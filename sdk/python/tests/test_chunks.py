"""Conformance: the Python chunk codec against the shared golden chunks. Run: python3 -m unittest discover sdk/python/tests"""
import pathlib
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "sdk" / "python"))

from sjws_swarm.chunks import FIRST, StreamChunk, decode_chunk  # noqa: E402
from sjws_swarm.frames import CANCELLED, LAST, Frame, FrameType, decode  # noqa: E402

WIRE = ROOT / "tests" / "sjws_nettask" / "swarm" / "wire"
GOLDEN = WIRE / "golden-chunks-v2.txt"
GOLDEN_V1 = WIRE / "golden-chunks-v1.txt"


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
        self.assertEqual(5, n)

    def test_v1_still_decodes_as_attempt_0(self):
        n = 0
        for line in GOLDEN_V1.read_text().splitlines():
            if not line.strip() or line.startswith("#"):
                continue
            name, flags, chunk, _ = line.split(" ")
            c = decode_chunk(bytes.fromhex(chunk))
            self.assertEqual((int(flags), 0), (c.flags, c.attempt), name)
            self.assertEqual(2, c.encode()[0], "re-encoded as v2")
            n += 1
        self.assertEqual(4, n)

    def test_errors(self):
        sid = bytes(16)
        with self.assertRaises(ValueError):
            StreamChunk(bytes(15), 0, 0)
        with self.assertRaises(ValueError):
            StreamChunk(sid, -1, 0)
        with self.assertRaises(ValueError):
            StreamChunk(sid, 0, 0x20)
        with self.assertRaises(ValueError):
            StreamChunk(sid, 0, CANCELLED)
        with self.assertRaises(ValueError):
            decode_chunk(bytes(21))
        with self.assertRaises(ValueError):
            decode_chunk(bytes([3]) + bytes(25))
        with self.assertRaises(ValueError):
            decode_chunk(bytes([2]) + bytes(21))
        with self.assertRaises(ValueError):
            StreamChunk(sid, 0, 0, b"", -1)
        with self.assertRaises(ValueError):
            decode_chunk(bytes([1]) + bytes((1 << 24) - 1))
        with self.assertRaises(ValueError):
            StreamChunk(sid, 0, 0, bytes((1 << 24) - 26))
        self.assertEqual(FIRST | LAST, StreamChunk(sid, 0, FIRST | LAST).flags)


if __name__ == "__main__":
    unittest.main()
