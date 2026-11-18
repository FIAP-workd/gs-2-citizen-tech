"""Ciclo de vida dos metadados: expiração e expurgo de registros antigos."""

from __future__ import annotations

import sqlite3
from dataclasses import dataclass

from app.persistence.db import Database


@dataclass(frozen=True)
class MaintenanceStats:
    expired_open_pairings: int
    expired_claimed_pairings: int
    expired_fill_requests: int


@dataclass(frozen=True)
class PurgeStats:
    deleted_fill_requests: int
    deleted_pairing_sessions: int
    deleted_communication_sessions: int
    deleted_devices: int


def expire_pairing_sessions_in_tx(
    conn: sqlite3.Connection, *, now: int, confirm_ttl_ms: int
) -> tuple[int, int]:
    """Expira sessões de pareamento vencidas e revoga seus dispositivos pendentes."""
    conn.execute(
        "UPDATE devices SET status = 'revoked'"
        " WHERE status = 'pending' AND id IN ("
        "     SELECT extension_device_id FROM pairing_sessions"
        "     WHERE status = 'open' AND expires_at < ?"
        " )",
        (now,),
    )
    open_cur = conn.execute(
        "UPDATE pairing_sessions SET status = 'expired' WHERE status = 'open' AND expires_at < ?",
        (now,),
    )

    conn.execute(
        "UPDATE devices SET status = 'revoked'"
        " WHERE status = 'pending' AND ("
        "     id IN ("
        "         SELECT extension_device_id FROM pairing_sessions"
        "         WHERE status = 'claimed' AND claimed_at IS NOT NULL AND claimed_at + ? < ?"
        "     )"
        "     OR id IN ("
        "         SELECT android_device_id FROM pairing_sessions"
        "         WHERE status = 'claimed' AND android_device_id IS NOT NULL"
        "           AND claimed_at IS NOT NULL AND claimed_at + ? < ?"
        "     )"
        " )",
        (confirm_ttl_ms, now, confirm_ttl_ms, now),
    )
    claimed_cur = conn.execute(
        "UPDATE pairing_sessions SET status = 'expired'"
        " WHERE status = 'claimed' AND claimed_at IS NOT NULL AND claimed_at + ? < ?",
        (confirm_ttl_ms, now),
    )
    return open_cur.rowcount, claimed_cur.rowcount


def expire_stale_records(
    db: Database, *, now: int, confirm_ttl_ms: int, fill_request_ttl_ms: int
) -> MaintenanceStats:
    """Atualiza sessões de pareamento e `fill_requests` que já venceram."""
    with db.transaction() as conn:
        open_count, claimed_count = expire_pairing_sessions_in_tx(
            conn, now=now, confirm_ttl_ms=confirm_ttl_ms
        )
        fill_cur = conn.execute(
            "UPDATE fill_requests SET status = 'expired'"
            " WHERE status = 'pending' AND created_at < ?",
            (now - fill_request_ttl_ms,),
        )
    return MaintenanceStats(
        expired_open_pairings=open_count,
        expired_claimed_pairings=claimed_count,
        expired_fill_requests=fill_cur.rowcount,
    )


def purge_expired_records(
    db: Database, *, now: int, pairing_retention_ms: int, fill_request_retention_ms: int
) -> PurgeStats:
    """Remove metadados finalizados após a janela de retenção sem violar FKs."""
    fill_cutoff = now - fill_request_retention_ms
    pairing_cutoff = now - pairing_retention_ms
    with db.transaction() as conn:
        fill_cur = conn.execute(
            "DELETE FROM fill_requests WHERE status IN ('answered', 'expired') AND created_at < ?",
            (fill_cutoff,),
        )
        pair_cur = conn.execute(
            "DELETE FROM pairing_sessions"
            " WHERE status IN ('completed', 'cancelled', 'expired') AND created_at < ?",
            (pairing_cutoff,),
        )
        comm_cur = conn.execute(
            "DELETE FROM communication_sessions"
            " WHERE status = 'revoked' AND revoked_at IS NOT NULL AND revoked_at < ?"
            " AND NOT EXISTS ("
            "     SELECT 1 FROM fill_requests fr WHERE fr.link_id = communication_sessions.id"
            " )",
            (pairing_cutoff,),
        )
        dev_cur = conn.execute(
            "DELETE FROM devices"
            " WHERE status = 'revoked'"
            " AND NOT EXISTS ("
            "     SELECT 1 FROM pairing_sessions ps"
            "     WHERE ps.extension_device_id = devices.id OR ps.android_device_id = devices.id"
            " )"
            " AND NOT EXISTS ("
            "     SELECT 1 FROM communication_sessions cs"
            "     WHERE cs.extension_device_id = devices.id OR cs.android_device_id = devices.id"
            " )"
            " AND NOT EXISTS ("
            "     SELECT 1 FROM fill_requests fr"
            "     WHERE fr.requester_device_id = devices.id OR fr.target_device_id = devices.id"
            " )",
        )
    return PurgeStats(
        deleted_fill_requests=fill_cur.rowcount,
        deleted_pairing_sessions=pair_cur.rowcount,
        deleted_communication_sessions=comm_cur.rowcount,
        deleted_devices=dev_cur.rowcount,
    )
