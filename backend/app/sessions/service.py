"""Vínculos ativos entre dispositivos (`link_id`) e estado das solicitações."""

from __future__ import annotations

import sqlite3
from dataclasses import dataclass

from app.persistence.db import Database


@dataclass(frozen=True)
class Link:
    id: str
    extension_device_id: str
    android_device_id: str
    status: str

    @classmethod
    def from_row(cls, row: sqlite3.Row) -> Link:
        return cls(row["id"], row["extension_device_id"], row["android_device_id"], row["status"])

    def endpoints(self) -> tuple[str, str]:
        return self.extension_device_id, self.android_device_id

    def peer_of(self, device_id: str) -> str | None:
        if device_id == self.extension_device_id:
            return self.android_device_id
        if device_id == self.android_device_id:
            return self.extension_device_id
        return None


def get_link(db: Database, link_id: str) -> Link | None:
    row = db.query_one("SELECT * FROM communication_sessions WHERE id = ?", (link_id,))
    return Link.from_row(row) if row else None


def active_links_for(db: Database, device_id: str) -> list[Link]:
    rows = db.query(
        "SELECT * FROM communication_sessions WHERE status = 'active'"
        " AND (extension_device_id = ? OR android_device_id = ?) ORDER BY created_at",
        (device_id, device_id),
    )
    return [Link.from_row(r) for r in rows]


def revoke_link(db: Database, link_id: str, now: int) -> Link | None:
    """Revoga o vínculo e os dois dispositivos dele (cada vínculo tem identidades próprias)."""
    with db.transaction() as conn:
        row = conn.execute(
            "SELECT * FROM communication_sessions WHERE id = ?", (link_id,)
        ).fetchone()
        if row is None:
            return None
        link = Link.from_row(row)
        conn.execute(
            "UPDATE communication_sessions SET status = 'revoked', revoked_at = ? WHERE id = ?",
            (now, link_id),
        )
        conn.execute(
            "UPDATE devices SET status = 'revoked' WHERE id IN (?, ?)",
            (link.extension_device_id, link.android_device_id),
        )
        conn.execute(
            "UPDATE fill_requests SET status = 'expired' WHERE link_id = ? AND status = 'pending'",
            (link_id,),
        )
    return link


def track_fill_request(
    db: Database, *, corr: str, link_id: str, sender: str, recipient: str, now: int, ttl_ms: int
) -> None:
    """Acompanha pedido/resposta pelo `corr` opaco, sem conhecer o conteúdo."""
    if not corr:
        return
    with db.transaction() as conn:
        conn.execute(
            "UPDATE fill_requests SET status = 'expired'"
            " WHERE status = 'pending' AND created_at < ?",
            (now - ttl_ms,),
        )
        row = conn.execute(
            "SELECT * FROM fill_requests WHERE link_id = ? AND corr = ?", (link_id, corr)
        ).fetchone()
        if row is None:
            conn.execute(
                "INSERT INTO fill_requests (corr, link_id, requester_device_id, target_device_id,"
                " status, created_at) VALUES (?, ?, ?, ?, 'pending', ?)",
                (corr, link_id, sender, recipient, now),
            )
        elif row["status"] == "pending" and row["target_device_id"] == sender:
            conn.execute(
                "UPDATE fill_requests SET status = 'answered', answered_at = ?"
                " WHERE link_id = ? AND corr = ?",
                (now, link_id, corr),
            )
