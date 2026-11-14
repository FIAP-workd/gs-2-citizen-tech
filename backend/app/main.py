from fastapi import FastAPI

from app.api.health import router as health_router

app = FastAPI(
    title="Chave Segura — Relay",
    version="0.1.0",
    # Documentação interativa desligada por padrão; o relay não é uma API pública.
    docs_url=None,
    redoc_url=None,
)
app.include_router(health_router)
