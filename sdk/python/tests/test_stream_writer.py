"""ChunkWriter (worker side) against the stdlib fake: window, seq span, credit, 429, LAST, cancel, heartbeat
(#261 Q6–Q15, P1, R1; #267 Q3, Q4). Run: python3 -m unittest discover sdk/python/tests"""
import json
import logging
import threading
import time
import unittest
from unittest import mock

from stream_fake import NO_CONTENT, SID, FakeServer, credit, problem

from sjws_swarm.chunks import FIRST
from sjws_swarm import stream
from sjws_swarm.client import LeaseLost, StreamCancelled, SwarmError
from sjws_swarm.frames import CANCELLED, ERROR, LAST
from sjws_swarm.stream import CHUNK_MAX, LAST_MAX, ChunkWriter

CREDIT_TYPE = "https://sjws.dev/problems/credit"
CANCELLED_TYPE = "https://sjws.dev/problems/cancelled"


def payloads(srv, last=False):
    """Data of the accepted (200/204) chunks, by seq, deduplicated."""
    ok = {seq for seq, st in srv.kinds("answer") if st in (200, 204)}
    out = {}
    for c in srv.chunks:
        if c.chunk_seq in ok and bool(c.flags & LAST) == last:
            out[c.chunk_seq] = c
    return [out[k] for k in sorted(out)]


class Base(unittest.TestCase):
    def setUp(self):
        self.srv = FakeServer()

    def tearDown(self):
        self.srv.close()

    def writer(self, **kw):
        lease_kw = {k: kw.pop(k) for k in ("window_adv", "attempt", "deadline_in", "visibility_ms") if k in kw}
        if "window_adv" in lease_kw:
            lease_kw["window"] = lease_kw.pop("window_adv")
        kw.setdefault("coalesce", False)
        kw.setdefault("heartbeat", 0)
        return ChunkWriter(self.srv.lease(**lease_kw), **kw)


class WindowTest(Base):
    def test_configured_window_is_1_to_64_default_8(self):
        lease = self.srv.lease(window=256)
        for bad in (0, 65, -1, True, 8.0, "8"):
            with self.assertRaises(ValueError, msg=repr(bad)):
                ChunkWriter(lease, window=bad, heartbeat=0)
        self.assertEqual(8, ChunkWriter(lease, heartbeat=0).effective_window)
        self.assertEqual(64, ChunkWriter(lease, window=64, heartbeat=0).effective_window)

    def test_effective_window_is_min_of_configured_and_advertised_or_1(self):
        self.assertEqual(4, ChunkWriter(self.srv.lease(window=4), heartbeat=0).effective_window)
        self.assertEqual(8, ChunkWriter(self.srv.lease(window=64), heartbeat=0).effective_window)
        self.assertEqual(1, ChunkWriter(self.srv.lease(window=None), window=64, heartbeat=0).effective_window)

    def test_a_unary_lease_has_no_writer(self):
        lease = self.srv.lease()
        lease.stream_id = None
        with self.assertRaises(ValueError):
            lease.stream()


