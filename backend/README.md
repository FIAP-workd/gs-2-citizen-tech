# Chave Segura — Backend relay

Python 3.12 + FastAPI. Módulos em `app/`:
`api`, `pairing`, `devices`, `relay`, `sessions`, `persistence`, `security`.

```bash
python -m venv .venv
.venv/Scripts/activate            # Windows (Linux/macOS: source .venv/bin/activate)
pip install -e ".[dev]"
ruff check . && pytest
uvicorn app.main:app --reload
```

Com Docker, na raiz do repositório: `docker compose up --build` → `http://localhost:8000/health`.
