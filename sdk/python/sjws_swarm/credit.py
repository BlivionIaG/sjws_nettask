"""CREDIT frame payloads (agent-swarm.md §5.2 Backpressure, §11.2; S2c PR 6, #247). Layout v1, 29 bytes, big-endian:
[version:u8 = 1][streamId:16][attempt:u32][chunks:u32][bytes:u32]. chunks and bytes are an absolute snapshot of the send
credit left; attempt is the lease's attempt. Mirrors sjws_nettask.swarm.wire.Credit; both are checked against
tests/sjws_nettask/swarm/wire/golden-credit-v1.txt. Decoding rejects an empty payload ("length"), then a version other
than 1 ("version"), then a length other than 29 ("length"); the ValueError message starts with that token. Frame flags
are reserved (sent as 0, ignored on receipt). Throttling on these frames is the SDK's stream() (PR 7). Standard library
only.
"""
from __future__ import annotations

from dataclasses import dataclass

CREDIT_VERSION = 1
CREDIT_SIZE = 29
MAX_U32 = 0xFFFFFFFF


@dataclass(frozen=True)
class Credit:
    stream_id: bytes
    attempt: int
    chunks: int
    bytes: int

    def __post_init__(self) -> None:
        if len(self.stream_id) != 16:
            raise ValueError("stream_id must be 16 bytes")
        for name in ("attempt", "chunks", "bytes"):
            v = getattr(self, name)
            if not 0 <= v <= MAX_U32:
                raise ValueError(f"{name} {v}")

    def encode(self) -> bytes:
        return (bytes((CREDIT_VERSION,)) + self.stream_id + self.attempt.to_bytes(4, "big")
                + self.chunks.to_bytes(4, "big") + self.bytes.to_bytes(4, "big"))


def decode_credit(payload: bytes) -> Credit:
    if not payload:
        raise ValueError(f"length 0: a CREDIT payload is {CREDIT_SIZE} bytes")
    if payload[0] != CREDIT_VERSION:
        raise ValueError(f"version {payload[0]}: expected {CREDIT_VERSION}")
    if len(payload) != CREDIT_SIZE:
        raise ValueError(f"length {len(payload)}: a CREDIT payload is {CREDIT_SIZE} bytes")
    return Credit(bytes(payload[1:17]), int.from_bytes(payload[17:21], "big"), int.from_bytes(payload[21:25], "big"),
                  int.from_bytes(payload[25:29], "big"))
