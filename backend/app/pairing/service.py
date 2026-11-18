"""Pareamento por QR Code: criar, reivindicar e confirmar (contrato §3).

Regras de segurança:
- `pairing_token` aleatório de 256 bits, guardado só como hash;
- uso único: consumido na reivindicação, com ou sem confirmação posterior;
- validade curta (120 s por padrão);
- a confirmação exige o token do Android que reivindicou a sessão.
"""

from __future__ import annotations

from dataclasses import dataclass

from app.config import Settings
from app.devices.service import Device, create_device
from app.errors import RelayError
from app.persistence.db import Database
from app.persistence.maintenance import expire_pairing_sessions_in_tx
from app.security.tokens import hash_token, new_id, new_token, token_matches


@dataclass(frozen=True)
class CreatedSession:
    pairing_id: str
    pairing_token: str
    expires_at: int
    device: Device
    device_token: str


@dataclass(frozen=True)
class ClaimedSession:
    pairing_id: str
    android: Device
    android_token: str
    extension: Device


@dataclass(frozen=True)
class ConfirmResult:
    pairing_id: str
    extension_device_id: str
    accepted: bool
    link_id: str | None


def create_session(
    db: Database, settings: Settings, *, device_name: str, public_key: str, now: int
) -> CreatedSession:
    pairing_id = new_id()
    token = new_token()
    expires_at = now + settings.pairing_ttl_ms
    with db.transaction() as conn:
        expire_pairing_sessions_in_tx(conn, now=now, confirm_ttl_ms=settings.confirm_ttl_ms)
        device, device_token = create_device(
            conn, kind="extension", name=device_name, public_key=public_key, now=now
        )
        conn.execute(
            "INSERT INTO pairing_sessions (id, token_hash, extension_device_id, status,"
            " created_at, expires_at) VALUES (?, ?, ?, 'open', ?, ?)",
            (pairing_id, hash_token(token), device.id, now, expires_at),
        )
    return CreatedSession(pairing_id, token, expires_at, device, device_token)


def claim_session(
    db: Database,
    *,
    pairing_id: str,
    pairing_token: str,
    device_name: str,
    public_key: str,
    now: int,
) -> ClaimedSession:
    with db.transaction() as conn:
        row = conn.execute("SELECT * FROM pairing_sessions WHERE id = ?", (pairing_id,)).fetchone()
        # Token errado é tratado como sessão inexistente, sem consumir a sessão.
        if row is None or not token_matches(pairing_token, row["token_hash"]):
            raise RelayError("pairing_not_found", 404)
        if row["status"] == "expired":
            raise RelayError("pairing_expired", 410)
        if row["status"] != "open":
            raise RelayError("pairing_used", 409)
        if now > row["expires_at"]:
            conn.execute(
                "UPDATE pairing_sessions SET status = 'expired' WHERE id = ?", (pairing_id,)
            )
            conn.execute(
                "UPDATE devices SET status = 'revoked' WHERE id = ?", (row["extension_device_id"],)
            )
            raise RelayError("pairing_expired", 410)

        android, android_token = create_device(
            conn, kind="android", name=device_name, public_key=public_key, now=now
        )
        conn.execute(
            "UPDATE pairing_sessions SET status = 'claimed', android_device_id = ?, claimed_at = ?"
            " WHERE id = ?",
            (android.id, now, pairing_id),
        )
        ext_row = conn.execute(
            "SELECT * FROM devices WHERE id = ?", (row["extension_device_id"],)
        ).fetchone()
    return ClaimedSession(pairing_id, android, android_token, Device.from_row(ext_row))


def confirm_session(
    db: Database,
    settings: Settings,
    *,
    pairing_id: str,
    android_device_id: str,
    accepted: bool,
    now: int,
) -> ConfirmResult:
    with db.transaction() as conn:
        row = conn.execute("SELECT * FROM pairing_sessions WHERE id = ?", (pairing_id,)).fetchone()
        if row is None or row["android_device_id"] != android_device_id:
            raise RelayError("pairing_not_found", 404)
        if row["status"] == "expired":
            raise RelayError("pairing_expired", 410)
        if row["status"] != "claimed":
            raise RelayError("pairing_used", 409)
        if now > row["claimed_at"] + settings.confirm_ttl_ms:
            conn.execute(
                "UPDATE pairing_sessions SET status = 'expired' WHERE id = ?", (pairing_id,)
            )
            conn.execute(
                "UPDATE devices SET status = 'revoked' WHERE id IN (?, ?)",
                (row["extension_device_id"], android_device_id),
            )
            raise RelayError("pairing_expired", 410)

        ext_id = row["extension_device_id"]
        if not accepted:
            conn.execute(
                "UPDATE pairing_sessions SET status = 'cancelled' WHERE id = ?", (pairing_id,)
            )
            conn.execute(
                "UPDATE devices SET status = 'revoked' WHERE id IN (?, ?)",
                (ext_id, android_device_id),
            )
            return ConfirmResult(pairing_id, ext_id, False, None)

        link_id = new_id()
        conn.execute(
            "INSERT INTO communication_sessions (id, extension_device_id, android_device_id,"
            " status, created_at) VALUES (?, ?, ?, 'active', ?)",
            (link_id, ext_id, android_device_id, now),
        )
        conn.execute(
            "UPDATE devices SET status = 'active' WHERE id IN (?, ?)", (ext_id, android_device_id)
        )
        conn.execute("UPDATE pairing_sessions SET status = 'completed' WHERE id = ?", (pairing_id,))
    return ConfirmResult(pairing_id, ext_id, True, link_id)
