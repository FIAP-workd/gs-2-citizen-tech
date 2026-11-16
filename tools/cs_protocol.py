"""Implementação de referência do canal E2E do Chave Segura em Python (PyNaCl).

Serve para:
- os testes de integração do backend (o relay nunca importa este módulo);
- o "celular simulado" (`fake_phone.py`), que permite testar a extensão sem o
  aplicativo Android.

Segue exatamente docs/message-contract.md e o ADR 0001: crypto_kx para as
chaves de sessão, XChaCha20-Poly1305 com o cabeçalho como AAD, e
anti-replay por msg_id, janela de ts e seq crescente.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import time
import uuid
from dataclasses import dataclass, field
from typing import Any

from nacl import bindings as sodium

PROTOCOL_VERSION = 1
TS_WINDOW_MS = 60_000


def b64e(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def b64d(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def now_ms() -> int:
    return int(time.time() * 1000)


@dataclass(frozen=True)
class KeyPair:
    public: bytes
    secret: bytes

    @classmethod
    def generate(cls) -> KeyPair:
        pk, sk = sodium.crypto_kx_keypair()
        return cls(pk, sk)


def sas_code(extension_pk: bytes, android_pk: bytes) -> str:
    """Código curto de verificação: 6 dígitos (ADR 0001 §4, contrato §3.5)."""
    digest = hashlib.blake2b(
        b"chave-segura/sas/v1" + extension_pk + android_pk, digest_size=32
    ).digest()
    return f"{int.from_bytes(digest[:4], 'big') % 1_000_000:06d}"


def session_keys(own: KeyPair, peer_public: bytes, *, is_client: bool) -> tuple[bytes, bytes]:
    """(rx, tx). A extensão é o cliente; o Android é o servidor."""
    if is_client:
        return sodium.crypto_kx_client_session_keys(own.public, own.secret, peer_public)
    return sodium.crypto_kx_server_session_keys(own.public, own.secret, peer_public)


def aad_for(header: dict[str, Any]) -> bytes:
    return (
        f"cs/{PROTOCOL_VERSION}|{header['msg_id']}|{header['link_id']}|{header['from']}|"
        f"{header['to']}|{header['ts']}|{header['seq']}|{header.get('corr', '')}"
    ).encode("ascii")


class ReplayError(Exception):
    pass


@dataclass
class Channel:
    """Canal com um par: cifra, decifra e aplica o anti-replay."""

    link_id: str
    own_device_id: str
    peer_device_id: str
    rx: bytes
    tx: bytes
    # Semeado com o relógio: continua crescente mesmo depois de reiniciar.
    seq: int = field(default_factory=now_ms)
    last_peer_seq: int = -1
    seen: dict[str, int] = field(default_factory=dict)

    def seal(self, payload: dict[str, Any], corr: str = "", ts: int | None = None) -> dict:
        self.seq += 1
        header = {
            "v": PROTOCOL_VERSION,
            "type": "relay",
            "msg_id": str(uuid.uuid4()),
            "link_id": self.link_id,
            "from": self.own_device_id,
            "to": self.peer_device_id,
            "ts": now_ms() if ts is None else ts,
            "seq": self.seq,
            "corr": corr,
        }
        nonce = os.urandom(24)
        plaintext = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        ciphertext = sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(
            plaintext, aad_for(header), nonce, self.tx
        )
        return {**header, "nonce": b64e(nonce), "ciphertext": b64e(ciphertext)}

    def open(self, envelope: dict[str, Any], now: int | None = None) -> dict[str, Any]:
        now = now_ms() if now is None else now
        if envelope.get("from") != self.peer_device_id or envelope.get("to") != self.own_device_id:
            raise ReplayError("remetente inesperado")
        if envelope.get("link_id") != self.link_id:
            raise ReplayError("vínculo inesperado")
        if abs(now - int(envelope["ts"])) > TS_WINDOW_MS:
            raise ReplayError("fora da janela de tempo")
        if envelope["msg_id"] in self.seen:
            raise ReplayError("msg_id repetido")
        if int(envelope["seq"]) <= self.last_peer_seq:
            raise ReplayError("seq não crescente")
        # Lança nacl.exceptions.CryptoError se cabeçalho ou payload foram alterados.
        plaintext = sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(
            b64d(envelope["ciphertext"]), aad_for(envelope), b64d(envelope["nonce"]), self.rx
        )
        self.last_peer_seq = int(envelope["seq"])
        self.seen = {k: v for k, v in self.seen.items() if now - v <= TS_WINDOW_MS}
        self.seen[envelope["msg_id"]] = now
        return json.loads(plaintext)
