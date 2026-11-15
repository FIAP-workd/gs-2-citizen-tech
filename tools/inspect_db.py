"""Inspeciona uma cópia do banco do app (ou do relay) e procura segredos em texto aberto.

Uso:
    adb exec-out run-as com.chavesegura cat databases/chave_segura.db > copia.db
    python tools/inspect_db.py copia.db "Senha-Demo-123" "ana@exemplo.com"

Mostra as tabelas, como cada coluna sensível está gravada e se algum dos
textos informados aparece em qualquer parte do arquivo (inclusive no WAL).
"""

from __future__ import annotations

import sqlite3
import sys
from pathlib import Path


def preview(value: object) -> str:
    if isinstance(value, bytes):
        return f"<{len(value)} bytes cifrados: {value[:12].hex()}…>"
    text = str(value)
    return text if len(text) <= 40 else text[:37] + "..."


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    path = Path(sys.argv[1])
    secrets = sys.argv[2:]

    conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    tables = [r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")]
    for table in tables:
        if table.startswith(("sqlite_", "android_", "room_")):
            continue
        rows = conn.execute(f'SELECT * FROM "{table}" LIMIT 3').fetchall()  # noqa: S608
        columns = [d[0] for d in conn.execute(f'SELECT * FROM "{table}" LIMIT 0').description]  # noqa: S608
        count = conn.execute(f'SELECT COUNT(*) FROM "{table}"').fetchone()[0]  # noqa: S608
        print(f"\n== {table} ({count} linhas)")
        for row in rows:
            print("  " + " | ".join(f"{c}={preview(v)}" for c, v in zip(columns, row, strict=True)))
    conn.close()

    if not secrets:
        return
    print("\n== Procura de textos em claro no arquivo")
    files = [path] + [p for p in (Path(f"{path}-wal"), Path(f"{path}-journal")) if p.exists()]
    raw = b"".join(p.read_bytes() for p in files)
    leaked = False
    for secret in secrets:
        found = any(secret.encode(enc) in raw for enc in ("utf-8", "utf-16-le"))
        leaked |= found
        print(f"  {'ENCONTRADO (falha!)' if found else 'não encontrado (ok)'}: {secret!r}")
    sys.exit(1 if leaked else 0)


if __name__ == "__main__":
    main()
