"""Aplicação FastAPI do relay."""

from __future__ import annotations

import logging

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.api.health import router as health_router
from app.api.pairing import router as pairing_router
from app.api.ws import router as ws_router
from app.config import PROTOCOL_VERSION, Settings
from app.context import AppContext
from app.errors import RelayError
from app.persistence.db import Database

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")


def create_app(ctx: AppContext | None = None) -> FastAPI:
    if ctx is None:
        settings = Settings.from_env()
        ctx = AppContext(settings=settings, db=Database(settings.database_path))

    app = FastAPI(
        title="Chave Segura — Relay",
        version="1.0.0",
        # Documentação interativa desligada; o relay não é uma API pública.
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    app.state.ctx = ctx

    # A extensão chama a API REST a partir do service worker (origem chrome-extension://).
    app.add_middleware(
        CORSMiddleware,
        allow_origin_regex=r"^chrome-extension://[a-p]{32}$",
        allow_methods=["POST"],
        allow_headers=["Authorization", "Content-Type"],
    )

    @app.exception_handler(RelayError)
    async def _relay_error(_: Request, exc: RelayError) -> JSONResponse:
        return JSONResponse({"v": PROTOCOL_VERSION, "code": exc.code}, status_code=exc.http_status)

    @app.exception_handler(RequestValidationError)
    async def _invalid(_: Request, __: RequestValidationError) -> JSONResponse:
        # Não ecoa o corpo recebido: ele pode conter tokens.
        return JSONResponse({"v": PROTOCOL_VERSION, "code": "invalid_message"}, status_code=400)

    app.include_router(health_router)
    app.include_router(pairing_router)
    app.include_router(ws_router)
    return app


app = create_app()
