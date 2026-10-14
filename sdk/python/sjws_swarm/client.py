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
was re-leased gets LeaseLost instead of settling the new attempt. The asyncio API of §11.1 and streaming
come later; this client is synchronous.
"""
from __future__ import annotations

import json
import os
import struct
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field

from .frames import Frame, FrameType, LAST, decode

CONTENT_TYPE = "application/vnd.sjws.swarm.v1"
LEASE_TRACE_HEADER = "sjws-lease-traceparent"


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


@dataclass
class Lease:
    client: "Client" = field(repr=False)
    id: str
    deadline_ms: int
    body: bytes
    traceparent: str | None = None

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
        q = {"max": max, "visibilityMs": visibility_ms, "waitMs": int(wait * 1000)}
        status, data, headers = self._post("tasks/" + _seg(service) + ":claim", q, b"",
                                           timeout=max_(self.timeout, wait + 5))
        spans = (headers.get(LEASE_TRACE_HEADER) or "").split(",") if headers.get(LEASE_TRACE_HEADER) else []
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
            out.append(Lease(self, lease_id, deadline, p[2 + n + 8:], span))
        return out

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
                return r.status, r.read(), {k.lower(): v for k, v in r.headers.items()}
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                problem = json.loads(raw)
            except ValueError:
                problem = {"title": e.reason, "detail": raw.decode(errors="replace")}
            kind = DeadlineExceeded if e.code == 504 else LeaseLost if e.code == 409 else SwarmError
            raise kind(e.code, problem) from None


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
