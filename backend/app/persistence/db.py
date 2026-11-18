"""Banco de metadados do relay com versionamento, constraints e índices.

Guarda apenas o necessário para parear e rotear: dispositivos (nome, chave
pública, hash do token), sessões de pareamento, vínculos e o estado das
solicitações. Não existe coluna para senha, domínio, nonce ou ciphertext.
"""

from __future__ import annotations

import sqlite3
import threading
import time
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from pathlib import Path

LATEST_SCHEMA_VERSION = 2

_MIGRATIONS_TABLE_SQL = """
CREATE TABLE IF NOT EXISTS schema_migrations (
    version     INTEGER PRIMARY KEY,
    name        TEXT NOT NULL,
    applied_at  INTEGER NOT NULL
)
"""

_V1_STATEMENTS: tuple[str, ...] = (
    """
    CREATE TABLE IF NOT EXISTS devices (
        id           TEXT PRIMARY KEY,
        kind         TEXT NOT NULL CHECK (kind IN ('extension', 'android')),
        device_name  TEXT NOT NULL,
        public_key   TEXT NOT NULL,
        token_hash   TEXT NOT NULL,
        status       TEXT NOT NULL CHECK (status IN ('pending', 'active', 'revoked')),
        created_at   INTEGER NOT NULL,
        last_seen    INTEGER
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS pairing_sessions (
        id                   TEXT PRIMARY KEY,
        token_hash           TEXT NOT NULL,
        extension_device_id  TEXT NOT NULL REFERENCES devices(id),
        android_device_id    TEXT REFERENCES devices(id),
        status               TEXT NOT NULL CHECK (
                                 status IN ('open', 'claimed', 'completed', 'cancelled', 'expired')
                             ),
        created_at           INTEGER NOT NULL,
        expires_at           INTEGER NOT NULL,
        claimed_at           INTEGER
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS communication_sessions (
        id                   TEXT PRIMARY KEY,
        extension_device_id  TEXT NOT NULL REFERENCES devices(id),
        android_device_id    TEXT NOT NULL REFERENCES devices(id),
        status               TEXT NOT NULL CHECK (status IN ('active', 'revoked')),
        created_at           INTEGER NOT NULL,
        revoked_at           INTEGER
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS fill_requests (
        corr                 TEXT NOT NULL,
        link_id              TEXT NOT NULL REFERENCES communication_sessions(id),
        requester_device_id  TEXT NOT NULL,
        target_device_id     TEXT NOT NULL,
        status               TEXT NOT NULL CHECK (status IN ('pending', 'answered', 'expired')),
        created_at           INTEGER NOT NULL,
        answered_at          INTEGER,
        PRIMARY KEY (link_id, corr)
    )
    """,
)