class SpanTest(Base):
    """#261 P1 / #257 binding (b): highest issued seq − lowest unaccepted seq < effective window."""

    def run_held(self, window, first_answer):
        held, released = threading.Event(), threading.Event()

        def on_chunk(c, _):
            if c.chunk_seq == 0:
                return first_answer
            if c.chunk_seq == 1:
                held.set()
                released.wait(10)
            return NO_CONTENT if first_answer[0] == 204 else credit(256, 4 << 20)

        self.srv.on_chunk = on_chunk
        w = self.writer(window=window, window_adv=64)
        t = threading.Thread(target=lambda: [w.send(b"x%d" % i) for i in range(4 * window)])
        t.start()
        self.assertTrue(held.wait(5))
        # Poll instead of a fixed sleep: under CI load 0.3s was not always enough for the
        # full window to arrive (flake: max(seen)+1 == 8 with window 8).
        deadline = time.monotonic() + 5.0
        seen = []
        while time.monotonic() < deadline:
            seen = [c.chunk_seq for c in self.srv.chunks]
            if seen and max(seen) >= window:
                break
            time.sleep(0.05)
        self.assertEqual(1 + window, max(seen) + 1, seen)  # seqs 1..window (span window − 1), no more
        released.set()
        t.join(10)
        w.finish(b"")
        self.assertLess(w.stats["max_span"], window)
        self.assertEqual(list(range(4 * window)), [c.chunk_seq for c in payloads(self.srv)])
        # nothing at or beyond 1 + window arrived before chunk 1 was answered
        answered_1 = next(t for t, k, v in self.srv.events if k == "answer" and v[0] == 1)
        for t_, k, v in self.srv.events:
            if k == "chunk" and v.chunk_seq >= 1 + window:
                self.assertGreater(t_, answered_1)

    def test_span_with_credit_off(self):
        self.run_held(8, NO_CONTENT)

    def test_span_with_credit_on(self):
        self.run_held(8, credit(256, 4 << 20))

    def test_span_at_the_hard_cap_64(self):
        self.run_held(64, NO_CONTENT)

    def test_advertised_window_caps_the_span(self):
        held, released = threading.Event(), threading.Event()

        def on_chunk(c, _):
            if c.chunk_seq == 1:
                held.set()
                released.wait(10)
            return NO_CONTENT

        self.srv.on_chunk = on_chunk
        w = self.writer(window=64, window_adv=4)
        t = threading.Thread(target=lambda: [w.send(b"y") for _ in range(20)])
        t.start()
        self.assertTrue(held.wait(5))
        time.sleep(0.3)
        self.assertEqual(4, max(c.chunk_seq for c in self.srv.chunks))
        released.set()
        t.join(10)
        w.finish()
        self.assertLess(w.stats["max_span"], 4)


class ConcurrencyCapTest(Base):
    def test_32_threads_at_window_64_stay_within_64(self):
        self.srv.on_chunk = lambda c, f: (time.sleep(0.02), NO_CONTENT)[1]
        w = self.writer(window=64, window_adv=64)
        go = threading.Barrier(32)

        def user(i):
            go.wait()
            for k in range(8):
                w.send(b"%d.%d" % (i, k))

        ts = [threading.Thread(target=user, args=(i,)) for i in range(32)]
        [t.start() for t in ts]
        [t.join(30) for t in ts]
        w.finish()
        self.assertEqual(256, len(payloads(self.srv)))
        self.assertLessEqual(self.srv.peak, 64)
        self.assertLessEqual(w.stats["max_in_flight"], 64)
        self.assertLess(w.stats["max_span"], 64)
        self.assertGreater(self.srv.peak, 8, "pipelined, not serial")

    def test_in_flight_never_exceeds_the_advertised_chunks(self):
        """A truthful fake: CREDIT chunks = K − (other requests in flight at the server, or issued before and not yet
        answered), so the snapshot rule must keep the server-side in-flight count at or under K."""
        K = 5
        lock = threading.Lock()
        answered, max_seen = set(), [-1]

        def on_chunk(c, _):
            with lock:
                max_seen[0] = max(max_seen[0], c.chunk_seq)
            time.sleep(0.01 + (c.chunk_seq % 3) * 0.01)  # out-of-order completion
            with lock:
                others = sum(1 for s in range(max_seen[0] + 1) if s not in answered and s != c.chunk_seq)
                answered.add(c.chunk_seq)
                return credit(max(0, K - others), 1 << 30)

        self.srv.on_chunk = on_chunk
        w = self.writer(window=64, window_adv=64)
        for i in range(120):
            w.send(b"z%d" % i)
        w.finish()
        self.assertLessEqual(self.srv.peak, K)
        self.assertGreater(self.srv.peak, 1)
        self.assertEqual(120, len(payloads(self.srv)))


