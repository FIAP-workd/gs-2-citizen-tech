"""Gera docs/test-vectors/e2e-v1.json: vetores fixos do canal E2E.

Os testes da extensão (TypeScript) leem este arquivo e precisam chegar aos
mesmos valores que a implementação de referência em Python. Qualquer
implementação nova (ex.: Android) pode usar os mesmos vetores.

Uso: python tools/gen_test_vectors.py
"""

from __future__ import annotations

import json
from pathlib import Path

from cs_protocol import KeyPair, aad_for, b64e, sas_code, session_keys
from nacl import bindings as sodium

OUT = Path(__file__).resolve().parents[1] / "docs" / "test-vectors" / "e2e-v1.json"


def main() -> None:
    ext_seed = bytes(range(32))
    android_seed = bytes(range(32, 64))
    ext = KeyPair(*sodium.crypto_kx_seed_keypair(ext_seed))
    android = KeyPair(*sodium.crypto_kx_seed_keypair(android_seed))
    ext_rx, ext_tx = session_keys(ext, android.public, is_client=True)

    header = {
        "msg_id": "0d6b7a54-3f0e-4a52-8a51-0f6b7e3b2c90",
        "link_id": "e7f0a1b2-c3d4-4e5f-8a6b-7c8d9e0f1a2b",
        "from": "b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d5e",
        "to": "a9d8c7b6-a5f4-4e3d-8c2b-1a0f9e8d7c6b",
        "ts": 1763150400000,
        "seq": 7,
        "corr": "c3a1e0b4-9f0a-4c5e-8d1b-0a2b3c4d5e6f",
    }
    payload = {
        "kind": "fill.request",
        "body": {
            "request_id": header["corr"],
            "origin": "https://accounts.exemplo.com",
            "hostname": "accounts.exemplo.com",
            "top_level": True,
        },
    }
    plaintext = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    nonce = bytes(range(100, 124))
    ciphertext = sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(
        plaintext, aad_for(header), nonce, ext_tx
    )

    vectors = {
        "description": "Vetores do canal E2E v1 (gerados por tools/gen_test_vectors.py)",
        "extension": {"seed": b64e(ext_seed), "public_key": b64e(ext.public)},
        "android": {"seed": b64e(android_seed), "public_key": b64e(android.public)},
        "sas": sas_code(ext.public, android.public),
        "extension_session": {"rx": b64e(ext_rx), "tx": b64e(ext_tx)},
        "aad": aad_for(header).decode("ascii"),
        "envelope": {
            "v": 1,
            "type": "relay",
            **header,
            "nonce": b64e(nonce),
            "ciphertext": b64e(ciphertext),
        },
        "plaintext": plaintext.decode("utf-8"),
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(vectors, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"gravado {OUT}")


if __name__ == "__main__":
    main()
