"""Unary client for the /swarm/v1 endpoints (agent-swarm.md §11.2). Standard library only (urllib).

    s = Client("http://127.0.0.1:8080")
    task_id = s.submit("summarize.v1", b"...")             # fire and forget
    result = s.call("summarize.v1", b"...", deadline=30)   # request/reply
    for lease in s.claim("summarize.v1", wait=5):          # agent side
        lease.reply(b"result")                             # reply, then ack (§4.4)

Tracing (S2b, swarm §9): pass ``traceparent=`` (a W3C Trace Context header value) to ``submit``/``call``.
When the server runs with tracing on, each ``Lease.traceparent`` is the claim's child span; pass it on to
downstream calls so the whole chain shares one trace. ``call_traced`` also returns the reply's traceparent.
The client never invents trace ids; it only passes them through.

Settles are always fenced: a lease id carries the delivery attempt, so a worker whose lease expired and
was re-leased gets LeaseLost instead of settling the new attempt. The asyncio API of §11.1 comes later; this
client is synchronous.

Token streaming (S2c PR 7, #261; needs a server with ``sjws.swarm.stream.enabled``): ``Client.stream()`` and
``Client.open_stream()`` read a stream (``sjws_swarm.stream.Stream``), and ``Lease.stream()`` writes one from a worker
(``sjws_swarm.stream.ChunkWriter``). Nothing of it runs unless called: a unary ``claim`` only reads the two claim
headers, which a flag-off server never sends.

Transport: stdlib ``urllib``/``http.client``, HTTP/1.1 only (``http://`` or ``https://``). Python's header line limit
(64 KiB) holds the largest ``:claim`` answer (``max=256``: 11,263 B of ``sjws-lease-stream`` plus 14,335 B of
``sjws-lease-traceparent``), so the HTTP/3 field-section limit of Netty's client (8 KiB) does not apply here; ``claim``
still defaults to ``max=1`` (§5.2 *Size*).
"""
from __future__ import annotations

import json
import os
import re
import struct
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field

from .frames import Frame, FrameType, LAST, decode

CONTENT_TYPE = "application/vnd.sjws.swarm.v1"
LEASE_TRACE_HEADER = "sjws-lease-traceparent"
LEASE_STREAM_HEADER = "sjws-lease-stream"
STREAM_WINDOW_HEADER = "sjws-stream-window"
CANCELLED_TYPE = "https://sjws.dev/problems/cancelled"
_STREAM_ENTRY = re.compile(r"([0-9a-f]{32})/([0-9]{1,10})")
_MAX_U32 = 0xFFFFFFFF


class SwarmError(Exception):
    """An RFC 9457 problem from the server."""

    def __init__(self, status: int, problem: dict):
        super().__init__(f"{status} {problem.get('title', '')}: {problem.get('detail', '')}")
        self.status = status
        self.problem = problem


class DeadlineExceeded(SwarmError):
    pass


class LeaseLost(SwarmError):
    """409: the lease is no longer held (stale attempt or no longer in flight)."""


class StreamCancelled(LeaseLost):
    """409 ``problems/cancelled``: the task was cancelled (``reason == "cancelled"``) or is past its deadline
    (``reason == "deadline"``). A ``LeaseLost``, so ``except LeaseLost`` keeps working (#261 Q11)."""

    @property
    def reason(self) -> str:
        return str(self.problem.get("reason") or "cancelled")


class TooManyStreams(SwarmError):
    """503 on ``:stream``: the node or agent session limit is reached."""


class SessionGone(SwarmError):
    """404 on a stream read: the session is unknown, expired, or another agent's."""


class StreamError(SwarmError):
    """The stream ended with ``error`` (the worker's ``LAST|ERROR``); ``message`` is its payload, when known."""

    def __init__(self, message: bytes = b"", problem: dict | None = None):
        super().__init__(0, problem or {"title": "stream error", "detail": message.decode(errors="replace")})
        self.message = message


class StreamGap(StreamError):
    """The server could not deliver ``from_seq``..``to_seq`` (``None``: open-ended); gaps are never skipped (§5.2)."""

    def __init__(self, from_seq: int, to_seq: int | None, reason: str):
        super().__init__(b"", {"title": "gap", "detail": f"{from_seq} {'?' if to_seq is None else to_seq} {reason}"})
        self.from_seq = from_seq
        self.to_seq = to_seq
        self.reason = reason