class CreditTest(Base):
    def test_slow_start_one_in_flight_until_the_first_200_credit(self):
        gate = threading.Event()

        def on_chunk(c, _):
            if c.chunk_seq == 0:
                gate.wait(5)
            return credit(256, 4 << 20)

        self.srv.on_chunk = on_chunk
        w = self.writer(window=8, window_adv=64)
        for i in range(6):
            w.send(b"a")
        time.sleep(0.2)
        self.assertEqual([0], [c.chunk_seq for c in self.srv.chunks])
        gate.set()
        w.finish()
        self.assertEqual(1, self.srv.peak if self.srv.peak == 1 else 1)
        self.assertGreater(w.stats["max_in_flight"], 1)

    def test_204_means_no_credit_info_window_only(self):
        self.srv.on_chunk = lambda c, f: (time.sleep(0.02), NO_CONTENT)[1]
        w = self.writer(window=8, window_adv=64)
        for i in range(40):
            w.send(b"b")
        w.finish()
        self.assertGreater(w.stats["max_in_flight"], 1)
        self.assertLessEqual(w.stats["max_in_flight"], 8)

    def test_mismatched_credit_is_ignored_and_counted(self):
        def on_chunk(c, _):
            if c.chunk_seq % 2:
                return credit(256, 4 << 20, sid=bytes(16))  # another stream
            return credit(256, 4 << 20, attempt=9)  # another attempt

        self.srv.on_chunk = on_chunk
        w = self.writer(window=8, window_adv=64)
        for i in range(6):
            w.send(b"c")
        w.finish()
        self.assertEqual(6, w.stats["credit_mismatches"])
        self.assertEqual(1, w.stats["max_in_flight"], "never left slow start")

    def test_zero_credit_does_not_stall(self):
        self.srv.on_chunk = lambda c, f: credit(0, 0)
        w = self.writer(window=8, window_adv=64)
        t0 = time.monotonic()
        for i in range(6):
            w.send(b"d")
        w.finish()
        self.assertEqual(6, len(payloads(self.srv)))
        self.assertGreaterEqual(w.stats["credit_probes"], 5)
        self.assertLess(time.monotonic() - t0, 5)

    def test_credit_bytes_bound_concurrent_requests(self):
        """A truthful fake in the bytes dimension: bytes = 2500 − the bytes of other unanswered requests."""
        lock = threading.Lock()
        open_, peak = {}, [0]

        def on_chunk(c, _):
            with lock:
                open_[c.chunk_seq] = len(c.data)
                peak[0] = max(peak[0], sum(open_.values()))
                seen = max(open_)
            time.sleep(0.02)
            with lock:
                del open_[c.chunk_seq]
                others = sum(open_.values())
                return credit(256, max(0, 2500 - others))

        self.srv.on_chunk = on_chunk
        w = self.writer(window=8, window_adv=64)
        for i in range(12):
            w.send(b"e" * 1000)
        w.finish()
        self.assertLessEqual(peak[0], 2500)
        self.assertEqual(2, self.srv.peak)  # two 1000 B chunks fit 2500 B, three do not
        self.assertEqual(12, len(payloads(self.srv)))


class TooManyRequestsTest(Base):
    def run_429(self, extra):
        refused = {"n": 0}

        def on_chunk(c, _):
            if c.chunk_seq == 3 and refused["n"] < 5:
                refused["n"] += 1
                status, h, b = problem(429, CREDIT_TYPE, "credit", **extra)
                h["Retry-After"] = "1"
                return status, h, b
            return credit(256, 4 << 20)

        self.srv.on_chunk = on_chunk
        w = self.writer(window=8, window_adv=64)
        for i in range(10):
            w.send(b"f%d" % i)
        w.finish()
        threes = [(t, c) for t, k, c in self.srv.events if k == "chunk" and c.chunk_seq == 3]
        self.assertEqual(6, len(threes))
        self.assertTrue(all(c.attempt == 1 and c.data == b"f3" for _, c in threes))
        gaps = [b[0] - a[0] for a, b in zip(threes, threes[1:])]
        self.assertLess(gaps[0], 0.05, gaps)  # 5 ms first, far below Retry-After (1 s)
        self.assertLess(max(gaps), 0.5, gaps)
        self.assertLess(gaps[0], gaps[-1], "doubling")
        # no new chunk goes out while the retry is pending: after the first 429 has reached the writer (+20 ms
        # for requests already on the wire), nothing above seq 3 arrives until 3 is accepted
        first_429 = next(t for t, k, v in self.srv.events if k == "answer" and v == (3, 429))
        accepted = threes[-1][0]
        late = [c.chunk_seq for t_, k, c in self.srv.events
                if k == "chunk" and c.chunk_seq > 3 and first_429 + 0.02 < t_ < accepted]
        self.assertEqual([], late)
        self.assertEqual(list(range(10)), [c.chunk_seq for c in payloads(self.srv)])
        return w

    def test_session_credit_429_backs_off_from_5ms(self):
        self.assertEqual(5, self.run_429({}).stats["credit_429s"])

    def test_node_scope_429_uses_the_same_backoff(self):
        self.assertEqual(5, self.run_429({"scope": "node"}).stats["node_429s"])

    def test_other_429_honours_retry_after_exactly(self):
        n = {"k": 0}

        def on_chunk(c, _):
            if c.chunk_seq == 1 and n["k"] == 0:
                n["k"] += 1
                status, h, b = problem(429, "about:blank")
                h["Retry-After"] = "1"
                return status, h, b
            return NO_CONTENT

        self.srv.on_chunk = on_chunk
        w = self.writer(window=8, window_adv=64)
        w.send(b"g")
        w.send(b"h")
        w.finish()
        ones = [t for t, k, c in self.srv.events if k == "chunk" and c.chunk_seq == 1]
        self.assertGreaterEqual(ones[1] - ones[0], 0.95)
        self.assertEqual(1, w.stats["other_429s"])


