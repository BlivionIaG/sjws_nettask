"""Token streaming for the synchronous client (agent-swarm.md §5.2, §11.2; S2c PR 7, rulings on #261, #267).

Caller side::

    with client.stream("chat.v1", b"prompt", deadline=60) as s:       # POST tasks/{pool}:stream
        for ev in s:                                                  # binary by default; sse=True for text
            if isinstance(ev, Reset): discard_rendered_output()       # a new attempt restarts at chunkSeq 0
            else: render(ev.data)                                     # Chunk(attempt, seq, data)
        s.state                                                       # "ok" or "cancelled"; error/deadline raise

Worker side::

    for lease in client.claim("chat.v1", wait=5):
        if lease.stream_id is None: ...                               # a unary task: lease.reply()
        with lease.stream() as w:                                     # ChunkWriter
            for tok in generate(lease.body): w.send(tok)
            w.finish(b"")                                             # LAST; leaving without finish sends LAST|ERROR

Writer rules (each is a ruling; the PR body maps them to tests):
- Effective window = min(``window``, the claim's ``sjws-stream-window``), or 1 when that header is absent; ``window``
  defaults to 8, at most 64 (#261 Q7, R1). What is capped is the **seq span**: highest issued seq minus lowest seq not
  yet accepted stays below the effective window, so out-of-order arrivals never reach the server's reorder ``OVERFLOW``
  (#261 P1). Sender threads (one keep-alive connection each) start lazily, at most the effective window.
- Credit (#261 Q8): one request in flight until the first ``200`` CREDIT (slow start); then the newest-issued answered
  snapshot, minus the chunks and bytes of requests issued after it and still outstanding. A ``204`` means "no credit
  info": the window alone caps. A CREDIT for another ``(streamId, attempt)`` is ignored and counted. Credit is a hint:
  with nothing in flight the writer never stalls at 0; it backs off (Q9) and sends, and the server decides.
- ``429`` (Q9, #267 Q4): ``problems/credit`` (session or ``"scope":"node"``) retries the same ``(attempt, chunkSeq)``
  after 5 ms doubling, ±20 % jitter, capped at ``Retry-After`` (1 s); any other ``429`` waits ``Retry-After`` exactly.
  No new chunk goes out while a ``429`` retry is pending.
- ``LAST`` (Q10, #267 Q3): sent after every earlier chunk is accepted, with seq = the next seq, never throttled on
  credit. Its data is always at most 64 KiB (``LAST_MAX``), credit or not: a larger ``finish(result)`` goes out
  entirely as ordinary chunks, then an empty LAST, so the reply is empty rather than a truncated tail (#279 C1).
  Every retry is byte-identical at the same seq: transport errors, ``503``/``504`` and ``429`` (after ``Retry-After``,
  #279 C3), bounded (#279 N1). Q11 option B: a ``409 NOT_IN_FLIGHT`` on a retry counts as done, with
  ``last_outcome == "unknown"``, only when every earlier attempt was a transport error, ``503`` or ``504``; every
  other ``409`` raises.
- Cancel (Q10, Q11, Q15): a ``409 cancelled``/``deadline`` from ``:chunk`` or from the heartbeat's ``:extend`` sends
  ``LAST|CANCELLED`` at once and makes the next call raise ``StreamCancelled``.
- Leaving the ``with`` block without ``finish()`` sends ``LAST|ERROR`` (normal exit: "writer closed without finish",
  logged as a warning), never a plain ``LAST`` (Q6).
- Heartbeat on by default (Q15): ``:extend`` with the claim's ``visibility_ms`` every third of the lease time left
  (at least 1 s); ``heartbeat=0`` turns it off; it stops once ``LAST`` is sent. Claim with ``visibility_ms >= 3000``
  (one-time warning below that).

Standard library only; HTTP/1.1 over ``http.client``.
"""
from __future__ import annotations

import collections
import http.client
import logging
import random
import threading
import time
import urllib.parse
from dataclasses import dataclass

from .chunks import FIRST, RESET, StreamChunk, decode_chunk
from .client import (CANCELLED_TYPE, CONTENT_TYPE, DeadlineExceeded, LeaseLost, SessionGone, StreamCancelled,
                     StreamError, StreamGap, SwarmError, _problem_error, _seg, parse_problem)
from .credit import decode_credit
from .frames import CANCELLED, ERROR, HEADER, LAST, Frame, FrameType, decode

log = logging.getLogger("sjws_swarm.stream")

