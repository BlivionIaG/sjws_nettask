"""sjws_swarm: thin client for the sjws agent swarm (docs/design/agent-swarm.md §11).

S1: the wire-frame codec. S1b: the synchronous unary client (submit, call, claim, fenced settles).
"""
from .client import Client, Lease, SwarmError, DeadlineExceeded, LeaseLost
from .frames import Frame, FrameType, LAST, CANCELLED, ERROR, VERSION, HEADER, MAX_PAYLOAD, decode

__all__ = ["Client", "Lease", "SwarmError", "DeadlineExceeded", "LeaseLost", "Frame", "FrameType", "LAST", "CANCELLED", "ERROR", "VERSION", "HEADER", "MAX_PAYLOAD", "decode"]
