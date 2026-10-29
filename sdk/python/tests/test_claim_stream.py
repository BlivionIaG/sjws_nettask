"""``:claim`` stream headers in the SDK (#261 Q1, R1, R2; §5.2 *Workers*): golden entries, joined fields, fail-closed
counts, the advertised window, and the max=256 header sizes through http.client (the SDK transport)."""
import pathlib
import time
import unittest

from stream_fake import FakeServer, lease_frame

from sjws_swarm.client import LEASE_TRACE_HEADER, parse_lease_stream, parse_stream_window
from sjws_swarm.stream import ChunkWriter

ROOT = pathlib.Path(__file__).resolve().parents[3]
GOLDEN = ROOT / "tests" / "sjws_nettask" / "swarm" / "wire" / "golden-lease-stream-v1.txt"


def golden():
    entries, headers = {}, []
    for line in GOLDEN.read_text().splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        f = line.split(" ")
        if f[1] == "!":
            headers.append((f[0], f[2].split(","), f[3]))
        else:
            entries[f[0]] = (f[1], f[2], f[3])
    return entries, headers


class GoldenTest(unittest.TestCase):
    def test_entries_and_header_vectors(self):
        entries, headers = golden()
        self.assertEqual(5, len(entries))
        self.assertEqual(3, len(headers))
        for name, (cid, attempt, entry) in entries.items():
            got = parse_lease_stream([entry], 1)[0]
            self.assertEqual(None if cid == "-" else (bytes.fromhex(cid), int(attempt)), got, name)
        for name, names, value in headers:
            want = [None if entries[n][0] == "-" else (bytes.fromhex(entries[n][0]), int(entries[n][1])) for n in names]
            self.assertEqual(want, parse_lease_stream([value], len(names)), name)
            # the same value split across several fields joins back to the same entries
            parts = value.split(",")
            self.assertEqual(want, parse_lease_stream([",".join(parts[:1]), ",".join(parts[1:])] if len(parts) > 1
                                                     else parts, len(names)), name)

    def test_fail_closed(self):
        ok = "0123456789abcdeffedcba9876543210/1"
        for fields, n in (([ok], 2), ([ok + ",-"], 1), (["-", "-"], 3)):
            with self.assertRaises(ValueError, msg=(fields, n)):
                parse_lease_stream(fields, n)
        for bad in ("0123456789ABCDEFFEDCBA9876543210/1", "0123456789abcdeffedcba987654321/1",
                    "0123456789abcdeffedcba9876543210", "0123456789abcdeffedcba9876543210/4294967296",
                    "0123456789abcdeffedcba9876543210/-1", "0123456789abcdeffedcba9876543210/1.shard", "", "--"):
            with self.assertRaises(ValueError, msg=bad):
                parse_lease_stream([bad], 1)

    def test_window_header(self):
        self.assertIsNone(parse_stream_window(None))
        self.assertIsNone(parse_stream_window([]))
        self.assertEqual(4, parse_stream_window(["4"]))
        self.assertEqual(64, parse_stream_window(["64", " 64"]))
        for bad in (["0"], ["-1"], ["x"], ["4", "8"], ["٤"]):
            with self.assertRaises(ValueError, msg=bad):
                parse_stream_window(bad)


class ClaimTest(unittest.TestCase):
    def setUp(self):
        self.srv = FakeServer()
        self.answer = None

        def on_post(path, body):
            return self.answer

        self.srv.on_post = on_post
        self.c = self.srv.client()

    def tearDown(self):
        self.srv.close()

    def claim(self, n, headers, max_=4):
        body = b"".join(lease_frame(f"p~{i}~1", int(time.time() * 1000) + 60_000) for i in range(n))
        self.answer = (200, headers, body)
        return self.c.claim("p", max=max_, visibility_ms=12_000)

    def test_mixed_batch_and_joined_fields(self):
        # two separate header fields of the same name, as some servers or proxies may send
        def on_post(path, body):
            return 200, {}, b"".join(lease_frame(f"p~{i}~1", 1) for i in range(3))

        self.srv.on_post = on_post
        orig = self.srv.RequestHandlerClass._answer

        def answer(h, status, headers, body):
            h.send_response(status)
            h.send_header("sjws-lease-stream", "-,0123456789abcdeffedcba9876543210/2")
            h.send_header("sjws-lease-stream", "-")
            h.send_header("sjws-stream-window", "4")
            h.send_header("Content-Length", str(len(body)))
            h.end_headers()
            h.wfile.write(body)

        self.srv.RequestHandlerClass._answer = answer
        try:
            a, b, c = self.c.claim("p", max=3, visibility_ms=12_000)
        finally:
            self.srv.RequestHandlerClass._answer = orig
        self.assertEqual((None, None, None), (a.stream_id, a.attempt, a.stream_window))
        self.assertEqual((bytes.fromhex("0123456789abcdeffedcba9876543210"), 2, 4, 12_000),
                         (b.stream_id, b.attempt, b.stream_window, b.visibility_ms))
        self.assertIsNone(c.stream_id)
        self.assertEqual(4, ChunkWriter(b, heartbeat=0).effective_window)

    def test_count_mismatch_fails_closed(self):
        with self.assertRaises(ValueError):
            self.claim(2, {"sjws-lease-stream": "0123456789abcdeffedcba9876543210/1"})

    def test_no_header_means_unary_and_no_window_means_1(self):
        (lease,) = self.claim(1, {})
        self.assertIsNone(lease.stream_id)
        with self.assertRaises(ValueError):
            lease.stream()
        (lease,) = self.claim(1, {"sjws-lease-stream": "0123456789abcdeffedcba9876543210/1"})
        self.assertIsNone(lease.stream_window)
        self.assertEqual(1, ChunkWriter(lease, window=64, heartbeat=0).effective_window)

    def test_max_256_headers_fit_the_sdk_transport(self):
        """§5.2 *Size*: at max=256 the header is 11,263 B at attempt 2^32−1 and the traceparent 14,335 B; Python's
        http.client (64 KiB per line) reads both, so Netty's 8 KiB HTTP/3 limit does not apply to this SDK."""
        sids = [bytes([i]) * 16 for i in range(256)]
        entries = ",".join(s.hex() + "/4294967295" for s in sids)
        tp = ",".join("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01" for _ in range(256))
        self.assertEqual(11_263, len(entries))
        self.assertEqual(14_335, len(tp))
        leases = self.claim(256, {"sjws-lease-stream": entries, "sjws-stream-window": "64", LEASE_TRACE_HEADER: tp},
                            max_=256)
        self.assertEqual(256, len(leases))
        self.assertEqual(sids, [l.stream_id for l in leases])
        self.assertTrue(all(l.attempt == 4294967295 and l.stream_window == 64 for l in leases))

    def test_claim_default_max_stays_1(self):
        import inspect
        from sjws_swarm.client import Client
        self.assertEqual(1, inspect.signature(Client.claim).parameters["max"].default)


if __name__ == "__main__":
    unittest.main()
