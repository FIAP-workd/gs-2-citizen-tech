"""Testes da camada de persistência: migrações, integridade, índices, WAL e ciclo de vida."""

from __future__ import annotations

import sqlite3
from pathlib import Path

import pytest
from cs_protocol import KeyPair
from fastapi.testclient import TestClient

from app.config import Settings, _db_path_from_url
from app.context import AppContext
from app.main import create_app
from app.persistence import (
    LATEST_SCHEMA_VERSION,
    Database,
    expire_stale_records,
    purge_expired_records,
)
from tests.helpers import claim, create_session, pair

LEGACY_V0_SCHEMA = """
CREATE TABLE devices (
    id           TEXT PRIMARY KEY,
    kind         TEXT NOT NULL CHECK (kind IN ('extension', 'android')),
    device_name  TEXT NOT NULL,
    public_key   TEXT NOT NULL,
    token_hash   TEXT NOT NULL,
    status       TEXT NOT NULL CHECK (status IN ('pending', 'active', 'revoked')),
    created_at   INTEGER NOT NULL,
    last_seen    INTEGER
);
CREATE TABLE pairing_sessions (
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
CREATE TABLE communication_sessions (
    id                   TEXT PRIMARY KEY,
    extension_device_id  TEXT NOT NULL REFERENCES devices(id),
    android_device_id    TEXT NOT NULL REFERENCES devices(id),
    status               TEXT NOT NULL CHECK (status IN ('active', 'revoked')),
    created_at           INTEGER NOT NULL,
    revoked_at           INTEGER
);
CREATE TABLE fill_requests (
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


def test_config_banco_valida_esquema_da_url():
    assert _db_path_from_url(None) == "data/relay.sqlite3"
    assert _db_path_from_url(":memory:") == ":memory:"
    assert _db_path_from_url("sqlite:///:memory:") == ":memory:"
    assert _db_path_from_url("sqlite:////srv/data/relay.sqlite3") == "/srv/data/relay.sqlite3"
    with pytest.raises(ValueError, match="Esquema DATABASE_URL não suportado"):
        _db_path_from_url("postgresql://user:pass@localhost/db")


def test_banco_novo_ativa_wal_fk_e_schema_v2(tmp_path: Path):
    path = tmp_path / "relay.sqlite3"
    db = Database(str(path))
    try:
        assert db.schema_version() == LATEST_SCHEMA_VERSION
        assert db.journal_mode().lower() == "wal"
        assert db.ping() is True
        fk_enabled = db.query_one("PRAGMA foreign_keys")[0]
        assert fk_enabled == 1
        migrations = db.query("SELECT version, name FROM schema_migrations ORDER BY version")
        assert [(r["version"], r["name"]) for r in migrations] == [
            (1, "initial_schema"),
            (2, "constraints_fks_and_indexes"),
        ]
    finally:
        db.close()


def test_reabertura_do_banco_e_idempotente(tmp_path: Path):
    path = tmp_path / "relay.sqlite3"
    db1 = Database(str(path))
    db1.close()

    db2 = Database(str(path))
    try:
        assert db2.schema_version() == LATEST_SCHEMA_VERSION
        count = db2.query_one("SELECT COUNT(*) FROM schema_migrations")[0]
        assert count == 2
    finally:
        db2.close()


def test_migra_banco_legado_v0_preservando_dados(tmp_path: Path):
    path = tmp_path / "legacy.sqlite3"
    conn = sqlite3.connect(path)
    conn.executescript(LEGACY_V0_SCHEMA)
    conn.execute(
        "INSERT INTO devices (id, kind, device_name, public_key, token_hash, status, created_at)"
        " VALUES ('ext-1', 'extension', 'Chrome', 'pk1', 'hash-ext', 'active', 1000)"
    )
    conn.execute(
        "INSERT INTO devices (id, kind, device_name, public_key, token_hash, status, created_at)"
        " VALUES ('and-1', 'android', 'Celular', 'pk2', 'hash-and', 'active', 1000)"
    )
    conn.execute(
        "INSERT INTO pairing_sessions (id, token_hash, extension_device_id, android_device_id,"
        " status, created_at, expires_at, claimed_at)"
        " VALUES ('pair-1', 'hash-pair', 'ext-1', 'and-1', 'completed', 1000, 121000, 1500)"
    )
    conn.execute(
        "INSERT INTO communication_sessions (id, extension_device_id, android_device_id, status,"
        " created_at) VALUES ('link-1', 'ext-1', 'and-1', 'active', 2000)"
    )
    conn.execute(
        "INSERT INTO fill_requests (corr, link_id, requester_device_id, target_device_id,"
        " status, created_at, answered_at)"
        " VALUES ('corr-1', 'link-1', 'ext-1', 'and-1', 'answered', 3000, 3100)"
    )
    conn.commit()
    conn.close()

    db = Database(str(path))
    try:
        assert db.schema_version() == LATEST_SCHEMA_VERSION
        assert (
            db.query_one("SELECT device_name FROM devices WHERE id = 'ext-1'")["device_name"]
            == "Chrome"
        )
        assert (
            db.query_one("SELECT status FROM fill_requests WHERE corr = 'corr-1'")["status"]
            == "answered"
        )
        # Novas FKs e constraints agora estão ativas no banco migrado.
        with pytest.raises(sqlite3.IntegrityError):
            with db.transaction() as tx:
                tx.execute(
                    "INSERT INTO fill_requests (corr, link_id, requester_device_id,"
                    " target_device_id, status, created_at)"
                    " VALUES ('corr-2', 'link-1', 'inexistente', 'and-1', 'pending', 4000)"
                )
    finally:
        db.close()


def test_constraints_de_integridade_unicidade_e_check():
    db = Database(":memory:")
    try:
        with db.transaction() as tx:
            tx.execute(
                "INSERT INTO devices"
                " (id, kind, device_name, public_key, token_hash, status, created_at)"
                " VALUES ('ext-1', 'extension', 'Chrome', 'pk1', 'same-hash', 'active', 1000)"
            )
            tx.execute(
                "INSERT INTO devices"
                " (id, kind, device_name, public_key, token_hash, status, created_at)"
                " VALUES ('and-1', 'android', 'Phone', 'pk2', 'hash-2', 'active', 1000)"
            )

        # token_hash duplicado em devices é recusado
        with pytest.raises(sqlite3.IntegrityError):
            with db.transaction() as tx:
                tx.execute(
                    "INSERT INTO devices"
                    " (id, kind, device_name, public_key, token_hash, status, created_at)"
                    " VALUES"
                    " ('ext-2', 'extension', 'Chrome 2', 'pk3', 'same-hash', 'pending', 1000)"
                )

        # vínculo de um dispositivo consigo mesmo é recusado
        with pytest.raises(sqlite3.IntegrityError):
            with db.transaction() as tx:
                tx.execute(
                    "INSERT INTO communication_sessions"
                    " (id, extension_device_id, android_device_id, status, created_at)"
                    " VALUES ('link-self', 'ext-1', 'ext-1', 'active', 1000)"
                )

        # expiração anterior à criação é recusada
        with pytest.raises(sqlite3.IntegrityError):
            with db.transaction() as tx:
                tx.execute(
                    "INSERT INTO pairing_sessions"
                    " (id, token_hash, extension_device_id, status, created_at, expires_at)"
                    " VALUES ('p-bad', 'pair-h', 'ext-1', 'open', 2000, 1000)"
                )

        with db.transaction() as tx:
            tx.execute(
                "INSERT INTO communication_sessions"
                " (id, extension_device_id, android_device_id, status, created_at)"
                " VALUES ('link-1', 'ext-1', 'and-1', 'active', 1000)"
            )

        # fill_request com requester == target é recusado
        with pytest.raises(sqlite3.IntegrityError):
            with db.transaction() as tx:
                tx.execute(
                    "INSERT INTO fill_requests"
                    " (corr, link_id, requester_device_id, target_device_id, status, created_at)"
                    " VALUES ('c1', 'link-1', 'ext-1', 'ext-1', 'pending', 1000)"
                )
    finally:
        db.close()


def test_plano_de_execucao_usa_indices_nas_consultas_criticas():
    db = Database(":memory:")
    try:
        auth_plan = " ".join(
            r["detail"]
            for r in db.query(
                "EXPLAIN QUERY PLAN SELECT id FROM devices WHERE token_hash = ?", ("x",)
            )
        )
        assert "uq_devices_token_hash" in auth_plan

        fill_plan = " ".join(
            r["detail"]
            for r in db.query(
                "EXPLAIN QUERY PLAN SELECT corr FROM fill_requests"
                " WHERE status = 'pending' AND created_at < ?",
                (1000,),
            )
        )
        assert "idx_fill_requests_status_created" in fill_plan

        pair_plan = " ".join(
            r["detail"]
            for r in db.query(
                "EXPLAIN QUERY PLAN SELECT id FROM pairing_sessions"
                " WHERE status = 'open' AND expires_at < ?",
                (1000,),
            )
        )
        assert "idx_pairing_sessions_status_expires" in pair_plan

        comm_plan = " ".join(
            r["detail"]
            for r in db.query(
                "EXPLAIN QUERY PLAN SELECT * FROM communication_sessions"
                " WHERE status = 'active' AND extension_device_id = ?",
                ("e1",),
            )
        )
        assert "idx_comm_sessions_ext_status" in comm_plan
    finally:
        db.close()


def test_create_session_expira_sessoes_vencidas_preservando_410(client, ctx, clock):
    stale = create_session(client, KeyPair.generate())
    clock.advance(ctx.settings.pairing_ttl_ms + 1)
    # Criar nova sessão dispara expiração das sessões pendentes já vencidas.
    create_session(client, KeyPair.generate())
    row = ctx.db.query_one(
        "SELECT status FROM pairing_sessions WHERE id = ?", (stale["pairing_id"],)
    )
    assert row["status"] == "expired"
    dev = ctx.db.query_one("SELECT status FROM devices WHERE id = ?", (stale["device_id"],))
    assert dev["status"] == "revoked"
    late_claim = claim(client, stale, KeyPair.generate())
    assert late_claim.status_code == 410
    assert late_claim.json()["code"] == "pairing_expired"


def test_ciclo_de_vida_expira_e_expurga_registros_antigos(client, ctx, clock):
    # 1. Sessão open que vence sem ser reivindicada
    expired_open = create_session(client, KeyPair.generate())
    # 2. Sessão claimed que vence sem ser confirmada
    expired_claimed = create_session(client, KeyPair.generate())
    claim(client, expired_claimed, KeyPair.generate())
    # 3. Par ativo que deve ser preservado
    paired = pair(client)

    clock.advance(200_000)
    stats = expire_stale_records(
        ctx.db,
        now=clock.now,
        confirm_ttl_ms=ctx.settings.confirm_ttl_ms,
        fill_request_ttl_ms=ctx.settings.fill_request_ttl_ms,
    )
    assert stats.expired_open_pairings == 1
    assert stats.expired_claimed_pairings == 1

    # Mesmo já marcada como 'expired', a reivindicação continua devolvendo 410 pairing_expired.
    late_claim = claim(client, expired_open, KeyPair.generate())
    assert late_claim.status_code == 410
    assert late_claim.json()["code"] == "pairing_expired"

    # Dispositivos pendentes das sessões expiradas foram revogados, mas o vínculo ativo permanece.
    ext_status = ctx.db.query_one("SELECT status FROM devices WHERE id = ?", (paired.ext_id,))[
        "status"
    ]
    assert ext_status == "active"

    # Cria uma fill_request e avança além da janela de retenção (24h).
    with ctx.db.transaction() as tx:
        tx.execute(
            "INSERT INTO fill_requests (corr, link_id, requester_device_id, target_device_id,"
            " status, created_at, answered_at) VALUES (?, ?, ?, ?, 'answered', ?, ?)",
            ("c-old", paired.link_id, paired.ext_id, paired.android_id, clock.now, clock.now + 10),
        )

    clock.advance(ctx.settings.pairing_retention_ms + 1_000)
    purged = purge_expired_records(
        ctx.db,
        now=clock.now,
        pairing_retention_ms=ctx.settings.pairing_retention_ms,
        fill_request_retention_ms=ctx.settings.fill_request_retention_ms,
    )
    assert purged.deleted_fill_requests == 1
    assert purged.deleted_pairing_sessions == 3  # 2 expiradas + 1 completed antiga
    assert purged.deleted_devices >= 3  # dispositivos revogados sem referências restantes
    # Dispositivos do vínculo ativo continuam íntegros.
    assert ctx.db.query_one("SELECT id FROM devices WHERE id = ?", (paired.ext_id,)) is not None
    assert ctx.db.query_one("SELECT id FROM devices WHERE id = ?", (paired.android_id,)) is not None


def test_health_reporta_erro_se_banco_estiver_fechado(tmp_path: Path):
    db = Database(str(tmp_path / "health.sqlite3"))
    ctx = AppContext(settings=Settings(database_path=str(tmp_path / "health.sqlite3")), db=db)
    app = create_app(ctx)
    with TestClient(app) as client:
        assert client.get("/health").status_code == 200
        db.close()
        response = client.get("/health")
        assert response.status_code == 503
        assert response.json() == {"status": "error"}
