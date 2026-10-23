"""Token-stream chunks carried in TOKEN frames (agent-swarm.md §5.2). Layout v2 (S2c PR 5):
[version:u8 = 2][streamId:16][attempt:u32][chunkSeq:u32][flags:u8][bytes]; v1 (no attempt, 22-byte header) still
decodes as attempt 0. Mirrors sjws_nettask.swarm.wire.StreamChunk; both are checked against
tests/sjws_nettask/swarm/wire/golden-chunks-v2.txt (and v1 for decoding). Standard library only.
"""
from __future__ import annotations

from dataclasses import dataclass

from .frames import CANCELLED, ERROR, LAST, MAX_PAYLOAD

CHUNK_VERSION = 2
CHUNK_VERSION_1 = 1
CHUNK_HEADER = 26
CHUNK_HEADER_V1 = 22
FIRST = 0x08
RESET = 0x10  # consumer-side marker: the stream restarted at chunk_seq 0 under `attempt`
MAX_SEQ = 0xFFFFFFFF
MAX_ATTEMPT = 0xFFFFFFFF
MAX_BYTES = MAX_PAYLOAD - CHUNK_HEADER
_KNOWN = LAST | CANCELLED | ERROR | FIRST | RESET


@dataclass(frozen=True)
class StreamChunk:
    stream_id: bytes
    chunk_seq: int
    flags: int
    data: bytes = b""
    attempt: int = 0

    def __post_init__(self) -> None:
        if len(self.stream_id) != 16:
            raise ValueError("stream_id must be 16 bytes")
        if not 0 <= self.attempt <= MAX_ATTEMPT:
            raise ValueError(f"attempt {self.attempt}")
        if not 0 <= self.chunk_seq <= MAX_SEQ:
            raise ValueError(f"chunk_seq {self.chunk_seq}")
        if self.flags & ~_KNOWN:
            raise ValueError(f"flags {self.flags}")
        if self.flags & (CANCELLED | ERROR) and not self.flags & LAST:
            raise ValueError("CANCELLED/ERROR need LAST")
        if len(self.data) > MAX_BYTES:
            raise ValueError(f"data {len(self.data)} > {MAX_BYTES}")

    def encode(self) -> bytes:
        return (bytes((CHUNK_VERSION,)) + self.stream_id + self.attempt.to_bytes(4, "big")
                + self.chunk_seq.to_bytes(4, "big")
                + bytes((self.flags,)) + bytes(self.data))


def decode_chunk(payload: bytes) -> StreamChunk:
    if len(payload) < CHUNK_HEADER_V1:
        raise ValueError(f"chunk {len(payload)}B < {CHUNK_HEADER_V1}")
    if len(payload) > MAX_PAYLOAD:
        raise ValueError(f"chunk {len(payload)}B > {MAX_PAYLOAD}")
    if payload[0] == CHUNK_VERSION_1:
        return StreamChunk(bytes(payload[1:17]), int.from_bytes(payload[17:21], "big"), payload[21], bytes(payload[22:]))
    if payload[0] != CHUNK_VERSION:
        raise ValueError(f"chunk version {payload[0]}")
    if len(payload) < CHUNK_HEADER:
        raise ValueError(f"chunk {len(payload)}B < {CHUNK_HEADER}")
    return StreamChunk(bytes(payload[1:17]), int.from_bytes(payload[21:25], "big"), payload[25], bytes(payload[26:]),
                       int.from_bytes(payload[17:21], "big"))
