from __future__ import annotations

import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.context import AppContext
from app.main import create_app
from app.persistence.db import Database

# Implementação de referência do canal E2E (só usada pelos testes, nunca pelo relay).
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))


class FakeClock:
    def __init__(self, start: int = 1_763_150_400_000) -> None:
        self.now = start

    def __call__(self) -> int:
        return self.now

    def advance(self, ms: int) -> None:
        self.now += ms


@pytest.fixture
def clock() -> FakeClock:
    return FakeClock()


@pytest.fixture
def db_path(tmp_path: Path) -> Path:
    return tmp_path / "relay.sqlite3"


@pytest.fixture
def ctx(clock: FakeClock, db_path: Path) -> AppContext:
    settings = Settings(database_path=str(db_path), relay_burst=5, relay_refill_per_s=0.0001)
    context = AppContext(settings=settings, db=Database(str(db_path)), clock=clock)
    yield context
    context.db.close()


@pytest.fixture
def client(ctx: AppContext) -> TestClient:
    with TestClient(create_app(ctx)) as test_client:
        yield test_client