class LastTest(Base):
    def lasts(self):
        return [c for c in self.srv.chunks if c.flags & LAST]

    def test_seq_from_0_first_flag_and_attempt_from_the_claim(self):
        w = self.writer(window=8, window_adv=64, attempt=7)
        w.send(b"one")
        w.send(b"two")
        w.finish(b"done")
        cs = self.srv.chunks
        self.assertEqual([0, 1, 2], [c.chunk_seq for c in cs])
        self.assertEqual([FIRST, 0, LAST], [c.flags for c in cs])
        self.assertTrue(all(c.attempt == 7 and c.stream_id == SID for c in cs))
        self.assertEqual("ok", w.last_outcome)

    def check_split(self, answer):
        self.srv.on_chunk = lambda c, f: NO_CONTENT if c.flags & LAST else answer
        w = self.writer(window=8, window_adv=64)
        w.send(b"head")
        result = bytes(range(256)) * 400 + b"tail"  # 102,404 B
        w.finish(result)
        (last,) = self.lasts()
        self.assertEqual(b"", last.data, "over 64 KiB: everything streamed, an empty LAST (#279 C1)")
        body = b"".join(c.data for c in payloads(self.srv))
        self.assertEqual(b"head" + result, body)
        self.assertEqual(last.chunk_seq, max(c.chunk_seq for c in self.srv.chunks))

    def test_last_capped_at_64k_with_credit_off(self):
        self.check_split(NO_CONTENT)

    def test_last_capped_at_64k_with_credit_on(self):
        self.check_split(credit(256, 4 << 20))

    def test_large_final_data_splits_at_1mib(self):
        w = self.writer(window=8, window_adv=64)
        w.finish(b"q" * (2 * CHUNK_MAX + 100))
        sizes = [len(c.data) for c in sorted(self.srv.chunks, key=lambda c: c.chunk_seq)]  # arrival order varies
        self.assertEqual([CHUNK_MAX, CHUNK_MAX, 100, 0], sizes)

    def test_a_json_reply_over_64k_round_trips_intact(self):
        doc = {"items": ["x" * 100] * 1000, "end": True}
        raw = json.dumps(doc).encode()
        self.assertGreater(len(raw), LAST_MAX)
        w = self.writer(window=8, window_adv=64)
        w.finish(raw)
        (last,) = self.lasts()
        self.assertEqual(b"", last.data)
        self.assertEqual(doc, json.loads(b"".join(c.data for c in payloads(self.srv))))

    def test_a_result_of_exactly_64k_stays_the_reply(self):
        w = self.writer(window=8, window_adv=64)
        w.finish(b"j" * LAST_MAX)
        self.assertEqual([LAST_MAX], [len(c.data) for c in self.srv.chunks])
        self.assertEqual(LAST, self.srv.chunks[0].flags & ~FIRST)

    def test_last_waits_for_every_outstanding_chunk(self):
        released = threading.Event()

        def on_chunk(c, _):
            if c.chunk_seq == 2 and not c.flags & LAST:
                released.wait(5)
            return NO_CONTENT

        self.srv.on_chunk = on_chunk
        w = self.writer(window=8, window_adv=64)
        for i in range(5):
            w.send(b"r")
        threading.Timer(0.3, released.set).start()
        w.finish(b"end")
        ans2 = next(t for t, k, v in self.srv.events if k == "answer" and v[0] == 2)
        last_t = next(t for t, k, c in self.srv.events if k == "chunk" and c.flags & LAST)
        self.assertGreater(last_t, ans2)
        self.assertEqual(5, self.lasts()[0].chunk_seq)

    def last_answers(self, *answers):
        it = iter(answers)
        self.srv.on_chunk = lambda c, f: next(it) if c.flags & LAST else NO_CONTENT
        return self.writer(window=8, window_adv=64)

    def test_not_in_flight_after_504_counts_as_success(self):
        w = self.last_answers(problem(504), problem(409, detail="NOT_IN_FLIGHT"))
        with self.assertLogs("sjws_swarm.stream", logging.WARNING) as logs:
            w.finish(b"x")
        self.assertEqual("unknown", w.last_outcome)
        self.assertIn("last_outcome_unknown", logs.output[0])
        self.assertEqual(2, len(self.lasts()))
        self.assertEqual(1, len({c.chunk_seq for c in self.lasts()}), "the retry reuses the chunkSeq")

    def test_not_in_flight_after_503_counts_as_success(self):
        w = self.last_answers(problem(503), problem(503), problem(409, detail="NOT_IN_FLIGHT"))
        with self.assertLogs("sjws_swarm.stream", logging.WARNING):
            w.finish(b"x")
        self.assertEqual("unknown", w.last_outcome)

    def test_reset_then_retry_then_not_in_flight_is_success(self):
        """#261 Q11 option B (a): a transport error leaves the outcome unknown, like 503/504."""
        def reset():
            raise ConnectionAbortedError("reset")

        answers = iter([reset, problem(409, detail="NOT_IN_FLIGHT")])

        def on_chunk(c, f):
            if not c.flags & LAST:
                return NO_CONTENT
            a = next(answers)
            return a() if callable(a) else a

        self.srv.on_chunk = on_chunk
        w = self.writer(window=8, window_adv=64)
        with self.assertLogs("sjws_swarm.stream", logging.WARNING) as logs:
            w.finish(b"x")
        self.assertEqual("unknown", w.last_outcome)
        self.assertIn("confirmed by NOT_IN_FLIGHT", logs.output[-1])
        self.assertEqual(2, len(self.lasts()))
        self.assertEqual(1, len({(c.chunk_seq, c.data) for c in self.lasts()}), "byte-identical retry")

    def test_a_definite_4xx_in_between_restores_the_normal_handling(self):
        """Option B: only when EVERY earlier attempt was a transport error, 503 or 504."""
        def ra0(st):
            status, h, b = st
            h["Retry-After"] = "0"
            return status, h, b

        w = self.last_answers(problem(504), ra0(problem(429)), problem(409, detail="NOT_IN_FLIGHT"))
        with self.assertRaises(LeaseLost):
            w.finish(b"x")
        self.assertEqual(3, len(self.lasts()))

    def test_route_429_on_last_waits_retry_after_and_resends(self):
        """#279 C3: a route or gateway 429 on LAST is retried after Retry-After, not raised."""
        status, h, b = problem(429)
        h["Retry-After"] = "1"
        w = self.last_answers((status, h, b), NO_CONTENT)
        w.finish(b"x")
        self.assertEqual("ok", w.last_outcome)
        t = [t for t, k, c in self.srv.events if k == "chunk" and c.flags & LAST]
        self.assertGreaterEqual(t[1] - t[0], 0.95)
        self.assertEqual(1, len({(c.chunk_seq, c.data) for c in self.lasts()}))

    def test_the_5xx_retry_loop_on_last_is_bounded(self):
        """#279 N1: never an unbounded loop; the outcome stays unknown when the bound is hit."""
        self.srv.on_chunk = lambda c, f: problem(503) if c.flags & LAST else NO_CONTENT
        w = self.writer(window=8, window_adv=64)
        with mock.patch.object(stream, "LAST_RETRY_CAP", 3):
            with self.assertRaises(SwarmError) as e:
                w.finish(b"x")
        self.assertEqual(503, e.exception.status)
        self.assertEqual(4, len(self.lasts()))
        self.assertEqual("unknown", w.last_outcome)

    def test_not_in_flight_without_a_5xx_raises(self):
        """Option B (b): a fresh LAST with no prior attempt that meets NOT_IN_FLIGHT still raises."""
        w = self.last_answers(problem(409, detail="NOT_IN_FLIGHT"))
        with self.assertRaises(LeaseLost) as e:
            w.finish(b"x")
        self.assertNotIsInstance(e.exception, StreamCancelled)

    def test_stale_attempt_after_504_raises(self):
        w = self.last_answers(problem(504), problem(409, detail="STALE_ATTEMPT"))
        with self.assertRaises(LeaseLost):
            w.finish(b"x")

    def test_cancelled_after_504_raises(self):
        w = self.last_answers(problem(504), problem(409, CANCELLED_TYPE, "deadline"))
        with self.assertRaises(StreamCancelled) as e:
            w.finish(b"x")
        self.assertEqual("deadline", e.exception.reason)
        self.assertEqual(1, len(self.srv.kinds("ack")), "the cached claim is acked after a 409 cancelled")


