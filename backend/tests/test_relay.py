"""Relay WebSocket e canal E2E: o servidor encaminha sem conseguir ler."""

from __future__ import annotations

import logging

import pytest
from cs_protocol import ReplayError
from nacl.exceptions import CryptoError

from tests.helpers import auth, pair, receive_type

SECRET_PASSWORD = "Senha-Ficticia-Para-Teste-123!"
HOSTNAME = "login.exemplo-teste.com"


def fill_request(request_id: str) -> dict:
    return {
        "kind": "fill.request",
        "body": {
            "request_id": request_id,
            "origin": f"https://{HOSTNAME}",
            "hostname": HOSTNAME,
            "top_level": True,
        },
    }


def fill_response(request_id: str) -> dict:
    return {
        "kind": "fill.response",
        "body": {
            "request_id": request_id,
            "result": "approved",
            "mode": "confirmation",
            "hostname": HOSTNAME,
            "credential": {"username": "ana@exemplo.com", "password": SECRET_PASSWORD},
            "expires_at": 0,
        },
    }


def test_autenticacao_com_token_invalido_e_recusada(client):
    paired = pair(client)
    with client.websocket_connect("/v1/ws") as ws:
        assert auth(ws, paired.ext_id, "token-errado")["code"] == "auth_failed"


def test_primeiro_frame_precisa_ser_auth(client):
    with client.websocket_connect("/v1/ws") as ws:
        ws.send_json({"v": 1, "type": "ping"})
        assert ws.receive_json()["code"] == "invalid_message"


def test_auth_ok_lista_vinculos(client):
    paired = pair(client)
    with client.websocket_connect("/v1/ws") as ws:
        assert auth(ws, paired.ext_id, paired.ext_token) == {
            "v": 1,
            "type": "auth.ok",
            "links": [paired.link_id],
        }


def test_fluxo_completo_e2e_sem_vazar_dados_ao_servidor(client, ctx, db_path, caplog):
    caplog.set_level(logging.DEBUG)
    paired = pair(client)
    ext, android = paired.ext_channel(), paired.android_channel()
    request_id = "c3a1e0b4-9f0a-4c5e-8d1b-0a2b3c4d5e6f"

    with (
        client.websocket_connect("/v1/ws") as ws_ext,
        client.websocket_connect("/v1/ws") as ws_android,
    ):
        auth(ws_ext, paired.ext_id, paired.ext_token)
        auth(ws_android, paired.android_id, paired.android_token)

        request = ext.seal(fill_request(request_id), corr=request_id)
        ws_ext.send_json(request)
        received = receive_type(ws_android, "relay")
        assert android.open(received)["body"]["hostname"] == HOSTNAME

        response = android.seal(fill_response(request_id), corr=request_id)
        ws_android.send_json(response)
        answer = ext.open(receive_type(ws_ext, "relay"))
        assert answer["body"]["credential"]["password"] == SECRET_PASSWORD

    # O que passou pelo servidor não contém domínio, usuário nem senha.
    raw_db = db_path.read_bytes()
    for secret in (SECRET_PASSWORD, HOSTNAME, "ana@exemplo.com"):
        assert secret.encode() not in raw_db
        assert secret not in caplog.text
        assert secret not in str(request) and secret not in str(response)
    assert request["ciphertext"] not in caplog.text
    row = ctx.db.query_one("SELECT status FROM fill_requests WHERE corr = ?", (request_id,))
    assert row["status"] == "answered"


def test_servidor_que_altera_o_cabecalho_e_detectado(client):
    paired = pair(client)
    ext, android = paired.ext_channel(), paired.android_channel()
    envelope = ext.seal(fill_request("r1"), corr="r1")
    tampered = {**envelope, "corr": "outro"}
    with pytest.raises(CryptoError):
        android.open(tampered)


def test_servidor_que_altera_o_payload_e_detectado(client):
    paired = pair(client)
    ext, android = paired.ext_channel(), paired.android_channel()
    envelope = ext.seal(fill_request("r1"))
    last = envelope["ciphertext"][-1]
    envelope["ciphertext"] = envelope["ciphertext"][:-1] + ("A" if last != "A" else "B")
    with pytest.raises(CryptoError):
        android.open(envelope)


def test_mensagem_repetida_e_recusada(client):
    paired = pair(client)
    ext, android = paired.ext_channel(), paired.android_channel()
    envelope = ext.seal(fill_request("r1"))
    android.open(envelope)
    with pytest.raises(ReplayError):
        android.open(envelope)


def test_mensagem_antiga_e_recusada(client):
    paired = pair(client)
    ext, android = paired.ext_channel(), paired.android_channel()
    envelope = ext.seal(fill_request("r1"), ts=1_000)
    with pytest.raises(ReplayError):
        android.open(envelope)


