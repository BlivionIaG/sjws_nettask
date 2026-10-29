"""Stream (caller side) against the stdlib fake: binary and SSE events, terminals, resume, busy/gone, submit and
cancel (#261 Q3–Q5, Q11). Run: python3 -m unittest discover sdk/python/tests"""
import json
import threading
import time
import unittest

from stream_fake import SID, FakeServer, chunk_frame, problem, write_chunked

from sjws_swarm.chunks import RESET
from sjws_swarm.client import DeadlineExceeded, SessionGone, StreamError, StreamGap, TooManyStreams
from sjws_swarm.frames import CANCELLED, ERROR, LAST, Frame, FrameType
from sjws_swarm.stream import Chunk, Reset

SESSION = bytes(range(16))


def err_frame(text):
    return Frame(FrameType.ERROR, LAST, text.encode()).encode()


class Base(unittest.TestCase):
    def setUp(self):
        self.srv = FakeServer()
        self.c = self.srv.client()

    def tearDown(self):
        self.srv.close()

    def serve(self, *answers):
        """Each GET takes the next answer: a list of parts (written chunked), or (parts, close_early), or a status."""
        it = iter(answers)

        def on_get(h):
            a = next(it)
            if isinstance(a, int):
                status, headers, body = problem(a, detail="x")
                h._answer(status, headers, body)
                return
            parts, early = a if isinstance(a, tuple) else (a, False)
            ct = "text/event-stream" if parts and isinstance(parts[0], bytes) and parts[0].startswith(b"retry") \
                else "application/vnd.sjws.swarm.v1"
            write_chunked(h, parts, ct, close_early=early)

        self.srv.on_get = on_get

    def read(self, **kw):
        s = self.c.open_stream(SESSION, **kw)
        return s, list(s)


