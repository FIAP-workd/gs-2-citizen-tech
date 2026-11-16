"""Limitadores de requisição em memória (ameaças P4 e M7)."""

from __future__ import annotations

import threading
import time
from collections import defaultdict, deque


class TokenBucket:
    """Balde de fichas por chave: permite rajadas curtas e limita a taxa média."""

    def __init__(self, capacity: int, refill_per_s: float, clock=time.monotonic) -> None:
        self.capacity = capacity
        self.refill_per_s = refill_per_s
        self._clock = clock
        self._state: dict[str, tuple[float, float]] = {}
        self._lock = threading.Lock()

    def allow(self, key: str) -> bool:
        now = self._clock()
        with self._lock:
            tokens, last = self._state.get(key, (float(self.capacity), now))
            tokens = min(self.capacity, tokens + (now - last) * self.refill_per_s)
            if tokens < 1:
                self._state[key] = (tokens, now)
                return False
            self._state[key] = (tokens - 1, now)
            return True


class SlidingWindow:
    """Máximo de N eventos por janela de tempo, por chave (ex.: IP)."""

    def __init__(self, limit: int, window_s: float, clock=time.monotonic) -> None:
        self.limit = limit
        self.window_s = window_s
        self._clock = clock
        self._events: dict[str, deque[float]] = defaultdict(deque)
        self._lock = threading.Lock()

    def allow(self, key: str) -> bool:
        now = self._clock()
        with self._lock:
            events = self._events[key]
            while events and now - events[0] > self.window_s:
                events.popleft()
            if len(events) >= self.limit:
                return False
            events.append(now)
            return True
