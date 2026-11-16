from __future__ import annotations

from dataclasses import dataclass

from cs_protocol import Channel, KeyPair, b64e, session_keys
from fastapi.testclient import TestClient


@dataclass
class Paired:
    link_id: str
    ext_id: str
    ext_token: str
    ext_keys: KeyPair
    android_id: str
    android_token: str
    android_keys: KeyPair

    def ext_channel(self) -> Channel:
        rx, tx = session_keys(self.ext_keys, self.android_keys.public, is_client=True)
        return Channel(self.link_id, self.ext_id, self.android_id, rx, tx)

    def android_channel(self) -> Channel:
        rx, tx = session_keys(self.android_keys, self.ext_keys.public, is_client=False)
        return Channel(self.link_id, self.android_id, self.ext_id, rx, tx)


def create_session(client: TestClient, keys: KeyPair, name: str = "Chrome — Teste") -> dict:
    response = client.post(
        "/v1/pairing/sessions", json={"v": 1, "device_name": name, "public_key": b64e(keys.public)}
    )
    assert response.status_code == 201, response.text
    return response.json()


def claim(client: TestClient, session: dict, keys: KeyPair, token: str | None = None):
    return client.post(
        f"/v1/pairing/sessions/{session['pairing_id']}/claim",
        json={
            "v": 1,
            "pairing_token": token or session["pairing_token"],
            "device_name": "Celular de Teste",
            "public_key": b64e(keys.public),
        },
    )


def confirm(client: TestClient, pairing_id: str, android_token: str, accepted: bool = True):
    return client.post(
        f"/v1/pairing/sessions/{pairing_id}/confirm",
        json={"v": 1, "accepted": accepted},
        headers={"Authorization": f"Bearer {android_token}"},
    )


def pair(client: TestClient) -> Paired:
    ext_keys, android_keys = KeyPair.generate(), KeyPair.generate()
    session = create_session(client, ext_keys)
    claimed = claim(client, session, android_keys)
    assert claimed.status_code == 200, claimed.text
    body = claimed.json()
    confirmed = confirm(client, session["pairing_id"], body["device_token"])
    assert confirmed.status_code == 200, confirmed.text
    return Paired(
        link_id=confirmed.json()["link_id"],
        ext_id=session["device_id"],
        ext_token=session["device_token"],
        ext_keys=ext_keys,
        android_id=body["device_id"],
        android_token=body["device_token"],
        android_keys=android_keys,
    )


def auth(ws, device_id: str, token: str) -> dict:
    ws.send_json({"v": 1, "type": "auth", "device_id": device_id, "device_token": token})
    return ws.receive_json()


def receive_type(ws, wanted: str, limit: int = 5) -> dict:
    """Lê frames até encontrar o tipo desejado (ignora avisos de presença)."""
    for _ in range(limit):
        frame = ws.receive_json()
        if frame["type"] == wanted:
            return frame
    raise AssertionError(f"frame {wanted} não recebido")