def test_reflexao_para_o_proprio_remetente_falha(client):
    """Chaves direcionais: o que a extensão cifra não é aceito como vindo do Android."""
    paired = pair(client)
    ext = paired.ext_channel()
    envelope = ext.seal(fill_request("r1"))
    reflected = {**envelope, "from": paired.android_id, "to": paired.ext_id}
    with pytest.raises(CryptoError):
        paired.ext_channel().open(reflected)


def test_remetente_falsificado_e_recusado(client):
    paired = pair(client)
    android = paired.android_channel()
    with client.websocket_connect("/v1/ws") as ws:
        auth(ws, paired.ext_id, paired.ext_token)
        forged = android.seal(fill_response("r1"))  # from = android, enviado pela extensão
        ws.send_json(forged)
        assert receive_type(ws, "error")["code"] == "invalid_message"


def test_destinatario_offline(client):
    paired = pair(client)
    with client.websocket_connect("/v1/ws") as ws:
        auth(ws, paired.ext_id, paired.ext_token)
        envelope = paired.ext_channel().seal(fill_request("r1"), corr="r1")
        ws.send_json(envelope)
        error = receive_type(ws, "error")
        assert error == {"v": 1, "type": "error", "code": "peer_offline", "ref": envelope["msg_id"]}


def test_presenca_do_par(client):
    paired = pair(client)
    with client.websocket_connect("/v1/ws") as ws_ext:
        auth(ws_ext, paired.ext_id, paired.ext_token)
        assert receive_type(ws_ext, "peer.status")["online"] is False
        with client.websocket_connect("/v1/ws") as ws_android:
            auth(ws_android, paired.android_id, paired.android_token)
            status = receive_type(ws_ext, "peer.status")
            assert status == {
                "v": 1,
                "type": "peer.status",
                "link_id": paired.link_id,
                "online": True,
            }


def test_dispositivo_revogado_nao_volta_a_conectar(client):
    paired = pair(client)
    with (
        client.websocket_connect("/v1/ws") as ws_ext,
        client.websocket_connect("/v1/ws") as ws_android,
    ):
        auth(ws_ext, paired.ext_id, paired.ext_token)
        auth(ws_android, paired.android_id, paired.android_token)
        ws_android.send_json({"v": 1, "type": "link.revoke", "link_id": paired.link_id})
        assert receive_type(ws_ext, "link.revoked")["link_id"] == paired.link_id
    with client.websocket_connect("/v1/ws") as ws:
        assert auth(ws, paired.ext_id, paired.ext_token)["code"] == "device_revoked"


def test_frame_grande_demais_e_recusado(client):
    paired = pair(client)
    with client.websocket_connect("/v1/ws") as ws:
        auth(ws, paired.ext_id, paired.ext_token)
        ws.send_text('{"v":1,"type":"ping","x":"' + "a" * 17_000 + '"}')
        assert receive_type(ws, "error")["code"] == "invalid_message"


def test_versao_desconhecida_no_websocket(client):
    paired = pair(client)
    with client.websocket_connect("/v1/ws") as ws:
        auth(ws, paired.ext_id, paired.ext_token)
        ws.send_json({"v": 9, "type": "ping"})
        assert receive_type(ws, "error")["code"] == "unsupported_version"


def test_ping(client):
    paired = pair(client)
    with client.websocket_connect("/v1/ws") as ws:
        auth(ws, paired.ext_id, paired.ext_token)
        ws.send_json({"v": 1, "type": "ping"})
        assert receive_type(ws, "pong") == {"v": 1, "type": "pong"}


def test_rate_limit_do_relay(client):
    paired = pair(client)
    ext = paired.ext_channel()
    with client.websocket_connect("/v1/ws") as ws:
        auth(ws, paired.ext_id, paired.ext_token)
        codes = []
        for i in range(7):
            ws.send_json(ext.seal(fill_request(f"r{i}")))
            codes.append(receive_type(ws, "error")["code"])
        # Rajada de 5 (conftest): as primeiras são "peer_offline", depois "rate_limited".
        assert codes[:5] == ["peer_offline"] * 5
        assert codes[5:] == ["rate_limited"] * 2


def test_corr_repetido_em_outro_vinculo_nao_se_mistura(client, ctx):
    first, second = pair(client), pair(client)
    for paired in (first, second):
        ext, android = paired.ext_channel(), paired.android_channel()
        with (
            client.websocket_connect("/v1/ws") as ws_ext,
            client.websocket_connect("/v1/ws") as ws_android,
        ):
            auth(ws_ext, paired.ext_id, paired.ext_token)
            auth(ws_android, paired.android_id, paired.android_token)
            ws_ext.send_json(ext.seal(fill_request("mesmo"), corr="mesmo"))
            android.open(receive_type(ws_android, "relay"))
            ws_android.send_json(android.seal(fill_response("mesmo"), corr="mesmo"))
            receive_type(ws_ext, "relay")
    rows = ctx.db.query("SELECT status FROM fill_requests WHERE corr = 'mesmo'")
    assert [r["status"] for r in rows] == ["answered", "answered"]
