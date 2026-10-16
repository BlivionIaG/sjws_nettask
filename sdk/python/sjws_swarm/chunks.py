"""Token-stream chunks carried in TOKEN frames (agent-swarm.md §5.2):
[version:u8][streamId:16][chunkSeq:u32][flags:u8][bytes]. Mirrors sjws_nettask.swarm.wire.StreamChunk; both are
checked against tests/sjws_nettask/swarm/wire/golden-chunks-v1.txt. Standard library only.
"""
from __future__ import annotations

from dataclasses import dataclass

from .frames import CANCELLED, ERROR, LAST, MAX_PAYLOAD

CHUNK_VERSION = 1
CHUNK_HEADER = 22
FIRST = 0x08
MAX_SEQ = 0xFFFFFFFF
MAX_BYTES = MAX_PAYLOAD - CHUNK_HEADER
_KNOWN = LAST | CANCELLED | ERROR | FIRST


@dataclass(frozen=True)
class StreamChunk:
    stream_id: bytes
    chunk_seq: int
    flags: int
    data: bytes = b""

    def __post_init__(self) -> None:
        if len(self.stream_id) != 16:
            raise ValueError("stream_id must be 16 bytes")
        if not 0 <= self.chunk_seq <= MAX_SEQ:
            raise ValueError(f"chunk_seq {self.chunk_seq}")
        if self.flags & ~_KNOWN:
            raise ValueError(f"flags {self.flags}")
        if self.flags & (CANCELLED | ERROR) and not self.flags & LAST:
            raise ValueError("CANCELLED/ERROR need LAST")
        if len(self.data) > MAX_BYTES:
            raise ValueError(f"data {len(self.data)} > {MAX_BYTES}")

    def encode(self) -> bytes:
        return (bytes((CHUNK_VERSION,)) + self.stream_id + self.chunk_seq.to_bytes(4, "big")
                + bytes((self.flags,)) + bytes(self.data))


def decode_chunk(payload: bytes) -> StreamChunk:
    if len(payload) < CHUNK_HEADER:
        raise ValueError(f"chunk {len(payload)}B < {CHUNK_HEADER}")
    if len(payload) > MAX_PAYLOAD:
        raise ValueError(f"chunk {len(payload)}B > {MAX_PAYLOAD}")
    if payload[0] != CHUNK_VERSION:
        raise ValueError(f"chunk version {payload[0]}")
    return StreamChunk(bytes(payload[1:17]), int.from_bytes(payload[17:21], "big"), payload[21], bytes(payload[22:]))
