"""Tokens aleatórios e validação de campos do contrato."""

from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
import secrets
import uuid


def new_token() -> str:
    """32 bytes aleatórios em base64url sem padding (ADR 0001 §6)."""
    return secrets.token_urlsafe(32)


def hash_token(token: str) -> str:
    """O servidor guarda apenas o SHA-256 dos tokens."""
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def token_matches(token: str, stored_hash: str) -> bool:
    return hmac.compare_digest(hash_token(token), stored_hash)


def b64url_decode(value: str) -> bytes:
    """Decodifica base64url sem padding; levanta ValueError se inválido."""
    if not isinstance(value, str) or not value:
        raise ValueError("vazio")
    if any(
        c not in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_" for c in value
    ):
        raise ValueError("caractere inválido")
    try:
        return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    except (binascii.Error, ValueError) as exc:
        raise ValueError("base64url inválido") from exc


def is_public_key(value: object) -> bool:
    """Chave pública X25519: 32 bytes em base64url."""
    if not isinstance(value, str):
        return False
    try:
        return len(b64url_decode(value)) == 32
    except ValueError:
        return False


def is_uuid(value: object) -> bool:
    if not isinstance(value, str):
        return False
    try:
        return str(uuid.UUID(value)) == value.lower()
    except ValueError:
        return False


def new_id() -> str:
    return str(uuid.uuid4())
