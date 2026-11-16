"""Banco de metadados do relay.

Guarda apenas o necessário para parear e rotear: dispositivos (nome, chave
pública, hash do token), sessões de pareamento, vínculos e o estado das
solicitações. Não existe coluna para senha, domínio, nonce ou ciphertext.
"""

from __future__ import annotations

import sqlite3
import threading
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS devices (
    id           TEXT PRIMARY KEY,
    kind         TEXT NOT NULL CHECK (kind IN ('extension', 'android')),
    device_name  TEXT NOT NULL,
    public_key   TEXT NOT NULL,
    token_hash   TEXT NOT NULL,
    status       TEXT NOT NULL CHECK (status IN ('pending', 'active', 'revoked')),
    created_at   INTEGER NOT NULL,
    last_seen    INTEGER
);

CREATE TABLE IF NOT EXISTS pairing_sessions (
    id                   TEXT PRIMARY KEY,
    token_hash           TEXT NOT NULL,
    extension_device_id  TEXT NOT NULL REFERENCES devices(id),
    android_device_id    TEXT REFERENCES devices(id),
    status               TEXT NOT NULL
                         CHECK (status IN ('open', 'claimed', 'completed', 'cancelled', 'expired')),
    created_at           INTEGER NOT NULL,
    expires_at           INTEGER NOT NULL,
    claimed_at           INTEGER
);

CREATE TABLE IF NOT EXISTS communication_sessions (
    id                   TEXT PRIMARY KEY,
    extension_device_id  TEXT NOT NULL REFERENCES devices(id),
    android_device_id    TEXT NOT NULL REFERENCES devices(id),
    status               TEXT NOT NULL CHECK (status IN ('active', 'revoked')),
    created_at           INTEGER NOT NULL,
    revoked_at           INTEGER
);

-- Sem domínio nem identificador de credencial: só o `corr` opaco (threat-model §5).
CREATE TABLE IF NOT EXISTS fill_requests (
    corr                 TEXT NOT NULL,
    link_id              TEXT NOT NULL REFERENCES communication_sessions(id),
    requester_device_id  TEXT NOT NULL,
    target_device_id     TEXT NOT NULL,
    status               TEXT NOT NULL CHECK (status IN ('pending', 'answered', 'expired')),
    created_at           INTEGER NOT NULL,
    answered_at          INTEGER,
    PRIMARY KEY (link_id, corr)
);
"""


class Database:
    """Conexão SQLite única, protegida por lock (suficiente para o protótipo)."""

    def __init__(self, path: str) -> None:
        if path != ":memory:":
            Path(path).parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA foreign_keys = ON")
        self._lock = threading.RLock()
        self._conn.executescript(SCHEMA)

    @contextmanager
    def transaction(self) -> Iterator[sqlite3.Connection]:
        with self._lock:
            self._conn.execute("BEGIN IMMEDIATE")
            try:
                yield self._conn
            except BaseException:
                self._conn.execute("ROLLBACK")
                raise
            else:
                self._conn.execute("COMMIT")

    def query(self, sql: str, params: tuple = ()) -> list[sqlite3.Row]:
        with self._lock:
            return list(self._conn.execute(sql, params))

    def query_one(self, sql: str, params: tuple = ()) -> sqlite3.Row | None:
        rows = self.query(sql, params)
        return rows[0] if rows else None

    def close(self) -> None:
        with self._lock:
            self._conn.close()