_V2_STATEMENTS: tuple[str, ...] = (
    """
    CREATE TABLE devices_v2 (
        id           TEXT PRIMARY KEY,
        kind         TEXT NOT NULL CHECK (kind IN ('extension', 'android')),
        device_name  TEXT NOT NULL,
        public_key   TEXT NOT NULL,
        token_hash   TEXT NOT NULL,
        status       TEXT NOT NULL CHECK (status IN ('pending', 'active', 'revoked')),
        created_at   INTEGER NOT NULL,
        last_seen    INTEGER
    )
    """,
    """
    INSERT INTO devices_v2 (
        id, kind, device_name, public_key, token_hash, status, created_at, last_seen
    )
    SELECT id, kind, device_name, public_key, token_hash, status, created_at, last_seen
    FROM devices
    """,
    "DROP TABLE devices",
    "ALTER TABLE devices_v2 RENAME TO devices",
    """
    CREATE TABLE pairing_sessions_v2 (
        id                   TEXT PRIMARY KEY,
        token_hash           TEXT NOT NULL,
        extension_device_id  TEXT NOT NULL REFERENCES devices(id),
        android_device_id    TEXT REFERENCES devices(id),
        status               TEXT NOT NULL CHECK (
                                 status IN ('open', 'claimed', 'completed', 'cancelled', 'expired')
                             ),
        created_at           INTEGER NOT NULL,
        expires_at           INTEGER NOT NULL,
        claimed_at           INTEGER,
        CHECK (expires_at >= created_at)
    )
    """,
    """
    INSERT INTO pairing_sessions_v2 (
        id, token_hash, extension_device_id, android_device_id,
        status, created_at, expires_at, claimed_at
    )
    SELECT
        id, token_hash, extension_device_id, android_device_id,
        status, created_at, expires_at, claimed_at
    FROM pairing_sessions
    """,
    "DROP TABLE pairing_sessions",
    "ALTER TABLE pairing_sessions_v2 RENAME TO pairing_sessions",
    """
    CREATE TABLE communication_sessions_v2 (
        id                   TEXT PRIMARY KEY,
        extension_device_id  TEXT NOT NULL REFERENCES devices(id),
        android_device_id    TEXT NOT NULL REFERENCES devices(id),
        status               TEXT NOT NULL CHECK (status IN ('active', 'revoked')),
        created_at           INTEGER NOT NULL,
        revoked_at           INTEGER,
        CHECK (extension_device_id <> android_device_id)
    )
    """,
    """
    INSERT INTO communication_sessions_v2 (
        id, extension_device_id, android_device_id, status, created_at, revoked_at
    )
    SELECT id, extension_device_id, android_device_id, status, created_at, revoked_at
    FROM communication_sessions
    """,
    "DROP TABLE communication_sessions",
    "ALTER TABLE communication_sessions_v2 RENAME TO communication_sessions",
    """
    CREATE TABLE fill_requests_v2 (
        corr                 TEXT NOT NULL,
        link_id              TEXT NOT NULL REFERENCES communication_sessions(id),
        requester_device_id  TEXT NOT NULL REFERENCES devices(id),
        target_device_id     TEXT NOT NULL REFERENCES devices(id),
        status               TEXT NOT NULL CHECK (status IN ('pending', 'answered', 'expired')),
        created_at           INTEGER NOT NULL,
        answered_at          INTEGER,
        PRIMARY KEY (link_id, corr),
        CHECK (requester_device_id <> target_device_id)
    )
    """,
    """
    INSERT INTO fill_requests_v2 (
        corr, link_id, requester_device_id, target_device_id, status, created_at, answered_at
    )
    SELECT
        corr, link_id, requester_device_id, target_device_id, status, created_at, answered_at
    FROM fill_requests
    """,
    "DROP TABLE fill_requests",
    "ALTER TABLE fill_requests_v2 RENAME TO fill_requests",
    "CREATE UNIQUE INDEX IF NOT EXISTS uq_devices_token_hash ON devices(token_hash)",
    "CREATE INDEX IF NOT EXISTS idx_devices_status ON devices(status)",
    (
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_pairing_sessions_token_hash"
        " ON pairing_sessions(token_hash)"
    ),
    (
        "CREATE INDEX IF NOT EXISTS idx_pairing_sessions_status_expires"
        " ON pairing_sessions(status, expires_at)"
    ),
    (
        "CREATE INDEX IF NOT EXISTS idx_pairing_sessions_ext_device"
        " ON pairing_sessions(extension_device_id)"
    ),
    (
        "CREATE INDEX IF NOT EXISTS idx_pairing_sessions_android_device"
        " ON pairing_sessions(android_device_id)"
    ),
    (
        "CREATE INDEX IF NOT EXISTS idx_comm_sessions_ext_status"
        " ON communication_sessions(extension_device_id, status)"
    ),
    (
        "CREATE INDEX IF NOT EXISTS idx_comm_sessions_android_status"
        " ON communication_sessions(android_device_id, status)"
    ),
    (
        "CREATE INDEX IF NOT EXISTS idx_fill_requests_status_created"
        " ON fill_requests(status, created_at)"
    ),
    "CREATE INDEX IF NOT EXISTS idx_fill_requests_requester ON fill_requests(requester_device_id)",
    "CREATE INDEX IF NOT EXISTS idx_fill_requests_target ON fill_requests(target_device_id)",
)

_USER_VERSION_SQL = {
    1: "PRAGMA user_version = 1",
    2: "PRAGMA user_version = 2",
}


