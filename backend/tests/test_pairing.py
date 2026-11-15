"""Pareamento: QR expirado, reutilizado, token errado e confirmação."""

from __future__ import annotations

from cs_protocol import KeyPair, b64e

from tests.helpers import auth, claim, confirm, create_session, pair, receive_type


def test_cria_sessao_com_tokens_que_nao_ficam_gravados_em_claro(client, db_path):
    session = create_session(client, KeyPair.generate())
    assert set(session) >= {"pairing_id", "pairing_token", "expires_at", "device_id"}
    raw = db_path.read_bytes()
    assert session["pairing_token"].encode() not in raw
    assert session["device_token"].encode() not in raw


def test_validade_do_qr_e_de_120_segundos(client, clock):
    session = create_session(client, KeyPair.generate())
    assert session["expires_at"] == clock.now + 120_000


def test_chave_publica_invalida_e_recusada(client):
    response = client.post(
        "/v1/pairing/sessions", json={"v": 1, "device_name": "x", "public_key": "curta"}
    )
    assert response.status_code == 400
    assert response.json() == {"v": 1, "code": "invalid_message"}


def test_versao_desconhecida_e_recusada(client):
    response = client.post(
        "/v1/pairing/sessions",
        json={"v": 2, "device_name": "x", "public_key": b64e(KeyPair.generate().public)},
    )
    assert response.status_code == 400


def test_qr_expirado_e_recusado(client, clock):
    session = create_session(client, KeyPair.generate())
    clock.advance(120_001)
    response = claim(client, session, KeyPair.generate())
    assert response.status_code == 410
    assert response.json()["code"] == "pairing_expired"


def test_qr_reutilizado_e_recusado(client):
    session = create_session(client, KeyPair.generate())
    assert claim(client, session, KeyPair.generate()).status_code == 200
    second = claim(client, session, KeyPair.generate())
    assert second.status_code == 409
    assert second.json()["code"] == "pairing_used"


def test_token_errado_nao_revela_nem_consome_a_sessao(client):
    session = create_session(client, KeyPair.generate())
    wrong = claim(client, session, KeyPair.generate(), token="A" * 43)
    assert wrong.status_code == 404
    assert claim(client, session, KeyPair.generate()).status_code == 200


def test_sessao_inexistente(client):
    fake = {"pairing_id": "00000000-0000-4000-8000-000000000000", "pairing_token": "B" * 43}
    assert claim(client, fake, KeyPair.generate()).status_code == 404


def test_reivindicacao_devolve_chave_publica_da_extensao(client):
    ext = KeyPair.generate()
    session = create_session(client, ext)
    body = claim(client, session, KeyPair.generate()).json()
    assert body["peer"]["public_key"] == b64e(ext.public)
    assert body["peer"]["device_name"] == "Chrome — Teste"


def test_confirmacao_exige_token_do_android(client):
    session = create_session(client, KeyPair.generate())
    claim(client, session, KeyPair.generate())
    response = confirm(client, session["pairing_id"], session["device_token"])
    assert response.status_code == 401


def test_confirmacao_cria_vinculo_e_nao_pode_ser_repetida(client):
    session = create_session(client, KeyPair.generate())
    body = claim(client, session, KeyPair.generate()).json()
    first = confirm(client, session["pairing_id"], body["device_token"])
    assert first.status_code == 200
    assert "link_id" in first.json()
    assert confirm(client, session["pairing_id"], body["device_token"]).status_code == 409


def test_confirmacao_tardia_e_recusada(client, clock):
    session = create_session(client, KeyPair.generate())
    body = claim(client, session, KeyPair.generate()).json()
    clock.advance(180_001)
    assert confirm(client, session["pairing_id"], body["device_token"]).status_code == 410


def test_recusa_no_celular_cancela_e_revoga_a_extensao(client):
    session = create_session(client, KeyPair.generate())
    body = claim(client, session, KeyPair.generate()).json()
    response = confirm(client, session["pairing_id"], body["device_token"], accepted=False)
    assert response.json() == {"v": 1, "cancelled": True}
    with client.websocket_connect("/v1/ws") as ws:
        assert auth(ws, session["device_id"], session["device_token"])["code"] == "device_revoked"


def test_extensao_recebe_eventos_de_pareamento(client):
    ext = KeyPair.generate()
    session = create_session(client, ext)
    with client.websocket_connect("/v1/ws") as ws:
        assert auth(ws, session["device_id"], session["device_token"])["type"] == "auth.ok"
        android = KeyPair.generate()
        body = claim(client, session, android).json()
        claimed = receive_type(ws, "pairing.claimed")
        assert claimed["peer"]["public_key"] == b64e(android.public)
        confirmed = confirm(client, session["pairing_id"], body["device_token"]).json()
        completed = receive_type(ws, "pairing.completed")
        assert completed["link_id"] == confirmed["link_id"]


def test_rate_limit_de_pareamento(client, ctx):
    ctx.pairing_limiter.limit = 3
    keys = KeyPair.generate()
    for _ in range(3):
        create_session(client, keys)
    response = client.post(
        "/v1/pairing/sessions", json={"v": 1, "device_name": "x", "public_key": b64e(keys.public)}
    )
    assert response.status_code == 429


def test_pareamento_completo(client):
    paired = pair(client)
    assert paired.link_id