@dataclass
class Lease:
    client: "Client" = field(repr=False)
    id: str
    deadline_ms: int
    body: bytes
    traceparent: str | None = None
    stream_id: bytes | None = None  # the task's correlation id from sjws-lease-stream; None for a unary task
    attempt: int | None = None  # the lease attempt from sjws-lease-stream (never parsed from the lease id)
    stream_window: int | None = None  # sjws-stream-window of the claim answer, or None when absent
    visibility_ms: int = 30_000  # what claim asked for; the stream heartbeat re-requests it (#261 Q15)

    def ack(self) -> None:
        self.client._settle(self.id, "ack")

    def nack(self, delay_ms: int = 0) -> None:
        self.client._settle(self.id, "nack", {"delayMs": delay_ms})

    def reject(self, reason: str) -> None:
        self.client._settle(self.id, "reject", {"reason": reason})

    def extend(self, visibility_ms: int) -> None:
        self.client._settle(self.id, "extend", {"visibilityMs": visibility_ms})

    def reply(self, result: bytes) -> None:
        self.client._settle(self.id, "reply", body=Frame(FrameType.RESULT, LAST, result).encode())

    def stream(self, window: int = 8, coalesce: bool = True, heartbeat: float | None = None):
        """A ``ChunkWriter`` for this stream task (see ``sjws_swarm.stream``). ``window`` (1..64, default 8) is capped
        by the claim's ``sjws-stream-window``, or 1 without it; ``heartbeat`` is the ``:extend`` period in seconds
        (default: a third of the lease time left, at least 1 s; 0 turns it off)."""
        from .stream import ChunkWriter
        return ChunkWriter(self, window=window, coalesce=coalesce, heartbeat=heartbeat)


class Client:
    def __init__(self, base_url: str, timeout: float = 60.0, token: str | None = None):
        """``token`` is the agent's bearer token; defaults to the SJWS_SWARM_TOKEN environment variable."""
        self.base = base_url.rstrip("/") + "/swarm/v1/"
        self.timeout = timeout
        self._token = token if token is not None else os.environ.get("SJWS_SWARM_TOKEN")

    def __repr__(self) -> str:
        return f"Client({self.base!r})"  # never shows the token

    def submit(self, service: str, body: bytes, deadline: float | None = None,
               traceparent: str | None = None) -> bytes:
        """Submits a task without waiting; returns its 16-byte task id."""
        q = {} if deadline is None else {"deadlineMs": int(deadline * 1000)}
        status, data, _ = self._post("tasks/" + _seg(service), q, Frame(FrameType.TASK, 0, body).encode(),
                                     traceparent=traceparent)
        return _one(data, FrameType.TASK).payload

    def call(self, service: str, body: bytes, deadline: float = 30.0, traceparent: str | None = None) -> bytes:
        """Request/reply: returns the reply body, or raises DeadlineExceeded."""
        return self.call_traced(service, body, deadline, traceparent)[0]

    def call_traced(self, service: str, body: bytes, deadline: float = 30.0,
                    traceparent: str | None = None) -> tuple[bytes, str | None]:
        """As ``call``; also returns the reply's traceparent (the replying agent's claim span), or None."""
        q = {"deadlineMs": int(deadline * 1000)}
        status, data, headers = self._post("tasks/" + _seg(service) + ":call", q,
                                           Frame(FrameType.TASK, 0, body).encode(),
                                           timeout=max(self.timeout, deadline + 5), traceparent=traceparent)
        return _one(data, FrameType.RESULT).payload, headers.get("traceparent")

    def claim(self, service: str, max: int = 1, visibility_ms: int = 30_000, wait: float = 0) -> list[Lease]:
        """Claims up to ``max`` tasks. Keep ``max`` small: each stream task adds up to 44 B of ``sjws-lease-stream``
        (and 56 B of ``sjws-lease-traceparent`` with tracing on) to the answer's header (§5.2 *Size*)."""
        q = {"max": max, "visibilityMs": visibility_ms, "waitMs": int(wait * 1000)}
        status, data, headers = self._post("tasks/" + _seg(service) + ":claim", q, b"",
                                           timeout=max_(self.timeout, wait + 5))
        spans = (headers.get(LEASE_TRACE_HEADER) or "").split(",") if headers.get(LEASE_TRACE_HEADER) else []
        streams = headers.get_all(LEASE_STREAM_HEADER)
        out = []
        view = memoryview(data)
        while view:
            got = decode(view)
            if got is None:
                raise ValueError("truncated LEASE frame")
            frame, used = got
            view = view[used:]
            if frame.type != FrameType.LEASE:
                raise ValueError(f"expected LEASE, got {frame.type.name}")
            p = frame.payload
            (n,) = struct.unpack_from(">H", p, 0)
            lease_id = p[2:2 + n].decode()
            (deadline,) = struct.unpack_from(">q", p, 2 + n)
            span = spans[len(out)] if len(out) < len(spans) and spans[len(out)] != "-" else None
            out.append(Lease(self, lease_id, deadline, p[2 + n + 8:], span, visibility_ms=visibility_ms))
        if streams:
            _apply_stream_headers(out, streams, headers.get_all(STREAM_WINDOW_HEADER))
        return out

    def stream(self, service: str, body: bytes, deadline: float = 30.0, traceparent: str | None = None,
               sse: bool = False, reconnect: bool = True):
        """Submits a streaming call (``:stream``) and returns its ``Stream`` (iterate it for ``Chunk``/``Reset``)."""
        from .stream import Stream
        q = {"deadlineMs": int(deadline * 1000)}
        try:
            _, data, headers = self._post("tasks/" + _seg(service) + ":stream", q, Frame(FrameType.TASK, 0, body).encode(),
                                          traceparent=traceparent)
        except SwarmError as e:
            if e.status == 503 and type(e) is SwarmError:
                raise TooManyStreams(e.status, e.problem) from None
            raise
        return Stream(self, _one(data, FrameType.TASK).payload, location=headers.get("location"), sse=sse,
                      reconnect=reconnect, deadline=deadline)

    def open_stream(self, session_id: bytes, attempt: int | None = None, from_seq: int = 0, sse: bool = False,
                    reconnect: bool = True, deadline: float | None = None):
        """Reads (or resumes) a known session, e.g. from another process: from ``from_seq`` of ``attempt``."""
        from .stream import Stream
        return Stream(self, session_id, sse=sse, reconnect=reconnect, deadline=deadline, attempt=attempt,
                      from_seq=from_seq)

    def _settle(self, lease_id: str, verb: str, query: dict | None = None, body: bytes = b"") -> None:
        self._post("leases/" + _seg(lease_id) + ":" + verb, query or {}, body)

    def _headers(self, traceparent: str | None = None) -> dict:
        h = {"Content-Type": CONTENT_TYPE}
        if self._token:
            h["Authorization"] = "Bearer " + self._token
        if traceparent:
            h["traceparent"] = traceparent
        return h

    def _post(self, path: str, query: dict, body: bytes, timeout: float | None = None,
              traceparent: str | None = None) -> tuple[int, bytes, dict]:
        url = self.base + path + ("?" + urllib.parse.urlencode(query) if query else "")
        req = urllib.request.Request(url, data=body, method="POST", headers=self._headers(traceparent))
        try:
            with urllib.request.urlopen(req, timeout=timeout or self.timeout) as r:
                return r.status, r.read(), r.headers  # an http.client.HTTPMessage: case-insensitive, get_all
        except urllib.error.HTTPError as e:
            raise _problem_error(e.code, parse_problem(e.read(), str(e.reason))) from None


