"""Rotas REST de pareamento (contrato §3)."""

from __future__ import annotations

from typing import Annotated, Literal

from fastapi import APIRouter, Header, Request
from pydantic import BaseModel, Field, field_validator

from app.config import PROTOCOL_VERSION
from app.context import AppContext
from app.devices.service import authenticate_bearer
from app.errors import RelayError
from app.pairing import service
from app.security.tokens import is_public_key, is_uuid

router = APIRouter(prefix="/v1/pairing", tags=["pairing"])


class _Base(BaseModel):
    v: Literal[1]


class _WithKey(_Base):
    device_name: str = Field(min_length=1, max_length=64)
    public_key: str

    @field_validator("public_key")
    @classmethod
    def _key(cls, value: str) -> str:
        if not is_public_key(value):
            raise ValueError("chave pública X25519 inválida")
        return value

    @field_validator("device_name")
    @classmethod
    def _name(cls, value: str) -> str:
        cleaned = " ".join(value.split())
        if not cleaned:
            raise ValueError("nome vazio")
        return cleaned


class CreateSessionBody(_WithKey):
    pass


class ClaimBody(_WithKey):
    pairing_token: str = Field(min_length=16, max_length=128)


class ConfirmBody(_Base):
    accepted: bool


def _ctx(request: Request) -> AppContext:
    return request.app.state.ctx


def _limit(request: Request, ctx: AppContext) -> None:
    client = request.client.host if request.client else "desconhecido"
    if not ctx.pairing_limiter.allow(client):
        raise RelayError("rate_limited", 429)


def _check_id(pairing_id: str) -> None:
    if not is_uuid(pairing_id):
        raise RelayError("pairing_not_found", 404)


@router.post("/sessions", status_code=201)
async def create_session(body: CreateSessionBody, request: Request) -> dict:
    ctx = _ctx(request)
    _limit(request, ctx)
    created = service.create_session(
        ctx.db,
        ctx.settings,
        device_name=body.device_name,
        public_key=body.public_key,
        now=ctx.clock(),
    )
    return {
        "v": PROTOCOL_VERSION,
        "pairing_id": created.pairing_id,
        "pairing_token": created.pairing_token,
        "expires_at": created.expires_at,
        "device_id": created.device.id,
        "device_token": created.device_token,
    }


@router.post("/sessions/{pairing_id}/claim")
async def claim_session(pairing_id: str, body: ClaimBody, request: Request) -> dict:
    ctx = _ctx(request)
    _limit(request, ctx)
    _check_id(pairing_id)
    claimed = service.claim_session(
        ctx.db,
        pairing_id=pairing_id,
        pairing_token=body.pairing_token,
        device_name=body.device_name,
        public_key=body.public_key,
        now=ctx.clock(),
    )
    await ctx.hub.send(
        claimed.extension.id,
        {
            "v": PROTOCOL_VERSION,
            "type": "pairing.claimed",
            "pairing_id": pairing_id,
            "peer": claimed.android.peer_view(),
        },
    )
    return {
        "v": PROTOCOL_VERSION,
        "device_id": claimed.android.id,
        "device_token": claimed.android_token,
        "peer": claimed.extension.peer_view(),
    }


@router.post("/sessions/{pairing_id}/confirm")
async def confirm_session(
    pairing_id: str,
    body: ConfirmBody,
    request: Request,
    authorization: Annotated[str | None, Header()] = None,
) -> dict:
    ctx = _ctx(request)
    _check_id(pairing_id)
    if not authorization or not authorization.startswith("Bearer "):
        raise RelayError("auth_failed", 401)
    token = authorization.removeprefix("Bearer ").strip()
    android = authenticate_bearer(ctx.db, token, ctx.clock())
    if android.kind != "android":
        raise RelayError("auth_failed", 401)
    result = service.confirm_session(
        ctx.db,
        ctx.settings,
        pairing_id=pairing_id,
        android_device_id=android.id,
        accepted=body.accepted,
        now=ctx.clock(),
    )
    if result.accepted:
        await ctx.hub.send(
            result.extension_device_id,
            {
                "v": PROTOCOL_VERSION,
                "type": "pairing.completed",
                "pairing_id": pairing_id,
                "link_id": result.link_id,
            },
        )
        return {"v": PROTOCOL_VERSION, "link_id": result.link_id}
    await ctx.hub.send(
        result.extension_device_id,
        {"v": PROTOCOL_VERSION, "type": "pairing.cancelled", "pairing_id": pairing_id},
    )
    await ctx.hub.disconnect(result.extension_device_id)
    return {"v": PROTOCOL_VERSION, "cancelled": True}
