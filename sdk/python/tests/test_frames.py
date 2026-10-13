"""Conformance: the Python codec against the shared golden frames. Run: python3 -m unittest discover sdk/python/tests"""
import pathlib
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "sdk" / "python"))

from sjws_swarm import Frame, FrameType, LAST, decode  # noqa: E402

GOLDEN = ROOT / "tests" / "sjws_nettask" / "swarm" / "wire" / "golden-v1.txt"


class FramesTest(unittest.TestCase):
    def test_golden(self):
        n = 0
        for line in GOLDEN.read_text().splitlines():
            if not line.strip() or line.startswith("#"):
                continue
            name, ftype, flags, payload, frame = line.split(" ")
            f = Frame(FrameType[ftype], int(flags), b"" if payload == "-" else bytes.fromhex(payload))
            self.assertEqual(frame, f.encode().hex(), name)
            got, used = decode(bytes.fromhex(frame))
            self.assertEqual(f, got, name)
            self.assertEqual(len(frame) // 2, used)
            n += 1
        self.assertGreaterEqual(n, 11)

    def test_golden_envelopes_are_standard_utf8(self):
        want = {"task-env-nul": "reply.nul\0x", "task-env-emoji": "reply.\U0001F600"}
        seen = 0
        for line in GOLDEN.read_text().splitlines():
            name, _, _, payload, _ = (line.split(" ") + [""] * 5)[:5]
            if name not in want:
                continue
            p = bytes.fromhex(payload)
            n = int.from_bytes(p[41:43], "big")
            reply_to = p[43:43 + n]
            self.assertEqual(want[name].encode("utf-8"), reply_to, name)
            self.assertEqual(want[name], reply_to.decode("utf-8", errors="strict"), name)
            self.assertNotIn(b"\xc0\x80", reply_to, name)   # modified-UTF-8 NUL
            self.assertNotIn(b"\xed", reply_to, name)        # CESU-8 surrogate half
            seen += 1
        self.assertEqual(2, seen)

    def test_partial_and_errors(self):
        whole = Frame(FrameType.TOKEN, LAST, b"abc").encode()
        self.assertIsNone(decode(whole[:5]))
        self.assertIsNone(decode(whole[:7]))
        with self.assertRaises(ValueError):
            decode(bytes([2, 1, 0, 0, 0, 0]))
        with self.assertRaises(ValueError):
            decode(bytes([1, 99, 0, 0, 0, 0]))
        with self.assertRaises(ValueError):
            Frame(FrameType.TASK, 256)


if __name__ == "__main__":
    unittest.main()
