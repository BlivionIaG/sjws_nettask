"""Swarm wire protocol v1 frames: [version:u8][type:u8][flags:u8][len:u24][payload] (agent-swarm.md §11.2).

Mirrors sjws_nettask.swarm.wire.Frame; both are checked against tests/sjws_nettask/swarm/wire/golden-v1.txt.
Standard library only.
"""
from __future__ import annotations

import enum
from dataclasses import dataclass

VERSION = 1
HEADER = 6
MAX_PAYLOAD = (1 << 24) - 1
LAST = 0x01
CANCELLED = 0x02
ERROR = 0x04


class FrameType(enum.IntEnum):
    TASK = 1
    CLAIM = 2
    LEASE = 3
    EXTEND = 4
    RESULT = 5
    TOKEN = 6
    ERROR = 7
    CANCEL = 8
    CREDIT = 9


@dataclass(frozen=True)
class Frame:
    type: FrameType
    flags: int
    payload: bytes = b""

    def __post_init__(self) -> None:
        if not 0 <= self.flags <= 0xFF:
            raise ValueError(f"flags {self.flags}")
        if len(self.payload) > MAX_PAYLOAD:
            raise ValueError(f"payload {len(self.payload)} > {MAX_PAYLOAD}")

    def encode(self) -> bytes:
        n = len(self.payload)
        return bytes((VERSION, int(self.type), self.flags)) + n.to_bytes(3, "big") + bytes(self.payload)


def decode(buf: bytes | bytearray | memoryview) -> tuple[Frame, int] | None:
    """Decodes one frame from the start of buf: (frame, bytes consumed), or None if buf holds no whole frame."""
    if len(buf) < HEADER:
        return None
    if buf[0] != VERSION:
        raise ValueError(f"wire version {buf[0]}")
    try:
        ftype = FrameType(buf[1])
    except ValueError:
        raise ValueError(f"unknown frame type {buf[1]}") from None
    n = int.from_bytes(bytes(buf[3:6]), "big")
    if len(buf) < HEADER + n:
        return None
    return Frame(ftype, buf[2], bytes(buf[HEADER:HEADER + n])), HEADER + n