class BinaryTest(Base):
    def test_chunks_reset_and_last_with_result(self):
        self.serve([chunk_frame(0, b"a"), chunk_frame(1, b"b"), chunk_frame(0, b"", RESET, attempt=2),
                    chunk_frame(0, b"A", attempt=2), chunk_frame(1, b"final", LAST, attempt=2)])
        s, evs = self.read()
        self.assertEqual([Chunk(1, 0, b"a"), Chunk(1, 1, b"b"), Reset(2), Chunk(2, 0, b"A"), Chunk(2, 1, b"final")], evs)
        self.assertEqual(("ok", b"final"), (s.state, s.result))
        path, _, accept = self.srv.kinds("get")[0]
        self.assertEqual("/swarm/v1/replies/" + SESSION.hex() + "?from=0", path)
        self.assertEqual("application/vnd.sjws.swarm.v1", accept)

    def test_empty_last_and_cancelled(self):
        self.serve([chunk_frame(0, b"a"), chunk_frame(1, b"", LAST)])
        s, evs = self.read()
        self.assertEqual(([Chunk(1, 0, b"a")], "ok", b""), (evs, s.state, s.result))
        self.serve([chunk_frame(0, b"a"), chunk_frame(1, b"", LAST | CANCELLED)])
        s, evs = self.read()
        self.assertEqual(([Chunk(1, 0, b"a")], "cancelled"), (evs, s.state))

    def test_error_deadline_and_gap_raise(self):
        self.serve([chunk_frame(0, b"model crashed", LAST | ERROR)])
        with self.assertRaises(StreamError) as e:
            self.read()
        self.assertEqual(b"model crashed", e.exception.message)
        self.serve([chunk_frame(0, b"a"), err_frame("deadline")])
        with self.assertRaises(DeadlineExceeded):
            self.read()
        self.serve([chunk_frame(0, b"a"), err_frame("gap 1 ? overflow")])
        s = self.c.open_stream(SESSION)
        self.assertEqual(Chunk(1, 0, b"a"), next(s))
        with self.assertRaises(StreamGap) as e:
            next(s)
        self.assertEqual((1, None, "overflow"), (e.exception.from_seq, e.exception.to_seq, e.exception.reason))
        self.serve([err_frame("gap 3 7 dropped")])
        with self.assertRaises(StreamGap) as e:
            self.read()
        self.assertEqual((3, 7, "dropped"), (e.exception.from_seq, e.exception.to_seq, e.exception.reason))

    def test_resume_after_a_drop_from_the_last_delivered_chunk(self):
        self.serve(([chunk_frame(0, b"a"), chunk_frame(1, b"b")], True),
                   [chunk_frame(2, b"c"), chunk_frame(3, b"", LAST)])
        s, evs = self.read()
        self.assertEqual([b"a", b"b", b"c"], [e.data for e in evs])
        self.assertEqual("ok", s.state)
        self.assertEqual(1, s.reconnects)
        self.assertEqual("/swarm/v1/replies/" + SESSION.hex() + "?from=2&attempt=1", self.srv.kinds("get")[1][0])

    def test_resume_after_a_reset_asks_for_seq_0_of_the_new_attempt(self):
        self.serve(([chunk_frame(0, b"a"), chunk_frame(0, b"", RESET, attempt=3)], True),
                   [chunk_frame(0, b"x", attempt=3), chunk_frame(1, b"", LAST, attempt=3)])
        s, evs = self.read()
        self.assertEqual([Chunk(1, 0, b"a"), Reset(3), Chunk(3, 0, b"x")], evs)
        self.assertEqual("/swarm/v1/replies/" + SESSION.hex() + "?from=0&attempt=3", self.srv.kinds("get")[1][0])

    def test_busy_is_retried_and_gone_raises(self):
        self.serve(409, [chunk_frame(0, b"", LAST)])
        s, evs = self.read()
        self.assertEqual(("ok", 1), (s.state, s.reconnects))
        self.serve(404)
        with self.assertRaises(SessionGone):
            self.read()

    def test_reconnect_off_raises_on_a_drop_and_retries_are_capped(self):
        self.serve(([chunk_frame(0, b"a")], True))
        s = self.c.open_stream(SESSION, reconnect=False)
        self.assertEqual(b"a", next(s).data)
        with self.assertRaises(Exception):
            next(s)
        self.serve(*([([chunk_frame(0, b"a")], True)] + [([], True)] * 6))
        with self.assertRaises(Exception):
            self.read()
        self.assertEqual(6, len(self.srv.kinds("get")) - 1)  # 1 + 5 retries (after the first test GET)

    def test_reconnect_is_bounded_by_the_deadline(self):
        self.serve(([], True), ([], True), ([], True))
        s = self.c.open_stream(SESSION, deadline=0.25)
        with self.assertRaises(DeadlineExceeded):
            list(s)

    def test_open_stream_resumes_a_known_position(self):
        self.serve([chunk_frame(5, b"f", attempt=2), chunk_frame(6, b"", LAST, attempt=2)])
        s, evs = self.read(attempt=2, from_seq=5)
        self.assertEqual([Chunk(2, 5, b"f")], evs)
        self.assertEqual("/swarm/v1/replies/" + SESSION.hex() + "?from=5&attempt=2", self.srv.kinds("get")[0][0])


