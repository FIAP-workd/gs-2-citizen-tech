"""Registro, autenticação e revogação de dispositivos."""

from __future__ import annotations

import sqlite3
from dataclasses import dataclass

from app.errors import RelayError
from app.persistence.db import Database
from app.security.tokens import hash_token, new_id, new_token, token_matches


@dataclass(frozen=True)
class Device:
    id: str
    kind: str
    device_name: str
    public_key: str
    status: str

    @classmethod
    def from_row(cls, row: sqlite3.Row) -> Device:
        return cls(row["id"], row["kind"], row["device_name"], row["public_key"], row["status"])

    def peer_view(self) -> dict[str, str]:
        return {
            "device_id": self.id,
            "device_name": self.device_name,
            "public_key": self.public_key,
        }


def create_device(
    conn: sqlite3.Connection, *, kind: str, name: str, public_key: str, now: int
) -> tuple[Device, str]:
    """Cria um dispositivo `pending` e devolve o token em claro (só nesta resposta)."""
    device_id = new_id()
    token = new_token()
    conn.execute(
        "INSERT INTO devices (id, kind, device_name, public_key, token_hash, status, created_at)"
        " VALUES (?, ?, ?, ?, ?, 'pending', ?)",
        (device_id, kind, name, public_key, hash_token(token), now),
    )
    return Device(device_id, kind, name, public_key, "pending"), token


def authenticate(db: Database, device_id: object, token: object, now: int) -> Device:
    """Valida `device_id` + `device_token`. Dispositivo revogado nunca autentica."""
    if not isinstance(device_id, str) or not isinstance(token, str):
        raise RelayError("auth_failed", 401)
    row = db.query_one("SELECT * FROM devices WHERE id = ?", (device_id,))
    # Compara mesmo quando o dispositivo não existe, para não revelar quais IDs existem.
    stored = row["token_hash"] if row else hash_token("")
    if not token_matches(token, stored) or row is None:
        raise RelayError("auth_failed", 401)
    if row["status"] == "revoked":
        raise RelayError("device_revoked", 403)
    with db.transaction() as conn:
        conn.execute("UPDATE devices SET last_seen = ? WHERE id = ?", (now, device_id))
    return Device.from_row(row)


def authenticate_bearer(db: Database, token: str, now: int) -> Device:
    """Autentica só pelo token (usado em `Authorization: Bearer`)."""
    row = db.query_one("SELECT id FROM devices WHERE token_hash = ?", (hash_token(token),))
    if row is None:
        raise RelayError("auth_failed", 401)
    return authenticate(db, row["id"], token, now)


def get_device(db: Database, device_id: str) -> Device | None:
    row = db.query_one("SELECT * FROM devices WHERE id = ?", (device_id,))
    return Device.from_row(row) if row else None