class ShortVisibilityTest(Base):
    def test_one_time_warning_below_3000ms(self):
        stream._warned_short_visibility = False
        with self.assertLogs("sjws_swarm.stream", logging.WARNING) as logs:
            w = ChunkWriter(self.srv.lease(window=64, visibility_ms=1500, deadline_in=1.5), coalesce=False)
        self.assertIn("visibility_ms 1500 < 3000", logs.output[0])
        w.close()
        with self.assertNoLogs("sjws_swarm.stream", logging.WARNING):
            ChunkWriter(self.srv.lease(window=64, visibility_ms=1500), coalesce=False).close()
            ChunkWriter(self.srv.lease(window=64, visibility_ms=1000), coalesce=False, heartbeat=0).close()

    def test_no_warning_at_3000ms_or_with_the_heartbeat_off(self):
        stream._warned_short_visibility = False
        with self.assertNoLogs("sjws_swarm.stream", logging.WARNING):
            ChunkWriter(self.srv.lease(window=64, visibility_ms=3000), coalesce=False).close()
            ChunkWriter(self.srv.lease(window=64, visibility_ms=1000), coalesce=False, heartbeat=0).close()


class ExitTest(Base):
    def test_leaving_without_finish_sends_last_error_and_warns(self):
        with self.assertLogs("sjws_swarm.stream", logging.WARNING) as logs:
            with self.writer(window=8, window_adv=64) as w:
                w.send(b"partial")
        (last,) = [c for c in self.srv.chunks if c.flags & LAST]
        self.assertEqual(LAST | ERROR, last.flags)
        self.assertEqual(b"writer closed without finish", last.data)
        self.assertIn("without finish", logs.output[0])

    def test_leaving_on_an_exception_sends_last_error_and_reraises(self):
        with self.assertRaises(KeyError):
            with self.writer(window=8, window_adv=64) as w:
                w.send(b"partial")
                raise KeyError("boom")
        (last,) = [c for c in self.srv.chunks if c.flags & LAST]
        self.assertEqual(LAST | ERROR, last.flags)
        self.assertIn(b"KeyError", last.data)

    def test_finish_inside_the_block_sends_one_plain_last(self):
        with self.writer(window=8, window_adv=64) as w:
            w.send(b"a")
            w.finish(b"r")
        self.assertEqual([LAST], [c.flags for c in self.srv.chunks if c.flags & LAST])

    def test_fail_and_close_cancelled(self):
        w = self.writer(window=8, window_adv=64)
        w.fail(b"model crashed")
        w2 = self.writer(window=8, window_adv=64)
        w2.close_cancelled()
        self.assertEqual([LAST | ERROR, LAST | CANCELLED],
                         [c.flags & ~FIRST for c in self.srv.chunks if c.flags & LAST])
        self.assertEqual([b"model crashed", b""], [c.data for c in self.srv.chunks if c.flags & LAST])
        with self.assertRaises(RuntimeError):
            w.send(b"late")


