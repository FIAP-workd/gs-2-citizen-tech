"""Protocolo WebSocket do relay (contrato §4).

O relay só lê o cabeçalho do envelope. `nonce` e `ciphertext` são
encaminhados sem serem gravados nem registrados em log.
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Any

from starlette.websockets import WebSocket, WebSocketDisconnect

from app.config import PROTOCOL_VERSION
from app.context import AppContext
from app.devices.service import Device, authenticate
from app.errors import RelayError
from app.security.tokens import b64url_decode, is_uuid
from app.sessions.service import active_links_for, get_link, revoke_link, track_fill_request

log = logging.getLogger("chave_segura.relay")

MAX_CORR_LEN = 64
MAX_CIPHERTEXT_BYTES = 12 * 1024
RELAY_FIELDS = ("msg_id", "link_id", "from", "to", "ts", "seq", "corr", "nonce", "ciphertext")


def _short(device_id: str) -> str:
    """Identificador pseudônimo curto para logs."""
    return device_id[:8]


def error_frame(code: str, ref: str | None = None) -> dict[str, Any]:
    frame: dict[str, Any] = {"v": PROTOCOL_VERSION, "type": "error", "code": code}
    if ref:
        frame["ref"] = ref
    return frame


async def _send(ws: WebSocket, frame: dict[str, Any]) -> None:
    await ws.send_text(json.dumps(frame, separators=(",", ":")))


def parse_frame(raw: str, max_bytes: int) -> dict[str, Any]:
    if len(raw.encode("utf-8")) > max_bytes:
        raise RelayError("invalid_message")
    try:
        frame = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise RelayError("invalid_message") from exc
    if not isinstance(frame, dict) or not isinstance(frame.get("type"), str):
        raise RelayError("invalid_message")
    if frame.get("v") != PROTOCOL_VERSION:
        raise RelayError("unsupported_version")
    return frame


def validate_relay(frame: dict[str, Any]) -> dict[str, Any]:
    """Confere o formato do envelope e devolve só os campos conhecidos."""
    for name in ("msg_id", "link_id", "from", "to"):
        if not is_uuid(frame.get(name)):
            raise RelayError("invalid_message")
    for name in ("ts", "seq"):
        value = frame.get(name)
        if not isinstance(value, int) or isinstance(value, bool) or value < 0:
            raise RelayError("invalid_message")
    corr = frame.get("corr", "")
    if not isinstance(corr, str) or len(corr) > MAX_CORR_LEN:
        raise RelayError("invalid_message")
    try:
        nonce = b64url_decode(frame.get("nonce"))
        ciphertext = b64url_decode(frame.get("ciphertext"))
    except ValueError as exc:
        raise RelayError("invalid_message") from exc
    if len(nonce) != 24 or not 16 <= len(ciphertext) <= MAX_CIPHERTEXT_BYTES:
        raise RelayError("invalid_message")
    clean = {"v": PROTOCOL_VERSION, "type": "relay"}
    clean.update({name: frame.get(name, "") for name in RELAY_FIELDS})
    return clean


class Session:
    """Uma conexão autenticada de um dispositivo."""

    def __init__(self, ws: WebSocket, ctx: AppContext, device: Device) -> None:
        self.ws = ws
        self.ctx = ctx
        self.device = device

    async def notify_presence(self, online: bool) -> None:
        for link in active_links_for(self.ctx.db, self.device.id):
            peer = link.peer_of(self.device.id)
            if peer is None:
                continue
            frame = {"v": PROTOCOL_VERSION, "type": "peer.status", "link_id": link.id}
            await self.ctx.hub.send(peer, {**frame, "online": online})
            if online:
                await _send(self.ws, {**frame, "online": self.ctx.hub.is_online(peer)})

    async def handle(self, frame: dict[str, Any]) -> None:
        kind = frame["type"]
        if kind == "ping":
            await _send(self.ws, {"v": PROTOCOL_VERSION, "type": "pong"})
        elif kind == "relay":
            await self.handle_relay(frame)
        elif kind == "link.revoke":
            await self.handle_revoke(frame)
        else:
            raise RelayError("invalid_message")

    async def handle_relay(self, frame: dict[str, Any]) -> None:
        ref = frame.get("msg_id") if isinstance(frame.get("msg_id"), str) else None
        try:
            envelope = validate_relay(frame)
        except RelayError as exc:
            await _send(self.ws, error_frame(exc.code, ref))
            return
        ctx = self.ctx
        if envelope["from"] != self.device.id:
            await _send(self.ws, error_frame("invalid_message", ref))
            return
        link = get_link(ctx.db, envelope["link_id"])
        if link is None or link.peer_of(self.device.id) != envelope["to"]:
            await _send(self.ws, error_frame("invalid_message", ref))
            return
        if link.status != "active":
            await _send(self.ws, error_frame("device_revoked", ref))
            return
        if not ctx.relay_limiter.allow(self.device.id):
            log.warning("relay limitado device=%s", _short(self.device.id))
            await _send(self.ws, error_frame("rate_limited", ref))
            return
        track_fill_request(
            ctx.db,
            corr=envelope["corr"],
            link_id=link.id,
            sender=self.device.id,
            recipient=envelope["to"],
            now=ctx.clock(),
            ttl_ms=ctx.settings.fill_request_ttl_ms,
        )
        delivered = await ctx.hub.send(envelope["to"], envelope)
        log.info(
            "relay from=%s to=%s entregue=%s",
            _short(self.device.id),
            _short(envelope["to"]),
            delivered,
        )
        if not delivered:
            await _send(self.ws, error_frame("peer_offline", ref))

    async def handle_revoke(self, frame: dict[str, Any]) -> None:
        link_id = frame.get("link_id")
        link = get_link(self.ctx.db, link_id) if is_uuid(link_id) else None
        if link is None or link.peer_of(self.device.id) is None:
            await _send(self.ws, error_frame("invalid_message"))
            return
        revoke_link(self.ctx.db, link.id, self.ctx.clock())
        log.info("link revogado por device=%s", _short(self.device.id))
        notice = {"v": PROTOCOL_VERSION, "type": "link.revoked", "link_id": link.id}
        for device_id in link.endpoints():
            await self.ctx.hub.send(device_id, notice)
        for device_id in link.endpoints():
            await self.ctx.hub.disconnect(device_id)


async def serve(ws: WebSocket, ctx: AppContext) -> None:
    await ws.accept()
    try:
        raw = await asyncio.wait_for(ws.receive_text(), timeout=ctx.settings.auth_timeout_s)
        frame = parse_frame(raw, ctx.settings.max_frame_bytes)
        if frame["type"] != "auth":
            raise RelayError("invalid_message")
        device = authenticate(
            ctx.db, frame.get("device_id"), frame.get("device_token"), ctx.clock()
        )
    except (TimeoutError, WebSocketDisconnect, KeyError):
        await ws.close(4001)
        return
    except RelayError as exc:
        await _send(ws, error_frame(exc.code))
        await ws.close(4001)
        return

    session = Session(ws, ctx, device)
    await ctx.hub.register(device.id, ws)
    log.info("conectado device=%s kind=%s", _short(device.id), device.kind)
    try:
        links = [link.id for link in active_links_for(ctx.db, device.id)]
        await _send(ws, {"v": PROTOCOL_VERSION, "type": "auth.ok", "links": links})
        await session.notify_presence(online=True)
        while True:
            message = await ws.receive()
            if message["type"] == "websocket.disconnect":
                break
            text = message.get("text")
            if text is None:
                await _send(ws, error_frame("invalid_message"))
                continue
            try:
                await session.handle(parse_frame(text, ctx.settings.max_frame_bytes))
            except RelayError as exc:
                await _send(ws, error_frame(exc.code))
    except (WebSocketDisconnect, RuntimeError):
        pass
    finally:
        await ctx.hub.unregister(device.id, ws)
        log.info("desconectado device=%s", _short(device.id))
        try:
            await session.notify_presence(online=False)
        except Exception:  # noqa: BLE001 - o banco pode já estar fechado no encerramento
            log.debug("falha ao avisar presença")
