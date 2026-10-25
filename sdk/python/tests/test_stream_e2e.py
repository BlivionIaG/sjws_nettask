"""End to end over HTTP/1.1 (S2c PR 7, #261 Q16 with P2): the SDK's ``Client.stream()`` reader and ``ChunkWriter`` against
a real sjws server. Runs at the advertised small window (window/sinkChunks 4/4, credit on), at 64/256 with credit on
(SDK default window 8, and window 64), and at 64/256 with credit off (``204`` answers).

HTTP/3 is not covered here: the Python SDK is stdlib-only and the stdlib has no QUIC client, and the server's HTTP/3
listener ignores request bodies today (§5.2 *Size*), so ``:stream`` and ``:chunk`` cannot run over HTTP/3 from any
client yet. ``LeaseStreamHttp3Test`` (Java) covers the ``:claim`` headers over HTTP/3.

Needs the built jar: SJWS_JAR=target/sjws_nettask-<version>.jar (CI sets it after `mvn verify`). Skipped otherwise.
"""
import socket
import threading
import time
import unittest

from test_lease_stream_e2e import JAR, _Server

from sjws_swarm import Client
from sjws_swarm.client import StreamCancelled
from sjws_swarm.stream import LAST_MAX, Chunk, Reset

CREDIT = ["-Dsjws.swarm.stream.credit.enabled=true"]


class _E2E:
    flags: list = []
    window_adv = 64
    credit = True

    @classmethod
    def setUpClass(cls):
        cls.server = _Server(cls.flags)
        cls.client = Client(f"http://127.0.0.1:{cls.server.port}", token=cls.server.token)

    @classmethod
    def tearDownClass(cls):
        cls.server.close()

    def worker(self, pool, n, result=b"", window=8, size=100, out=None, **kw):
        out = {} if out is None else out

        def run():
            try:
                (lease,) = self.client.claim(pool, wait=10)
                out["lease"] = lease
                with lease.stream(window=window, coalesce=False, **kw) as w:
                    out["writer"] = w
                    for i in range(n):
                        w.send(b"%06d" % i + b"." * (size - 6))
                    w.finish(result)
            except BaseException as e:  # noqa: BLE001 - reported by the test
                out["error"] = e

        t = threading.Thread(target=run)
        t.start()
        return t, out

    def expect(self, n, size=100):
        return b"".join(b"%06d" % i + b"." * (size - 6) for i in range(n))

    def test_binary_stream_with_a_large_final_result(self):
        result = bytes(range(256)) * 512  # 128 KiB: over LAST_MAX, so split (#267 L3)
        with self.client.stream("bin", b"prompt", deadline=60) as s:
            t, out = self.worker("bin", 300, result)
            evs = list(s)
        t.join(30)
        self.assertNotIn("error", out)
        w = out["writer"]
        self.assertEqual(min(8, self.window_adv), w.effective_window)
        self.assertLess(w.stats["max_span"], w.effective_window)
        self.assertTrue(all(isinstance(e, Chunk) and e.attempt == 1 for e in evs))
        self.assertEqual(list(range(len(evs))), [e.seq for e in evs])
        self.assertEqual(self.expect(300) + result, b"".join(e.data for e in evs))
        self.assertEqual(("ok", result[-LAST_MAX:]), (s.state, s.result))
        self.assertEqual(out["lease"].attempt, 1)
        if self.credit:
            self.assertEqual("credit", w._mode)
        else:
            self.assertEqual("none", w._mode)

    def test_sse_stream(self):
        with self.client.stream("sse", b"prompt", deadline=60, sse=True) as s:
            t, out = self.worker("sse", 50, b"done")
            text = b"".join(e.data for e in s)
        t.join(30)
        self.assertNotIn("error", out)
        self.assertEqual(self.expect(50) + b"done", text)
        self.assertEqual("ok", s.state)

    def resume(self, sse):
        pool = "resume-sse" if sse else "resume-bin"
        got = []
        with self.client.stream(pool, b"p", deadline=60, sse=sse) as s:
            t, out = self.worker(pool, 120)
            for e in s:
                got.append(e)
                if len(got) in (10, 60):  # the connection breaks mid-stream; the reader resumes after the last chunk
                    s._http._conn.sock.shutdown(socket.SHUT_RDWR)
        t.join(30)
        self.assertNotIn("error", out)
        self.assertGreaterEqual(s.reconnects, 2)  # plus any 409 "session busy" while the old reader detaches
        self.assertEqual(list(range(120)), [e.seq for e in got])
        self.assertEqual(self.expect(120), b"".join(e.data for e in got))

    def test_resume_binary(self):
        self.resume(False)

    def test_resume_sse(self):
        self.resume(True)

    def test_cancel_reaches_the_worker(self):
        out = {}

        def run():
            (lease,) = self.client.claim("cancel", wait=10)
            try:
                with lease.stream(coalesce=False, heartbeat=0) as w:
                    out["writer"] = w
                    for i in range(10_000):
                        w.send(b"tok%d " % i)
                        time.sleep(0.002)
                    w.finish()
            except StreamCancelled as e:
                out["cancelled"] = e

        with self.client.stream("cancel", b"p", deadline=60) as s:
            t = threading.Thread(target=run)
            t.start()
            n = 0
            for e in s:
                n += 1
                if n == 20:
                    self.assertEqual("cancelled", s.cancel())
            t.join(30)
        self.assertEqual("cancelled", s.state)
        self.assertEqual("cancelled", out["cancelled"].reason)

    def test_reset_after_a_lease_expires(self):
        with self.client.stream("reset", b"p", deadline=60) as s:
            (first,) = self.client.claim("reset", wait=10, visibility_ms=1000)
            w = first.stream(coalesce=False, heartbeat=0)
            for i in range(3):
                w.send(b"old%d" % i)
            while w.stats["requests"] < 3 or w._out or w._queue:
                time.sleep(0.01)
            w.close()  # the first worker dies without LAST; its lease expires and the task is redelivered
            evs = [next(s) for _ in range(3)]
            t, out = self.worker("reset", 5, b"new")
            evs += list(s)
        t.join(30)
        self.assertNotIn("error", out)
        self.assertEqual([b"old0", b"old1", b"old2"], [e.data for e in evs[:3]])
        second = out["lease"].attempt
        self.assertGreater(second, first.attempt)
        self.assertEqual(Reset(second), evs[3])
        self.assertEqual(list(range(6)), [e.seq for e in evs[4:]])
        self.assertTrue(all(e.attempt == second for e in evs[4:]))

    def test_heartbeat_keeps_a_long_stream_leased(self):
        out = {}

        def run():
            (lease,) = self.client.claim("hb", wait=10, visibility_ms=1500)
            with lease.stream(coalesce=False) as w:  # heartbeat on by default: a third of 1.5 s, at least 1 s
                for i in range(8):
                    w.send(b"slow%d" % i)
                    time.sleep(0.5)  # 4 s in all, well past the 1.5 s lease
                w.finish(b"end")
                out["writer"] = w

        with self.client.stream("hb", b"p", deadline=60) as s:
            t = threading.Thread(target=run)
            t.start()
            evs = list(s)
        t.join(30)
        self.assertFalse(any(isinstance(e, Reset) for e in evs), "no RESET: the heartbeat extended the lease")
        self.assertGreaterEqual(out["writer"].stats["heartbeats"], 3)
        self.assertEqual("ok", s.state)


