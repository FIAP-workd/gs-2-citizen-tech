"""Objetos compartilhados pela aplicação (injetados em `app.state.ctx`)."""

from __future__ import annotations

import time
from collections.abc import Callable
from dataclasses import dataclass, field

from app.config import Settings
from app.persistence.db import Database
from app.relay.hub import Hub
from app.security.rate_limit import SlidingWindow, TokenBucket


def now_ms() -> int:
    return int(time.time() * 1000)


@dataclass
class AppContext:
    settings: Settings
    db: Database
    hub: Hub = field(default_factory=Hub)
    clock: Callable[[], int] = now_ms
    relay_limiter: TokenBucket | None = None
    pairing_limiter: SlidingWindow | None = None

    def __post_init__(self) -> None:
        if self.relay_limiter is None:
            self.relay_limiter = TokenBucket(
                self.settings.relay_burst, self.settings.relay_refill_per_s
            )
        if self.pairing_limiter is None:
            self.pairing_limiter = SlidingWindow(self.settings.pairing_per_minute, 60.0)
