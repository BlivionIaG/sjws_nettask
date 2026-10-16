"""Conformance: the Python chunk codec against the shared golden chunks. Run: python3 -m unittest discover sdk/python/tests"""
import pathlib
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "sdk" / "python"))

from sjws_swarm.chunks import FIRST, StreamChunk, decode_chunk  # noqa: E402
from sjws_swarm.frames import CANCELLED, LAST, Frame, FrameType, decode  # noqa: E402

GOLDEN = ROOT / "tests" / "sjws_nettask" / "swarm" / "wire" / "golden-chunks-v1.txt"


class ChunksTest(unittest.TestCase):
    def test_golden(self):
        n = 0
        for line in GOLDEN.read_text().splitlines():
            if not line.strip() or line.startswith("#"):
                continue
            name, flags, chunk, frame = line.split(" ")
            c = decode_chunk(bytes.fromhex(chunk))
            self.assertEqual(int(flags), c.flags, name)
            self.assertEqual(chunk, c.encode().hex(), name)
            self.assertEqual(frame, Frame(FrameType.TOKEN, c.flags & 7, c.encode()).encode().hex(), name)
            got, _ = decode(bytes.fromhex(frame))
            self.assertEqual(c, decode_chunk(got.payload), name)
            n += 1
        self.assertEqual(4, n)

    def test_errors(self):
        sid = bytes(16)
        with self.assertRaises(ValueError):
            StreamChunk(bytes(15), 0, 0)
        with self.assertRaises(ValueError):
            StreamChunk(sid, -1, 0)
        with self.assertRaises(ValueError):
            StreamChunk(sid, 0, 0x10)
        with self.assertRaises(ValueError):
            StreamChunk(sid, 0, CANCELLED)
        with self.assertRaises(ValueError):
            decode_chunk(bytes(21))
        with self.assertRaises(ValueError):
            decode_chunk(bytes([2]) + bytes(21))
        self.assertEqual(FIRST | LAST, StreamChunk(sid, 0, FIRST | LAST).flags)


if __name__ == "__main__":
    unittest.main()