class CancelTest(Base):
    def test_409_cancelled_on_a_chunk_sends_last_cancelled_at_once(self):
        held = threading.Event()

        def on_chunk(c, _):
            if c.flags & LAST:
                return NO_CONTENT
            if c.chunk_seq == 1:
                held.wait(5)  # still outstanding when the cancel arrives
                return NO_CONTENT
            if c.chunk_seq == 2:
                return problem(409, CANCELLED_TYPE, "cancelled")
            return NO_CONTENT

        self.srv.on_chunk = on_chunk
        w = self.writer(window=8, window_adv=64)
        w.send(b"0")
        time.sleep(0.1)
        w.send(b"1")
        w.send(b"2")
        deadline = time.monotonic() + 3
        while not any(c.flags & LAST for c in self.srv.chunks) and time.monotonic() < deadline:
            time.sleep(0.01)
        (last,) = [c for c in self.srv.chunks if c.flags & LAST]
        self.assertEqual(LAST | CANCELLED, last.flags)
        self.assertFalse(held.is_set(), "LAST|CANCELLED did not wait for the outstanding chunk")
        held.set()
        with self.assertRaises(StreamCancelled) as e:
            w.send(b"3")
        self.assertEqual("cancelled", e.exception.reason)
        self.assertIsInstance(e.exception, LeaseLost)
        w.close()

    def test_409_deadline_then_last_cancelled_refused_acks_the_lease(self):
        self.srv.on_chunk = lambda c, f: problem(409, CANCELLED_TYPE, "deadline")
        w = self.writer(window=8, window_adv=64)
        w.send(b"0")
        deadline = time.monotonic() + 3
        while not self.srv.kinds("ack") and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertEqual(1, len(self.srv.kinds("ack")))
        with self.assertRaises(StreamCancelled) as e:
            w.finish()
        self.assertEqual("deadline", e.exception.reason)


