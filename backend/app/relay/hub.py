"""Registro das conexões WebSocket ativas, por dispositivo."""

from __future__ import annotations

import asyncio
import contextlib
import json
from typing import Any, Protocol


class Connection(Protocol):
    async def send_text(self, data: str) -> None: ...
    async def close(self, code: int = 1000) -> None: ...


class Hub:
    def __init__(self) -> None:
        self._connections: dict[str, Connection] = {}
        self._lock = asyncio.Lock()

    async def register(self, device_id: str, conn: Connection) -> None:
        async with self._lock:
            previous = self._connections.get(device_id)
            self._connections[device_id] = conn
        if previous is not None and previous is not conn:
            # Uma conexão por dispositivo: a mais recente substitui a anterior.
            with contextlib.suppress(Exception):
                await previous.close(4000)

    async def unregister(self, device_id: str, conn: Connection) -> None:
        async with self._lock:
            if self._connections.get(device_id) is conn:
                del self._connections[device_id]

    def is_online(self, device_id: str) -> bool:
        return device_id in self._connections

    async def send(self, device_id: str, frame: dict[str, Any]) -> bool:
        conn = self._connections.get(device_id)
        if conn is None:
            return False
        try:
            await conn.send_text(json.dumps(frame, separators=(",", ":")))
        except Exception:
            return False
        return True

    async def disconnect(self, device_id: str, code: int = 4003) -> None:
        async with self._lock:
            conn = self._connections.pop(device_id, None)
        if conn is not None:
            with contextlib.suppress(Exception):
                await conn.close(code)
