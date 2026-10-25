"""A stdlib fake of the /swarm/v1 stream endpoints for the SDK unit tests (no jar). Not a test module itself.

Each handler is a plain function the test sets on the server: ``on_chunk(chunk, frame_flags)`` returns
``(status, headers, body)``, ``on_extend()`` likewise; ``on_get(handler)`` writes a whole GET answer. Every request is
recorded in ``events`` as ``(time, kind, value)``; ``inflight``/``peak`` count concurrent ``:chunk`` requests.
"""
import http.server
import json
import pathlib
import struct
import sys
import threading
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from sjws_swarm.chunks import StreamChunk, decode_chunk  # noqa: E402
from sjws_swarm.client import Client, Lease  # noqa: E402
from sjws_swarm.credit import Credit  # noqa: E402
from sjws_swarm.frames import Frame, FrameType, decode  # noqa: E402

SID = bytes.fromhex("0123456789abcdeffedcba9876543210")
WIRE = "application/vnd.sjws.swarm.v1"


def problem(status, type_="about:blank", reason=None, detail="", **extra):
    p = {"type": type_, "title": "t", "status": status, "detail": detail}
    if reason is not None:
        p["reason"] = reason
    p.update(extra)
    return status, {"Content-Type": "application/problem+json"}, json.dumps(p).encode()


def credit(chunks, nbytes, sid=SID, attempt=1):
    return 200, {"Content-Type": WIRE}, Frame(FrameType.CREDIT, 0, Credit(sid, attempt, chunks, nbytes).encode()).encode()


NO_CONTENT = (204, {}, b"")


class FakeServer(http.server.ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self):
        super().__init__(("127.0.0.1", 0), _Handler)
        self.lock = threading.Lock()
        self.events = []
        self.chunks = []  # every StreamChunk received, in arrival order
        self.inflight = 0
        self.peak = 0
        self.on_chunk = lambda c, flags: NO_CONTENT
        self.on_extend = lambda: NO_CONTENT
        self.on_get = None
        self.on_post = None  # (path, body) -> (status, headers, body) for anything else
        threading.Thread(target=self.serve_forever, daemon=True).start()

    def handle_error(self, request, client_address):
        pass  # clients dropping keep-alive or streaming connections is part of the tests

    @property
    def url(self):
        return f"http://127.0.0.1:{self.server_address[1]}"

    def client(self):
        return Client(self.url, token="t", timeout=10)

    def lease(self, window=64, attempt=1, deadline_in=60.0, visibility_ms=30_000, sid=SID):
        return Lease(self.client(), "pool~0~1", int(time.time() * 1000 + deadline_in * 1000), b"", None, sid, attempt,
                     window, visibility_ms)

    def record(self, kind, value):
        with self.lock:
            self.events.append((time.monotonic(), kind, value))

    def kinds(self, kind):
        with self.lock:
            return [v for _, k, v in self.events if k == kind]

    def close(self):
        self.shutdown()
        self.server_close()


class _Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _answer(self, status, headers, body):
        self.send_response(status)
        for k, v in headers.items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        srv = self.server
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        path = self.path.split("?")[0]
        if path.endswith(":chunk"):
            frame, _ = decode(body)
            c = decode_chunk(frame.payload)
            with srv.lock:
                srv.inflight += 1
                srv.peak = max(srv.peak, srv.inflight)
                srv.chunks.append(c)
            srv.record("chunk", c)
            try:
                ans = srv.on_chunk(c, frame.flags)
            finally:
                with srv.lock:
                    srv.inflight -= 1
            srv.record("answer", (c.chunk_seq, ans[0]))
            self._answer(*ans)
        elif path.endswith(":extend"):
            srv.record("extend", self.path)
            self._answer(*srv.on_extend())
        elif path.endswith(":ack"):
            srv.record("ack", self.path)
            self._answer(204, {}, b"")
        elif srv.on_post is not None:
            srv.record("post", self.path)
            self._answer(*srv.on_post(self.path, body))
        else:
            self._answer(404, {}, b"")

    def do_GET(self):
        srv = self.server
        srv.record("get", (self.path, self.headers.get("Last-Event-ID"), self.headers.get("Accept")))
        srv.on_get(self)


def lease_frame(lease_id: str, deadline_ms: int = 1, body: bytes = b"") -> bytes:
    lid = lease_id.encode()
    return Frame(FrameType.LEASE, 0, struct.pack(">H", len(lid)) + lid + struct.pack(">q", deadline_ms) + body).encode()


def chunk_frame(seq, data=b"", flags=0, attempt=1, sid=SID) -> bytes:
    c = StreamChunk(sid, seq, flags, data, attempt)
    return Frame(FrameType.TOKEN, flags & 7, c.encode()).encode()


def write_chunked(handler, parts, content_type=WIRE, close_early=False):
    """Streams ``parts`` as an HTTP/1.1 chunked 200; ``close_early`` drops the connection without the final chunk."""
    handler.send_response(200)
    handler.send_header("Content-Type", content_type)
    handler.send_header("Transfer-Encoding", "chunked")
    handler.end_headers()
    for p in parts:
        if callable(p):
            p()
            continue
        handler.wfile.write(f"{len(p):x}\r\n".encode() + p + b"\r\n")
        handler.wfile.flush()
    if close_early:
        handler.close_connection = True
        return
    handler.wfile.write(b"0\r\n\r\n")
