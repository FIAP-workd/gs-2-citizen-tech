"""Configuração do relay, lida de variáveis de ambiente."""

from __future__ import annotations

import os
from dataclasses import dataclass

PROTOCOL_VERSION = 1


def _int_env(name: str, default: int) -> int:
    value = os.environ.get(name)
    return int(value) if value else default


def _db_path_from_url(url: str | None) -> str:
    """Aceita `sqlite:////caminho/absoluto`, `sqlite:///relativo`, `:memory:` ou caminho simples."""
    if not url:
        return "data/relay.sqlite3"
    if url in (":memory:", "sqlite:///:memory:"):
        return ":memory:"
    if url.startswith("sqlite:///"):
        return url.removeprefix("sqlite:///")
    if "://" in url:
        scheme = url.split("://", 1)[0]
        raise ValueError(
            f"Esquema DATABASE_URL não suportado no protótipo: {scheme!r}. Use sqlite:///..."
        )
    return url


@dataclass(frozen=True)
class Settings:
    database_path: str = "data/relay.sqlite3"
    pairing_ttl_ms: int = 120_000
    """Validade do QR Code (contrato §3.1: 120 s)."""
    confirm_ttl_ms: int = 180_000
    """Tempo máximo entre a leitura do QR e a confirmação no celular."""
    fill_request_ttl_ms: int = 120_000
    pairing_retention_ms: int = 24 * 60 * 60 * 1000
    """Retenção de sessões de pareamento encerradas e vínculos revogados."""
    fill_request_retention_ms: int = 24 * 60 * 60 * 1000
    """Retenção de metadados de solicitações respondidas ou expiradas."""
    max_frame_bytes: int = 16 * 1024
    auth_timeout_s: float = 5.0
    relay_burst: int = 30
    """Mensagens `relay` permitidas em rajada por dispositivo."""
    relay_refill_per_s: float = 1.0
    pairing_per_minute: int = 20
    """Criações/reivindicações de pareamento por IP e minuto."""

    @classmethod
    def from_env(cls) -> Settings:
        return cls(
            database_path=_db_path_from_url(os.environ.get("DATABASE_URL")),
            pairing_ttl_ms=_int_env("PAIRING_TTL_SECONDS", 120) * 1000,
            pairing_per_minute=_int_env("PAIRING_PER_MINUTE", 20),
            pairing_retention_ms=_int_env("PAIRING_RETENTION_HOURS", 24) * 60 * 60 * 1000,
            fill_request_retention_ms=_int_env("FILL_REQUEST_RETENTION_HOURS", 24) * 60 * 60 * 1000,
        )