DEFAULT_WINDOW = 8
MAX_WINDOW = 64
LAST_MAX = 64 * 1024  # #267 Q3 (L3): LAST data cap, applied whether or not credit is on
CHUNK_MAX = 1 << 20  # #261 Q14: TaskEnvelope.MAX_INLINE_BODY
COALESCE_BYTES = 4096  # #261 Q13: a chunk closes at 4 KiB or 20 ms
COALESCE_SECONDS = 0.020
CREDIT_TYPE = "https://sjws.dev/problems/credit"
CREDIT_FLOOR = 0.005  # #261 Q9: 5 ms, doubling, ±20 % jitter, capped at Retry-After
RETRY_FLOOR = 0.1  # #261 Q5: 0.1 s doubling to 2 s, 5 tries (reader reconnects; also writer transport errors)
RETRY_CAP = 2.0
RETRIES = 5
NOT_IN_FLIGHT = "NOT_IN_FLIGHT"
LAST_GRACE = 35.0  # past the lease's expected end, a LAST retry would only meet NOT_IN_FLIGHT (#279 N1)
LAST_RETRY_CAP = 20
_warned_short_visibility = False


@dataclass(frozen=True)
class Chunk:
    attempt: int
    seq: int
    data: bytes


@dataclass(frozen=True)
class Reset:
    """The stream restarted under ``attempt`` from chunkSeq 0: discard what was rendered."""
    attempt: int


def credit_backoff(tries: int, retry_after: float = 1.0, rnd: random.Random | None = None) -> float:
    """#261 Q9: 5 ms × 2^tries, ±20 % jitter, never above ``Retry-After``."""
    base = CREDIT_FLOOR * (2 ** min(tries, 20))
    return min(retry_after, base * (rnd or random).uniform(0.8, 1.2))


def retry_backoff(tries: int, rnd: random.Random | None = None) -> float:
    """#261 Q5: 0.1 s doubling to 2 s, plus up to 20 % jitter."""
    return min(RETRY_CAP, RETRY_FLOOR * (2 ** min(tries, 20))) * (rnd or random).uniform(1.0, 1.2)


def _retry_after(headers) -> float:
    v = headers.get("retry-after") if headers is not None else None
    try:
        return max(0.0, float(int(v.strip())))
    except (AttributeError, ValueError):
        return 1.0


class _Http:
    """One keep-alive HTTP/1.1 connection to the client's server; reopened after any transport error."""

    def __init__(self, client, timeout: float | None):
        u = urllib.parse.urlsplit(client.base)
        self._https = u.scheme == "https"
        self._host = u.hostname
        self._port = u.port
        self._prefix = u.path
        self._timeout = timeout
        self._client = client
        self._conn: http.client.HTTPConnection | None = None

    def _open(self) -> http.client.HTTPConnection:
        if self._conn is None:
            cls = http.client.HTTPSConnection if self._https else http.client.HTTPConnection
            self._conn = cls(self._host, self._port, timeout=self._timeout)
        return self._conn

    def request(self, method: str, path: str, body: bytes | None = None, headers: dict | None = None,
                stream: bool = False):
        """(status, headers, body); with ``stream`` the response itself is returned instead of its body."""
        h = self._client._headers()
        if headers:
            h.update(headers)
        if body is None:
            h.pop("Content-Type", None)
        try:
            c = self._open()
            c.request(method, self._prefix + path, body=body, headers=h)
            r = c.getresponse()
            if stream and r.status == 200:
                return r.status, r.headers, r
            data = r.read()
            if r.will_close:
                self.close()
            return r.status, r.headers, data
        except (OSError, http.client.HTTPException):
            self.close()
            raise

    def close(self) -> None:
        c, self._conn = self._conn, None
        if c is not None:
            c.close()


