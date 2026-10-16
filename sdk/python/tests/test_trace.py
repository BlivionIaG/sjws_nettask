"""Traceparent pass-through in the client (S2b), against a stub server: no jar needed."""
import http.server
import pathlib
import sys
import struct
import threading
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from sjws_swarm.client import Client, LEASE_TRACE_HEADER
from sjws_swarm.frames import Frame, FrameType, LAST

TP = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
CHILD = "00-4bf92f3577b34da6a3ce929d0e0e4736-1111111111111111-01"


class Stub(http.server.BaseHTTPRequestHandler):
    seen = []

    def log_message(self, *a):
        pass

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        self.rfile.read(n)
        Stub.seen.append((self.path, self.headers.get("traceparent")))
        if ":claim" in self.path:
            def lease(i):
                lid = f"L{i}".encode()
                return Frame(FrameType.LEASE, 0, struct.pack(">H", len(lid)) + lid + struct.pack(">q", 1) + b"b").encode()
            body = lease(0) + lease(1)
            self.send_response(200)
            self.send_header(LEASE_TRACE_HEADER, CHILD + ",-")
        elif ":call" in self.path:
            body = Frame(FrameType.RESULT, LAST, b"r").encode()
            self.send_response(200)
            self.send_header("traceparent", CHILD)
        else:
            body = Frame(FrameType.TASK, 0, b"x" * 16).encode()
            self.send_response(202)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class TraceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Stub)
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        cls.c = Client(f"http://127.0.0.1:{cls.srv.server_address[1]}", token="")

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()

    def setUp(self):
        Stub.seen.clear()

    def test_submit_and_call_send_traceparent(self):
        self.c.submit("p", b"x", traceparent=TP)
        body, tp = self.c.call_traced("p", b"x", traceparent=TP)
        self.assertEqual((b"r", CHILD), (body, tp))
        self.assertEqual(b"r", self.c.call("p", b"x"))
        self.assertEqual([TP, TP, None], [h for _, h in Stub.seen])

    def test_claim_maps_spans_to_leases(self):
        leases = self.c.claim("p", max=2)
        self.assertEqual([CHILD, None], [l.traceparent for l in leases])


if __name__ == "__main__":
    unittest.main()
