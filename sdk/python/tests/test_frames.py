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
        self.assertGreaterEqual(n, 9)

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