# ----------------------------------------------------------------------------------------------------- caller side
class Stream:
    """A stream session being read: iterate for ``Chunk``/``Reset`` events (#261 Q3–Q5).

    Iteration ends with ``state`` set to ``"ok"`` or ``"cancelled"``; ``error`` raises ``StreamError``, a gap
    ``StreamGap``, ``deadline`` ``DeadlineExceeded``, an unknown session ``SessionGone``. With ``reconnect`` (default)
    a transport error or an early EOF resumes from the last delivered ``(attempt, seq)``: binary
    ``?attempt=a&from=seq+1``, SSE ``Last-Event-ID: a.seq``; 5 tries, 0.1 s doubling to 2 s with jitter, bounded by the
    deadline; ``409 session busy`` is retried the same way. Over binary, ``result`` holds the ``LAST`` chunk's bytes
    (the task's reply), which are also yielded as the final ``Chunk`` when not empty: concatenating every ``Chunk`` is
    the full output, so do not append ``result`` too. Over SSE ``result`` stays None. SSE carries text: the server
    replaces invalid UTF-8 with U+FFFD and line breaks arrive as ``\\n``; a worker's ``LAST|ERROR`` text arrives as a
    final chunk event (server behaviour) and also fills ``StreamError.message``; over binary it is only in
    ``StreamError.message``.
    """

    def __init__(self, client, session_id: bytes, location: str | None = None, sse: bool = False,
                 reconnect: bool = True, deadline: float | None = None, attempt: int | None = None,
                 from_seq: int = 0, _rnd: random.Random | None = None):
        if len(session_id) != 16:
            raise ValueError("session_id must be 16 bytes")
        self._client = client
        self.session_id = bytes(session_id)
        self.location = location or "/swarm/v1/replies/" + self.session_id.hex()
        self.sse = sse
        self.reconnect = reconnect
        self._deadline_at = None if deadline is None else time.monotonic() + deadline
        self._attempt = attempt  # resume position: the last delivered (attempt, seq); seq -1 = none yet
        self._seq = from_seq - 1
        self._delivered = False
        self.state: str | None = None
        self.result: bytes | None = None
        self.reconnects = 0
        self._http = _Http(client, None)
        self._resp = None
        self._done = False
        self._pending_end = None  # a terminal to apply after yielding the final chunk
        self._sse_gap: str | None = None
        self._sse_last_chunk: tuple[str | None, bytes] = (None, b"")
        self._rnd = _rnd or random.Random()

    def __enter__(self) -> "Stream":
        return self

    def __exit__(self, *exc) -> bool:
        self.close()
        return False

    def __iter__(self) -> "Stream":
        return self

    def close(self) -> None:
        self._done = True
        self._drop()

    def cancel(self) -> str:
        """``POST tasks/{session}:cancel``; returns the server's state (``cancelled``, ``ok`` or ``error``)."""
        import json
        _, data, _ = self._client._post("tasks/" + self.session_id.hex() + ":cancel", {}, b"")
        return json.loads(data).get("state", "")

    def _drop(self) -> None:
        r, self._resp = self._resp, None
        if r is not None:
            r.close()
        self._http.close()

    def _remaining(self) -> float | None:
        return None if self._deadline_at is None else self._deadline_at - time.monotonic()

    def _query_and_headers(self) -> tuple[str, dict]:
        h = {"Accept": "text/event-stream" if self.sse else CONTENT_TYPE}
        if self.sse and self._attempt is not None and self._seq >= 0:
            h["Last-Event-ID"] = f"{self._attempt}.{self._seq}"
            return "", h
        q = {"from": self._seq + 1}
        if self._attempt is not None:
            q["attempt"] = self._attempt
        return "?" + urllib.parse.urlencode(q), h

    def _connect(self) -> None:
        q, h = self._query_and_headers()
        status, headers, r = self._http.request("GET", "replies/" + self.session_id.hex() + q, None, h, stream=True)
        if status == 200:
            self._resp = r
            return
        problem = parse_problem(r)
        if status == 404:
            raise SessionGone(status, problem)
        if status == 409:
            raise _Busy(status, problem)
        raise _problem_error(status, problem)

    def __next__(self):
        if self._pending_end is not None:
            end, self._pending_end = self._pending_end, None
            self._finish(end)
        if self._done:
            raise StopIteration
        tries = 0
        while True:
            try:
                if self._resp is None:
                    self._connect()
                ev = self._read_binary() if not self.sse else self._read_sse()
                return ev
            except _Terminal as t:
                self._finish(t)
            except (OSError, http.client.HTTPException, _Busy, _EarlyEof) as e:
                self._drop()
                rem = self._remaining()
                if not self.reconnect or tries >= RETRIES:
                    self._done = True
                    raise e if not isinstance(e, _EarlyEof) else ConnectionError("stream ended early") from None
                delay = retry_backoff(tries, self._rnd)
                if rem is not None and delay >= rem:
                    self._done = True
                    raise DeadlineExceeded(0, {"title": "deadline", "detail": "stream deadline during reconnect"}) \
                        from None
                tries += 1
                self.reconnects += 1
                time.sleep(delay)

    def _finish(self, t: "_Terminal"):
        self._done = True
        self._drop()
        if t.state is not None:
            self.state = t.state
            raise StopIteration
        raise t.error

    def _deliver(self, attempt: int, seq: int) -> None:
        self._attempt, self._seq = attempt, seq

    # binary: TOKEN frames (chunks, RESET), LAST, or ERROR|LAST "deadline" / "gap <from> <to|?> <reason>"
    def _read_exact(self, n: int) -> bytes:
        b = self._resp.read(n)
        if len(b) != n:
            raise _EarlyEof()
        return b

    def _read_binary(self):
        while True:
            head = self._read_exact(HEADER)
            n = int.from_bytes(head[3:6], "big")
            frame, _ = decode(head + (self._read_exact(n) if n else b""))
            if frame.type == FrameType.ERROR:
                text = frame.payload.decode(errors="replace")
                raise _Terminal(error=_end_error(text))
            if frame.type != FrameType.TOKEN:
                raise ValueError(f"unexpected {frame.type.name} frame on a stream")
            c = decode_chunk(frame.payload)
            if c.flags & RESET:
                self._deliver(c.attempt, -1)
                return Reset(c.attempt)
            self._deliver(c.attempt, c.chunk_seq)
            if c.flags & LAST:
                if c.flags & CANCELLED:
                    raise _Terminal(state="cancelled")
                if c.flags & ERROR:
                    raise _Terminal(error=StreamError(c.data))
                self.result = c.data
                if c.data:
                    self._pending_end = _Terminal(state="ok")
                    return Chunk(c.attempt, c.chunk_seq, c.data)
                raise _Terminal(state="ok")
            return Chunk(c.attempt, c.chunk_seq, c.data)

    # SSE: id / event / data / retry; one event per blank line
    def _read_sse(self):
        event, data, eid = "", [], None
        while True:
            line = self._resp.readline()
            if not line:
                raise _EarlyEof()
            line = line.decode("utf-8", errors="replace").rstrip("\r\n")
            if line:
                if line.startswith(":"):
                    continue
                k, _, v = line.partition(":")
                v = v[1:] if v.startswith(" ") else v
                if k == "event":
                    event = v
                elif k == "data":
                    data.append(v)
                elif k == "id":
                    eid = v
                continue
            if not event and not data:
                continue
            text = "\n".join(data)
            if eid:
                a, _, s = eid.partition(".")
                try:
                    if s:
                        self._deliver(int(a), int(s))
                except ValueError:
                    pass
            this_id, kind, event, data, eid = eid, event or "message", "", [], None
            if kind == "chunk":
                self._sse_last_chunk = (this_id, text.encode())
                return Chunk(self._attempt or 0, self._seq, text.encode())
            if kind == "reset":
                self._deliver(int(text), -1)
                return Reset(int(text))
            if kind == "gap":
                self._sse_gap = text
                continue
            if kind == "end":
                if text in ("ok", "cancelled"):
                    raise _Terminal(state=text)
                if text == "deadline":
                    raise _Terminal(error=_end_error("deadline"))
                if self._sse_gap is not None:
                    raise _Terminal(error=_end_error("gap " + self._sse_gap))
                # the server sends LAST|ERROR text as a chunk event with the end event's id (#279 C2)
                last_id, last_text = self._sse_last_chunk
                raise _Terminal(error=StreamError(last_text if this_id is not None and last_id == this_id else b""))