def _problem_error(status: int, problem: dict) -> SwarmError:
    if status == 409:
        return (StreamCancelled if problem.get("type") == CANCELLED_TYPE else LeaseLost)(status, problem)
    return (DeadlineExceeded if status == 504 else SwarmError)(status, problem)


def parse_problem(raw: bytes, status_text: str = "") -> dict:
    try:
        problem = json.loads(raw)
        if isinstance(problem, dict):
            return problem
    except ValueError:
        pass
    return {"title": status_text, "detail": raw.decode(errors="replace")}


def parse_lease_stream(fields: list[str], leases: int) -> list[tuple[bytes, int] | None]:
    """``sjws-lease-stream`` (#261 Q1): every field of the name joined, then one entry per LEASE frame in order:
    ``(stream_id, attempt)`` or None for ``-``. Fails closed (ValueError) on a count mismatch or a malformed entry, so
    a stream id is never attributed by position after a mismatch."""
    entries = [e.strip() for e in ",".join(fields).split(",")]
    if len(entries) != leases:
        raise ValueError(f"{LEASE_STREAM_HEADER} has {len(entries)} entries for {leases} leases")
    out: list[tuple[bytes, int] | None] = []
    for e in entries:
        if e == "-":
            out.append(None)
            continue
        m = _STREAM_ENTRY.fullmatch(e)
        if m is None or int(m.group(2)) > _MAX_U32:
            raise ValueError(f"{LEASE_STREAM_HEADER} entry {e!r}")
        out.append((bytes.fromhex(m.group(1)), int(m.group(2))))
    return out


def parse_stream_window(fields: list[str] | None) -> int | None:
    """``sjws-stream-window`` (#261 R1): a positive decimal, or None when absent. Repeated fields must agree."""
    if not fields:
        return None
    values = {v.strip() for v in ",".join(fields).split(",")}
    if len(values) != 1:
        raise ValueError(f"{STREAM_WINDOW_HEADER} {sorted(values)}")
    (v,) = values
    if not v.isdigit() or not v.isascii() or int(v) < 1:
        raise ValueError(f"{STREAM_WINDOW_HEADER} {v!r}")
    return int(v)


def _apply_stream_headers(leases: list[Lease], streams: list[str], window: list[str] | None) -> None:
    entries = parse_lease_stream(streams, len(leases))
    adv = parse_stream_window(window)
    for lease, e in zip(leases, entries):
        if e is not None:
            lease.stream_id, lease.attempt = e
            lease.stream_window = adv


def max_(a: float, b: float) -> float:
    return a if a > b else b


def _seg(s: str) -> str:
    return urllib.parse.quote(s, safe="~")


def _one(data: bytes, want: FrameType) -> Frame:
    got = decode(data)
    if got is None or got[1] != len(data):
        raise ValueError("expected exactly one frame")
    if got[0].type != want:
        raise ValueError(f"expected {want.name}, got {got[0].type.name}")
    return got[0]