@unittest.skipUnless(JAR, "SJWS_JAR not set")
class AdvertisedSmallWindowTest(_E2E, unittest.TestCase):
    """window/sinkChunks 4/4 with credit on: the SDK default of 8 is capped by the advertised 4 (#261 R1, P2)."""
    flags = ["-Dsjws.swarm.stream.window=4", "-Dsjws.swarm.stream.sinkChunks=4"] + CREDIT
    window_adv = 4

    def test_credit_refusals_then_recovery(self):
        with self.client.stream("slow-reader", b"p", deadline=60) as s:
            t, out = self.worker("slow-reader", 40, size=1000)
            time.sleep(1.5)  # nobody reads: credit runs out (sinkChunks 4)
            evs = list(s)
        t.join(30)
        self.assertNotIn("error", out)
        w = out["writer"]
        self.assertGreater(w.stats["credit_429s"] + w.stats["credit_probes"], 0, dict(w.stats))
        self.assertEqual(self.expect(40, 1000), b"".join(e.data for e in evs))
        self.assertEqual("ok", s.state)


@unittest.skipUnless(JAR, "SJWS_JAR not set")
class Defaults64x256CreditTest(_E2E, unittest.TestCase):
    """64/256 with credit on (#261 P2): the SDK default of 8, plus one run at window 64."""
    flags = CREDIT
    window_adv = 64

    def test_window_64(self):
        with self.client.stream("w64", b"p", deadline=60) as s:
            t, out = self.worker("w64", 1000, b"end", window=64)
            evs = list(s)
        t.join(30)
        self.assertNotIn("error", out)
        w = out["writer"]
        self.assertEqual(64, w.effective_window)
        self.assertLess(w.stats["max_span"], 64)
        self.assertEqual(self.expect(1000) + b"end", b"".join(e.data for e in evs))


@unittest.skipUnless(JAR, "SJWS_JAR not set")
class Defaults64x256NoCreditTest(_E2E, unittest.TestCase):
    """64/256 with the credit sub-flag off: every non-LAST chunk is answered 204 (no credit info, window only)."""
    flags = []
    window_adv = 64
    credit = False


if __name__ == "__main__":
    unittest.main()
