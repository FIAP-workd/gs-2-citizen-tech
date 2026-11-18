"""Acesso ao banco de metadados (SQLite)."""

from app.persistence.db import LATEST_SCHEMA_VERSION, Database
from app.persistence.maintenance import (
    MaintenanceStats,
    PurgeStats,
    expire_pairing_sessions_in_tx,
    expire_stale_records,
    purge_expired_records,
)

__all__ = [
    "Database",
    "LATEST_SCHEMA_VERSION",
    "MaintenanceStats",
    "PurgeStats",
    "expire_pairing_sessions_in_tx",
    "expire_stale_records",
    "purge_expired_records",
]
