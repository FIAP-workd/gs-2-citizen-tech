"""Endpoint WebSocket do relay."""

from __future__ import annotations

from fastapi import APIRouter, WebSocket

from app.relay.handler import serve

router = APIRouter()


@router.websocket("/v1/ws")
async def relay_socket(ws: WebSocket) -> None:
    await serve(ws, ws.app.state.ctx)
