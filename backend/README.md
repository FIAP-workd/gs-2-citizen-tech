# Chave Segura — Backend relay

Python 3.12 + FastAPI + SQLite. Pareamento por QR Code e encaminhamento de
envelopes cifrados ponta a ponta. **Não conhece senhas, domínios nem
usuários**: só metadados de dispositivos e sessões.

```bash
python -m venv .venv
.venv/Scripts/activate            # Windows (Linux/macOS: source .venv/bin/activate)
pip install -e ".[dev]"
ruff check . && pytest
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Com Docker, na raiz do repositório: `docker compose up --build` → `http://localhost:8000/health`.

## Rotas

| Rota | Função |
| --- | --- |
| `GET /health` | Verificação de saúde |
| `POST /v1/pairing/sessions` | Extensão cria sessão de pareamento (token de uso único, 120 s) |
| `POST /v1/pairing/sessions/{id}/claim` | Android lê o QR e reivindica |
| `POST /v1/pairing/sessions/{id}/confirm` | Android confirma ou recusa (`Authorization: Bearer`) |
| `WS /v1/ws` | Autenticação, relay de envelopes, presença, revogação |

Contrato completo: [docs/message-contract.md](../docs/message-contract.md).

## Módulos (`app/`)

`api` (rotas), `pairing` (criar/reivindicar/confirmar), `devices`
(registro e autenticação), `sessions` (vínculos e `fill_requests`),
`relay` (protocolo WebSocket e conexões), `persistence` (SQLite),
`security` (tokens, validação, rate limiting).

## Variáveis de ambiente

| Variável | Padrão |
| --- | --- |
| `DATABASE_URL` | `data/relay.sqlite3` (aceita `sqlite:///caminho`) |
| `PAIRING_TTL_SECONDS` | `120` |
| `PAIRING_PER_MINUTE` | `20` (por IP) |
