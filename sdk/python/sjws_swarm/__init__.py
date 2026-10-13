"""sjws_swarm: thin client for the sjws agent swarm (docs/design/agent-swarm.md §11).

S1 ships the wire-frame codec only; the unary HTTP client follows once the server binds
the /swarm/v1 endpoints (see the S1 PR notes).
"""
from .frames import Frame, FrameType, LAST, CANCELLED, ERROR, VERSION, HEADER, MAX_PAYLOAD, decode

__all__ = ["Frame", "FrameType", "LAST", "CANCELLED", "ERROR", "VERSION", "HEADER", "MAX_PAYLOAD", "decode"]