def _end_error(text: str) -> SwarmError:
    if text == "deadline":
        return DeadlineExceeded(0, {"title": "deadline", "detail": "stream deadline"})
    if text.startswith("gap "):
        parts = text.split(" ", 3)
        if len(parts) == 4:
            return StreamGap(int(parts[1]), None if parts[2] == "?" else int(parts[2]), parts[3])
    return StreamError(text.encode())


class _Terminal(Exception):
    def __init__(self, state: str | None = None, error: BaseException | None = None):
        super().__init__(state)
        self.state = state
        self.error = error


class _EarlyEof(Exception):
    pass


class _Busy(SwarmError):
    pass


# ----------------------------------------------------------------------------------------------------- worker side
@dataclass
class _Job:
    seq: int
    flags: int
    data: bytes
    issued: bool = False
    not_before: float = 0.0
    tries: int = 0  # consecutive 429s or transport errors of this chunk
    waiting: bool = False  # in the retry list


class ChunkWriter:
    """Sends a stream task's chunks (``POST leases/{id}:chunk``). Use it as a context manager; see the module doc.

    ``send(data)`` queues data and blocks only while the queue is full; ``flush()`` closes the coalesced chunk;
    ``finish(result)`` sends ``LAST`` (the reply); ``fail(message)`` ``LAST|ERROR``; ``close_cancelled()``
    ``LAST|CANCELLED``. ``stats`` counts requests, 429s, ignored CREDIT frames, the peak seq span and in-flight count.
    """

    def __init__(self, lease, window: int = DEFAULT_WINDOW, coalesce: bool = True, heartbeat: float | None = None,
                 _rnd: random.Random | None = None):
        if isinstance(window, bool) or not isinstance(window, int) or not 1 <= window <= MAX_WINDOW:
            raise ValueError(f"window {window!r}: 1..{MAX_WINDOW}")
        if lease.stream_id is None or lease.attempt is None:
            raise ValueError("not a stream task: the claim carried no sjws-lease-stream entry for this lease")
        if heartbeat is not None and heartbeat < 0:
            raise ValueError(f"heartbeat {heartbeat!r}")
        self.lease = lease
        self.window = window
        self.effective_window = min(window, lease.stream_window) if lease.stream_window else 1
        self.coalesce = coalesce
        self._rnd = _rnd or random.Random()
        self._cv = threading.Condition(threading.Lock())
        self._next_seq = 0
        self._queue: collections.deque[_Job] = collections.deque()  # numbered, not yet issued
        self._retry: list[_Job] = []  # issued and to be re-issued (429 or transport error)
        self._out: dict[int, _Job] = {}  # in flight, by issue index
        self._unaccepted: set[int] = set()  # issued seqs not yet accepted
        self._backing_off: set[int] = set()  # seqs refused (429) or failed in transport, until accepted
        self._issue = 0
        self._mode = "unknown"  # unknown (slow start) | credit | none (204: no credit info)
        self._snap: tuple[int, int, int] | None = None  # (issue index, chunks, bytes)
        self._zero_tries = 0
        self._probe_at: float | None = None
        self._buf = bytearray()
        self._buf_since = 0.0
        self._error: BaseException | None = None
        self._cancelled: StreamCancelled | None = None
        self._last_started = False
        self._finished = False
        self._closed = False
        self._threads: list[threading.Thread] = []
        self._idle = 0
        self.last_outcome: str | None = None  # "ok", or "unknown" (409 NOT_IN_FLIGHT on a LAST retry, Q11 option B)
        self.stats = collections.Counter()
        self._hb_stop = threading.Event()
        self._hb: threading.Thread | None = None
        self._lease_until = time.monotonic() + min(
            max(0.0, (lease.deadline_ms - time.time() * 1000) / 1000.0), lease.visibility_ms / 1000.0)
        if heartbeat != 0:
            global _warned_short_visibility
            if lease.visibility_ms < 3000 and not _warned_short_visibility:
                _warned_short_visibility = True
                log.warning("visibility_ms %d < 3000: the stream heartbeat runs at most once per second, so the lease "
                            "can expire between beats; claim stream tasks with visibility_ms >= 3000",
                            lease.visibility_ms)
            # the server's deadline against this clock moves with skew: never wait longer than the visibility (N2)
            left = min((lease.deadline_ms - time.time() * 1000) / 1000.0, lease.visibility_ms / 1000.0)
            first = heartbeat if heartbeat else max(1.0, left / 3)
            self._hb = threading.Thread(target=self._heartbeat, args=(first, heartbeat), daemon=True,
                                        name="sjws-stream-heartbeat")
            self._hb.start()

    # ------------------------------------------------------------------------------------------------ public API
    def __enter__(self) -> "ChunkWriter":
        return self

    def __exit__(self, exc_type, exc, tb) -> bool:
        try:
            with self._cv:
                done = self._last_started or isinstance(self._error, LeaseLost)
            if not done:
                if exc_type is None:
                    log.warning("lease %s: writer closed without finish; sending LAST|ERROR", self.lease.id)
                    self._terminal(LAST | ERROR, b"writer closed without finish", ignore_error=True)
                else:
                    msg = f"writer failed: {exc_type.__name__}: {exc}".encode(errors="replace")[:LAST_MAX]
                    try:
                        self._terminal(LAST | ERROR, msg, ignore_error=True)
                    except Exception:  # noqa: BLE001 - the caller's exception wins
                        log.exception("lease %s: LAST|ERROR after %s failed", self.lease.id, exc_type.__name__)
        finally:
            self.close()
        return False

    def send(self, data: bytes) -> None:
        data = bytes(data)
        if not data:
            return
        with self._cv:
            self._check()
            if not self.coalesce:
                self._enqueue_split(data)
            else:
                if self._buf and len(self._buf) + len(data) > COALESCE_BYTES:
                    self._close_buf(block=True)
                if len(data) >= COALESCE_BYTES:
                    self._enqueue_split(data)
                else:
                    if not self._buf:
                        self._buf_since = time.monotonic()
                    self._buf += data
                    if len(self._buf) >= COALESCE_BYTES:
                        self._close_buf(block=True)
            self._kick()

    def flush(self) -> None:
        """Closes the coalesced chunk now: queued, not accepted (``finish`` is the barrier that waits)."""
        with self._cv:
            self._check()
            self._close_buf(block=True)
            self._kick()

    def finish(self, result: bytes = b"") -> None:
        """Sends ``LAST`` once every earlier chunk is accepted. ``result`` of at most 64 KiB rides on the LAST and is
        the task's reply (``Stream.result``). Above 64 KiB (#267 L3, LAST always capped) all of it is streamed as
        ordinary chunks and the LAST is empty, so the reply and ``Stream.result`` are empty: the output is the
        concatenation of the chunks, never a truncated tail (#279 C1)."""
        result = bytes(result)
        if len(result) > LAST_MAX:
            with self._cv:
                self._check()
                self._close_buf(block=True)
                self._enqueue_split(result)
                self._kick()
            result = b""
        self._terminal(LAST, result)

    def fail(self, message: bytes = b"") -> None:
        """Ends the stream with ``LAST|ERROR``; ``message`` (at most 64 KiB) is the error text the reader gets."""
        message = bytes(message)
        if len(message) > LAST_MAX:
            raise ValueError(f"message {len(message)} B > {LAST_MAX}")
        self._terminal(LAST | ERROR, message)

    def close_cancelled(self) -> None:
        """Ends the stream with ``LAST|CANCELLED`` (after the outstanding chunks are accepted)."""
        self._terminal(LAST | CANCELLED, b"")

    def close(self) -> None:
        """Stops the heartbeat and the sender threads; sends nothing (use the context manager)."""
        with self._cv:
            self._closed = True
            self._cv.notify_all()
            threads = list(self._threads)
        self._hb_stop.set()
        me = threading.current_thread()
        for t in threads:
            if t is not me:
                t.join(5)

    @property
    def cancelled(self) -> StreamCancelled | None:
        return self._cancelled

    # --------------------------------------------------------------------------------------------- the scheduler
    def _check(self) -> None:
        if self._cancelled is not None:
            raise self._cancelled
        if self._error is not None:
            raise self._error
        if self._last_started or self._closed:
            raise RuntimeError("stream writer is closed")

    def _new_job(self, data: bytes) -> _Job:
        j = _Job(self._next_seq, FIRST if self._next_seq == 0 else 0, data)
        self._next_seq += 1
        return j

    def _enqueue_split(self, data: bytes) -> None:
        for i in range(0, len(data), CHUNK_MAX):
            while (len(self._queue) >= self.effective_window and self._error is None and self._cancelled is None
                   and not self._closed):
                self._kick()
                self._cv.wait()
            self._check()
            self._queue.append(self._new_job(data[i:i + CHUNK_MAX]))
            self._kick()

    def _close_buf(self, block: bool) -> None:
        if not self._buf:
            return
        data, self._buf = bytes(self._buf), bytearray()
        if block:
            self._enqueue_split(data)
        else:
            self._queue.append(self._new_job(data))

    def _kick(self) -> None:
        """Wakes a sender, starting one if none is idle (at most the effective window)."""
        self._cv.notify_all()
        work = self._queue or self._retry or self._buf
        if work and self._idle == 0 and len(self._threads) < self.effective_window and not self._closed:
            t = threading.Thread(target=self._sender, daemon=True, name=f"sjws-stream-send-{len(self._threads)}")
            self._threads.append(t)
            t.start()

    def _span_low(self, seq: int) -> int:
        return min(self._unaccepted) if self._unaccepted else seq

    def _credit_allows(self, j: _Job, now: float) -> bool:
        if self._mode == "none":
            return True
        if self._mode == "unknown":
            return not self._out  # slow start: one in flight until the first 200 CREDIT
        idx, chunks, nbytes = self._snap
        after = [o for i, o in self._out.items() if i > idx]
        left_c = chunks - len(after)
        left_b = nbytes - sum(len(o.data) for o in after)
        if left_c >= 1 and (len(j.data) <= left_b or not self._out):
            return True
        if self._out:
            return False  # an answer will bring a new snapshot
        # credit is a hint: nothing in flight, so back off and send; the server decides (200 or 429)
        if self._probe_at is None:
            self._probe_at = now + credit_backoff(self._zero_tries, 1.0, self._rnd)
            return False
        if now >= self._probe_at:
            self._probe_at = None
            self._zero_tries += 1
            self.stats["credit_probes"] += 1
            return True
        return False

    def _pick(self, now: float) -> _Job | None:
        if self._buf and now - self._buf_since >= COALESCE_SECONDS and not self._last_started:
            self._close_buf(block=False)
        if self._error is not None or self._cancelled is not None:
            return None
        if self._retry:
            j = min(self._retry, key=lambda r: (r.not_before, r.seq))
            if j.not_before <= now:
                self._retry.remove(j)
                j.waiting = False
                return j
            return None
        if self._backing_off:
            return None  # no new chunk while a 429 (or transport) retry is pending, in flight or not (#261 Q9)
        if not self._queue:
            return None
        j = self._queue[0]
        if j.seq - self._span_low(j.seq) >= self.effective_window:
            return None  # #261 P1: highest issued − lowest unaccepted < effective window
        if not self._credit_allows(j, now):
            return None
        self._queue.popleft()
        return j

    def _wake_in(self, now: float) -> float | None:
        t = []
        if self._buf:
            t.append(self._buf_since + COALESCE_SECONDS - now)
        if self._retry:
            t.append(min(r.not_before for r in self._retry) - now)
        if self._probe_at is not None:
            t.append(self._probe_at - now)
        return max(0.0, min(t)) if t else None

    def _sender(self) -> None:
        conn = _Http(self.lease.client, self.lease.client.timeout)
        path = "leases/" + _seg(self.lease.id) + ":chunk"
        try:
            while True:
                with self._cv:
                    while True:
                        if self._closed:
                            return
                        now = time.monotonic()
                        j = self._pick(now)
                        if j is not None:
                            break
                        self._idle += 1
                        self._cv.wait(self._wake_in(now))
                        self._idle -= 1
                    self._issue += 1
                    idx = self._issue
                    self._out[idx] = j
                    if not j.issued:
                        j.issued = True
                        self._unaccepted.add(j.seq)
                        self.stats["max_span"] = max(self.stats["max_span"], j.seq - min(self._unaccepted))
                    self.stats["requests"] += 1
                    self.stats["max_in_flight"] = max(self.stats["max_in_flight"], len(self._out))
                body = self._frame(j)
                try:
                    answer = conn.request("POST", path, body, {"Content-Type": CONTENT_TYPE})
                except (OSError, http.client.HTTPException) as e:
                    answer = e
                with self._cv:
                    cancel = self._settle(idx, j, answer)
                    self._cv.notify_all()
                if cancel is not None:
                    self._on_cancel(cancel)
        finally:
            conn.close()
            with self._cv:
                if threading.current_thread() in self._threads:
                    self._threads.remove(threading.current_thread())

    def _frame(self, j: _Job) -> bytes:
        c = StreamChunk(self.lease.stream_id, j.seq, j.flags, j.data, self.lease.attempt)
        return Frame(FrameType.TOKEN, j.flags & 7, c.encode()).encode()

    def _settle(self, idx: int, j: _Job, answer) -> StreamCancelled | None:
        """Applies one ``:chunk`` answer, under the lock. Returns a cancel to act on outside the lock."""
        del self._out[idx]
        now = time.monotonic()
        if isinstance(answer, BaseException):
            self.stats["transport_errors"] += 1
            if j.tries >= RETRIES:
                self._error = self._error or answer
                return None
            j.not_before = now + retry_backoff(j.tries, self._rnd)
            j.tries += 1
            self._retry.append(j)
            self._backing_off.add(j.seq)
            return None
        status, headers, data = answer
        if status in (200, 204):
            self._unaccepted.discard(j.seq)
            self._backing_off.discard(j.seq)
            if status == 204:
                self._mode, self._snap = "none", None
            else:
                self._apply_credit(idx, data)
            return None
        problem = parse_problem(data)
        if status == 429:
            ra = _retry_after(headers)
            if problem.get("type") == CREDIT_TYPE:
                self.stats["node_429s" if problem.get("scope") == "node" else "credit_429s"] += 1
                j.not_before = now + credit_backoff(j.tries, ra, self._rnd)
            else:
                self.stats["other_429s"] += 1
                j.not_before = now + ra
            j.tries += 1
            self._retry.append(j)
            self._backing_off.add(j.seq)
            return None
        err = _problem_error(status, problem)
        if isinstance(err, StreamCancelled):
            return err
        self._error = self._error or err
        if isinstance(err, LeaseLost):
            self._hb_stop.set()
        return None

    def _apply_credit(self, idx: int, data: bytes) -> None:
        try:
            got = decode(data)
            if got is None or got[0].type != FrameType.CREDIT:
                raise ValueError("no CREDIT frame")
            c = decode_credit(got[0].payload)
        except ValueError:
            self.stats["credit_invalid"] += 1
            return
        if c.stream_id != self.lease.stream_id or c.attempt != self.lease.attempt:
            self.stats["credit_mismatches"] += 1  # #261 Q8: ignored and counted
            return
        if self._snap is None or idx > self._snap[0] or self._mode != "credit":
            self._mode = "credit"
            self._snap = (idx, c.chunks, c.bytes)
            if c.chunks > 0:
                self._zero_tries = 0
                self._probe_at = None

    # ------------------------------------------------------------------------------------------- LAST and cancel
    def _terminal(self, flags: int, data: bytes, ignore_error: bool = False) -> None:
        with self._cv:
            if self._cancelled is not None:
                raise self._cancelled
            if self._error is not None and not ignore_error:
                raise self._error
            if self._last_started or self._closed:
                raise RuntimeError("stream writer is closed")
            self._close_buf(block=False)
            self._kick()
            while (self._queue or self._retry or self._out) and self._cancelled is None and (
                    self._error is None):
                self._cv.wait()
            if self._cancelled is not None:
                raise self._cancelled
            if self._error is not None:
                if not ignore_error:
                    raise self._error
                # best effort: queued jobs already own their seqs, so a LAST|ERROR after dropping them lands above a
                # hole and the reader may end by gap or deadline rather than "error" (#279 N4)
                self._queue.clear()
                self._retry.clear()
                while self._out:  # in-flight requests still finish; LAST must come after them
                    self._cv.wait()
            self._last_started = True
            seq = self._next_seq
            self._next_seq += 1
            self._cv.notify_all()
        self._hb_stop.set()  # the heartbeat stops once LAST is sent (#261 Q15, Architecture)
        try:
            self._send_last(seq, flags, data)
        finally:
            with self._cv:
                self._finished = True
                self._closed = True
                self._cv.notify_all()

    def _send_last(self, seq: int, flags: int, data: bytes) -> None:
        """Sends the LAST and retries the byte-identical body at the same seq (the server dedups it).

        - ``503``/``504`` and transport errors (no status) leave the outcome unknown and are retried.
        - A ``429`` (credit-typed or a route/gateway limiter) waits ``Retry-After`` and resends (#279 C3).
        - #261 Q11 as re-ruled (option B): a ``409 NOT_IN_FLIGHT`` is success, ``last_outcome == "unknown"``, only
          when every earlier attempt of this LAST failed with a transport error, 503 or 504. After any definite answer
          (a 4xx such as 429) the normal handling applies and it raises ``LeaseLost``.
        - Bounded (#279 N1): at most ``LAST_RETRY_CAP`` retries on 5xx/429 and ``RETRIES`` on transport errors, and
          none past the lease's expected end plus ``LAST_GRACE``; then the last error is raised.
        """
        if len(data) > LAST_MAX:
            raise ValueError(f"LAST {len(data)} B > {LAST_MAX}")
        conn = _Http(self.lease.client, max(self.lease.client.timeout, 35.0))
        c = StreamChunk(self.lease.stream_id, seq, flags | (FIRST if seq == 0 else 0), data, self.lease.attempt)
        body = Frame(FrameType.TOKEN, c.flags & 7, c.encode()).encode()
        path = "leases/" + _seg(self.lease.id) + ":chunk"
        attempts = 0  # sends so far
        only_unknown = True  # every earlier attempt failed with a transport error, 503 or 504
        tries = net_tries = 0
        stop_at = self._lease_until + LAST_GRACE
        try:
            while True:
                attempts += 1
                try:
                    status, headers, raw = conn.request("POST", path, body, {"Content-Type": CONTENT_TYPE})
                except (OSError, http.client.HTTPException):
                    self.stats["transport_errors"] += 1
                    if net_tries >= RETRIES or time.monotonic() > stop_at:
                        self.last_outcome = "unknown"
                        raise
                    time.sleep(retry_backoff(net_tries, self._rnd))
                    net_tries += 1
                    continue
                self.stats["requests"] += 1
                if status in (200, 204):
                    self.last_outcome = "ok"
                    return
                problem = parse_problem(raw)
                if status in (503, 504, 429):
                    if status == 429:
                        only_unknown = False  # a definite refusal: this LAST did not land
                        delay = _retry_after(headers)
                        self.stats["last_429s"] += 1
                    else:
                        delay = retry_backoff(min(tries, 4), self._rnd)
                    self.stats["last_retries"] += 1
                    if tries >= LAST_RETRY_CAP or time.monotonic() + delay > stop_at:
                        if status != 429:
                            self.last_outcome = "unknown"
                        raise _problem_error(status, problem)
                    time.sleep(delay)
                    tries += 1
                    continue
                err = _problem_error(status, problem)
                if (status == 409 and attempts > 1 and only_unknown and type(err) is LeaseLost
                        and problem.get("detail") == NOT_IN_FLIGHT):
                    self.last_outcome = "unknown"
                    log.warning("lease %s: last_outcome_unknown: outcome unknown, then confirmed by NOT_IN_FLIGHT "
                                "(earlier attempts: transport error, 503 or 504)", self.lease.id)
                    return
                if isinstance(err, StreamCancelled):
                    self._ack_quietly()  # the claim stays cached after a 409 cancelled: the worker acks it (§5.2)
                raise err
        finally:
            conn.close()

    def _ack_quietly(self) -> None:
        try:
            self.lease.ack()
        except (SwarmError, OSError) as e:
            log.info("lease %s: ack after cancel: %s", self.lease.id, e)

    def _on_cancel(self, err: StreamCancelled) -> None:
        """A 409 cancelled/deadline (from ``:chunk`` or ``:extend``): ``LAST|CANCELLED`` at once (#261 Q10)."""
        with self._cv:
            if self._cancelled is not None or self._last_started:
                return
            self._cancelled = err
            self._queue.clear()
            self._retry.clear()
            self._buf = bytearray()
            self._last_started = True
            seq = self._next_seq
            self._next_seq += 1
            self._cv.notify_all()
        self._hb_stop.set()
        try:
            self._send_last(seq, LAST | CANCELLED, b"")
        except StreamCancelled:
            pass  # acked by _send_last
        except (SwarmError, OSError) as e:
            log.warning("lease %s: LAST|CANCELLED failed: %s", self.lease.id, e)
        finally:
            with self._cv:
                self._finished = True
                self._closed = True
                self._cv.notify_all()

    def _heartbeat(self, first: float, fixed: float | None) -> None:
        wait = first
        while not self._hb_stop.wait(wait):
            try:
                t0 = time.monotonic()
                self.lease.extend(self.lease.visibility_ms)
                self._lease_until = t0 + self.lease.visibility_ms / 1000.0
                self.stats["heartbeats"] += 1
            except StreamCancelled as e:
                self._on_cancel(e)
                return
            except LeaseLost as e:
                with self._cv:
                    self._error = self._error or e
                    self._cv.notify_all()
                return
            except (SwarmError, OSError) as e:
                log.info("lease %s: heartbeat failed: %s", self.lease.id, e)
            wait = fixed if fixed else max(1.0, self.lease.visibility_ms / 3000.0)