def _run_statements(conn: sqlite3.Connection, statements: tuple[str, ...]) -> None:
    for statement in statements:
        conn.execute(statement)


def _migrate_v1(conn: sqlite3.Connection) -> None:
    _run_statements(conn, _V1_STATEMENTS)


def _migrate_v2(conn: sqlite3.Connection) -> None:
    _run_statements(conn, _V2_STATEMENTS)


_MIGRATIONS: tuple[tuple[int, str, bool, Callable[[sqlite3.Connection], None]], ...] = (
    (1, "initial_schema", False, _migrate_v1),
    (2, "constraints_fks_and_indexes", True, _migrate_v2),
)


class Database:
    """Conexão SQLite com WAL, chaves estrangeiras, migrações e transações serializadas."""

    def __init__(self, path: str) -> None:
        self._is_memory = path == ":memory:"
        if not self._is_memory:
            Path(path).parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self._conn.row_factory = sqlite3.Row
        self._lock = threading.RLock()
        self._configure_pragmas()
        self._apply_migrations()

    def _configure_pragmas(self) -> None:
        with self._lock:
            self._conn.execute("PRAGMA foreign_keys = ON")
            self._conn.execute("PRAGMA busy_timeout = 5000")
            if not self._is_memory:
                self._conn.execute("PRAGMA journal_mode = WAL")
                self._conn.execute("PRAGMA synchronous = NORMAL")

    def _apply_migrations(self) -> None:
        with self._lock:
            self._conn.execute(_MIGRATIONS_TABLE_SQL)
            row = self._conn.execute(
                "SELECT COALESCE(MAX(version), 0) AS version FROM schema_migrations"
            ).fetchone()
            current = int(row["version"]) if row else 0
            for version, name, needs_fk_off, step in _MIGRATIONS:
                if version <= current:
                    continue
                if needs_fk_off:
                    self._conn.execute("PRAGMA foreign_keys = OFF")
                try:
                    self._conn.execute("BEGIN IMMEDIATE")
                    try:
                        step(self._conn)
                        self._conn.execute(
                            "INSERT INTO schema_migrations (version, name, applied_at)"
                            " VALUES (?, ?, ?)",
                            (version, name, int(time.time() * 1000)),
                        )
                        self._conn.execute(_USER_VERSION_SQL[version])
                    except BaseException:
                        if self._conn.in_transaction:
                            self._conn.execute("ROLLBACK")
                        raise
                    else:
                        if self._conn.in_transaction:
                            self._conn.execute("COMMIT")
                finally:
                    if needs_fk_off:
                        self._conn.execute("PRAGMA foreign_keys = ON")
                fk_violations = self._conn.execute("PRAGMA foreign_key_check").fetchall()
                if fk_violations:
                    raise sqlite3.IntegrityError(
                        f"Violação de chave estrangeira após migração v{version}"
                    )

    @contextmanager
    def transaction(self) -> Iterator[sqlite3.Connection]:
        with self._lock:
            self._conn.execute("BEGIN IMMEDIATE")
            try:
                yield self._conn
            except BaseException:
                if self._conn.in_transaction:
                    self._conn.execute("ROLLBACK")
                raise
            else:
                if self._conn.in_transaction:
                    self._conn.execute("COMMIT")

    def query(self, sql: str, params: tuple = ()) -> list[sqlite3.Row]:
        with self._lock:
            return list(self._conn.execute(sql, params))

    def query_one(self, sql: str, params: tuple = ()) -> sqlite3.Row | None:
        rows = self.query(sql, params)
        return rows[0] if rows else None

    def ping(self) -> bool:
        row = self.query_one("SELECT 1 AS ok")
        return bool(row and row["ok"] == 1)

    def schema_version(self) -> int:
        row = self.query_one("PRAGMA user_version")
        return int(row[0]) if row else 0

    def journal_mode(self) -> str:
        row = self.query_one("PRAGMA journal_mode")
        return str(row[0]) if row else ""

    def close(self) -> None:
        with self._lock:
            if not self._is_memory:
                try:
                    self._conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                except sqlite3.DatabaseError:
                    pass
            self._conn.close()