class SseTest(Base):
    def test_events_multiline_reset_and_end(self):
        self.serve([b"retry: 1000\n\n", b"id: 1.0\nevent: chunk\ndata: hel\ndata: lo\n\n", b":\n\n",
                    b"event: reset\ndata: 2\n\n", b"id: 2.0\nevent: chunk\ndata: x\n\n",
                    b"id: 2.1\nevent: chunk\ndata: done\n\nid: 2.1\nevent: end\ndata: ok\n\n"])
        s, evs = self.read(sse=True)
        self.assertEqual([Chunk(1, 0, b"hel\nlo"), Reset(2), Chunk(2, 0, b"x"), Chunk(2, 1, b"done")], evs)
        self.assertEqual("ok", s.state)
        self.assertEqual("text/event-stream", self.srv.kinds("get")[0][2])

    def test_gap_cancelled_deadline_error(self):
        self.serve([b"retry: 1000\n\n", b"event: gap\ndata: 4 ? overflow\n\nevent: end\ndata: error\n\n"])
        with self.assertRaises(StreamGap) as e:
            self.read(sse=True)
        self.assertEqual((4, None, "overflow"), (e.exception.from_seq, e.exception.to_seq, e.exception.reason))
        self.serve([b"retry: 1000\n\n", b"id: 1.0\nevent: end\ndata: cancelled\n\n"])
        s, evs = self.read(sse=True)
        self.assertEqual("cancelled", s.state)
        self.serve([b"retry: 1000\n\n", b"event: end\ndata: deadline\n\n"])
        with self.assertRaises(DeadlineExceeded):
            self.read(sse=True)
        self.serve([b"retry: 1000\n\n", b"id: 1.0\nevent: end\ndata: error\n\n"])
        with self.assertRaises(StreamError):
            self.read(sse=True)

    def test_error_text_fills_stream_error_message(self):
        """#279 C2 fallback: older servers send LAST|ERROR text as a chunk with the end event's id."""
        self.serve([b"retry: 1000\n\n", b"id: 1.0\nevent: chunk\ndata: tok\n\n",
                    b"id: 1.1\nevent: chunk\ndata: model crashed\n\nid: 1.1\nevent: end\ndata: error\n\n"])
        s = self.c.open_stream(SESSION, sse=True)
        got = []
        with self.assertRaises(StreamError) as e:
            for ev in s:
                got.append(ev.data)
        self.assertEqual(b"model crashed", e.exception.message)
        self.assertEqual([b"tok", b"model crashed"], got)  # old wire still yields the chunk

    def test_failure_event_fills_stream_error_message(self):
        """#282: event: failure carries LAST|ERROR text; it is not yielded as a Chunk."""
        self.serve([b"retry: 1000\n\n", b"id: 1.0\nevent: chunk\ndata: tok\n\n",
                    b"id: 1.1\nevent: failure\ndata: model crashed\n\nid: 1.1\nevent: end\ndata: error\n\n"])
        s = self.c.open_stream(SESSION, sse=True)
        got = []
        with self.assertRaises(StreamError) as e:
            for ev in s:
                got.append(ev.data)
        self.assertEqual(b"model crashed", e.exception.message)
        self.assertEqual([b"tok"], got)

    def test_failure_event_prefers_over_chunk_fallback(self):
        """#282: when both failure and a same-id chunk appear, failure wins (should not happen on one server)."""
        self.serve([b"retry: 1000\n\n",
                    b"id: 1.1\nevent: chunk\ndata: old\n\n"
                    b"id: 1.1\nevent: failure\ndata: new\n\nid: 1.1\nevent: end\ndata: error\n\n"])
        s = self.c.open_stream(SESSION, sse=True)
        got = []
        with self.assertRaises(StreamError) as e:
            for ev in s:
                got.append(ev.data)
        self.assertEqual(b"new", e.exception.message)
        self.assertEqual([b"old"], got)

    def test_resume_uses_last_event_id(self):
        self.serve(([b"retry: 1000\n\n", b"id: 1.0\nevent: chunk\ndata: a\n\n"], True),
                   [b"retry: 1000\n\n", b"id: 1.1\nevent: chunk\ndata: b\n\nid: 1.1\nevent: end\ndata: ok\n\n"])
        s, evs = self.read(sse=True)
        self.assertEqual([b"a", b"b"], [e.data for e in evs])
        self.assertEqual("1.0", self.srv.kinds("get")[1][1])


class SubmitAndCancelTest(Base):
    def test_stream_submits_and_reads(self):
        def on_post(path, body):
            if path.startswith("/swarm/v1/tasks/chat.v1:stream"):
                return 201, {"Content-Type": "application/vnd.sjws.swarm.v1",
                             "Location": "/swarm/v1/replies/" + SESSION.hex()}, Frame(FrameType.TASK, 0, SESSION).encode()
            if path == "/swarm/v1/tasks/" + SESSION.hex() + ":cancel":
                return 200, {"Content-Type": "application/json"}, json.dumps({"state": "cancelled"}).encode()
            return 404, {}, b""

        self.srv.on_post = on_post
        self.serve([chunk_frame(0, b"", LAST)])
        with self.c.stream("chat.v1", b"prompt", deadline=5) as s:
            self.assertEqual(SESSION, s.session_id)
            self.assertEqual("/swarm/v1/replies/" + SESSION.hex(), s.location)
            self.assertEqual("cancelled", s.cancel())
            self.assertEqual([], list(s))
        self.assertIn("deadlineMs=5000", self.srv.kinds("post")[0])

    def test_503_is_too_many_streams(self):
        self.srv.on_post = lambda path, body: problem(503, detail="node session limit")
        with self.assertRaises(TooManyStreams):
            self.c.stream("chat.v1", b"p")


if __name__ == "__main__":
    unittest.main()