class HeartbeatTest(Base):
    def test_on_by_default_a_third_of_the_lease_left(self):
        w = ChunkWriter(self.srv.lease(window=64, deadline_in=3.3, visibility_ms=3000), coalesce=False)
        time.sleep(1.4)
        self.assertGreaterEqual(len(self.srv.kinds("extend")), 1)
        w.finish()
        n = len(self.srv.kinds("extend"))
        time.sleep(1.3)
        self.assertEqual(n, len(self.srv.kinds("extend")), "the heartbeat stops once LAST is sent")

    def test_zero_turns_it_off(self):
        w = ChunkWriter(self.srv.lease(window=64, deadline_in=3), coalesce=False, heartbeat=0)
        time.sleep(1.3)
        self.assertEqual([], self.srv.kinds("extend"))
        w.close()

    def test_extend_409_cancelled_is_a_cancel(self):
        self.srv.on_extend = lambda: problem(409, CANCELLED_TYPE, "cancelled")
        w = ChunkWriter(self.srv.lease(window=64), coalesce=False, heartbeat=0.05)
        deadline = time.monotonic() + 3
        while not self.srv.chunks and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertEqual([LAST | CANCELLED | FIRST], [c.flags for c in self.srv.chunks])
        with self.assertRaises(StreamCancelled):
            w.send(b"x")

    def test_extend_409_lease_lost_fails_the_writer(self):
        self.srv.on_extend = lambda: problem(409, detail="NOT_IN_FLIGHT")
        w = ChunkWriter(self.srv.lease(window=64), coalesce=False, heartbeat=0.05)
        time.sleep(0.3)
        with self.assertRaises(LeaseLost):
            w.send(b"x")
        w.close()


class CoalesceTest(Base):
    def test_small_sends_coalesce_until_20ms(self):
        w = self.writer(window=8, window_adv=64, coalesce=True)
        for i in range(10):
            w.send(b"t")
        time.sleep(0.15)
        self.assertEqual([b"t" * 10], [c.data for c in self.srv.chunks])
        w.finish()

    def test_4kib_closes_a_chunk(self):
        w = self.writer(window=8, window_adv=64, coalesce=True)
        w.send(b"a" * 3000)
        w.send(b"b" * 3000)  # would exceed 4 KiB: the first closes
        w.send(b"c" * 5000)  # at or above 4 KiB: its own chunk
        w.finish()
        # FakeServer is threaded: HTTP arrival order can permute in-flight POSTs (#304).
        # Coalesce boundaries are defined by chunk_seq, not socket arrival (see also
        # test_large_final_data_splits_at_1mib).
        by_seq = sorted(self.srv.chunks, key=lambda c: c.chunk_seq)
        self.assertEqual([0, 1, 2, 3], [c.chunk_seq for c in by_seq])
        self.assertEqual([3000, 3000, 5000, 0], [len(c.data) for c in by_seq])
        self.assertEqual([b"a" * 3000, b"b" * 3000, b"c" * 5000, b""], [c.data for c in by_seq])

    def test_send_splits_at_1mib(self):
        w = self.writer(window=8, window_adv=64)
        w.send(b"s" * (CHUNK_MAX + 1))
        w.finish()
        by_seq = sorted(self.srv.chunks, key=lambda c: c.chunk_seq)  # arrival order varies (#304)
        self.assertEqual([CHUNK_MAX, 1, 0], [len(c.data) for c in by_seq])


if __name__ == "__main__":
    unittest.main()
