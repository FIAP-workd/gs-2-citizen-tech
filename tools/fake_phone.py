"""Celular simulado: faz o papel do app Android para testar extensão + backend.

Usa a implementação de referência do canal E2E (cs_protocol.py), sem
compartilhar código com a extensão, então também prova a interoperabilidade.

ATENÇÃO: guarda as credenciais de teste em texto aberto no arquivo de estado.
Use apenas contas e senhas fictícias.

Exemplos (com o backend em http://localhost:8000):

    python tools/fake_phone.py add localhost ana@exemplo.com "Senha-Demo-123"
    python tools/fake_phone.py pair            # cole o código copiado no popup
    python tools/fake_phone.py run              # modo confirmação (pergunta no terminal)
    python tools/fake_phone.py run --mode automatic
    python tools/fake_phone.py run --locked     # simula cofre bloqueado
    python tools/fake_phone.py revoke
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

from cs_protocol import Channel, KeyPair, ReplayError, b64d, b64e, now_ms, sas_code, session_keys
from nacl.exceptions import CryptoError
from websockets.sync.client import connect

STATE = Path(os.environ.get("CS_FAKE_PHONE_STATE") or Path(__file__).with_name(".fake_phone_state.json"))


def load() -> dict[str, Any]:
    if STATE.exists():
        return json.loads(STATE.read_text(encoding="utf-8"))
    return {"credentials": []}


def save(state: dict[str, Any]) -> None:
    STATE.write_text(json.dumps(state, indent=2, ensure_ascii=False), encoding="utf-8")


def http_base(relay_ws: str) -> str:
    base = relay_ws.replace("wss://", "https://").replace("ws://", "http://")
    return base.removesuffix("/v1/ws")


def post(url: str, body: dict, token: str | None = None) -> dict:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(  # noqa: S310 - URL vem do QR de teste
        url, data=json.dumps(body).encode(), headers=headers, method="POST"
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:  # noqa: S310
            return json.loads(response.read())
    except urllib.error.HTTPError as exc:
        detail = json.loads(exc.read() or b"{}")
        raise SystemExit(f"Erro {exc.code}: {detail.get('code', 'desconhecido')}") from exc


# ------------------------------------------------------------------ comandos


def cmd_pair(args: argparse.Namespace) -> None:
    text = args.qr or input("Cole o código de pareamento copiado no popup: ").strip()
    qr = json.loads(text)
    if qr.get("v") != 1 or qr.get("t") != "cs-pair":
        raise SystemExit("Isto não é um código de pareamento do Chave Segura.")
    keys = KeyPair.generate()
    base = http_base(qr["relay"])
    claimed = post(
        f"{base}/v1/pairing/sessions/{qr['pid']}/claim",
        {"v": 1, "pairing_token": qr["tok"], "device_name": args.name, "public_key": b64e(keys.public)},
    )
    # A chave pública da extensão vem do QR (canal físico), não do servidor.
    if claimed["peer"]["public_key"] != qr["pk"]:
        raise SystemExit("A chave do computador não confere com o QR Code. Pareamento abortado.")
    sas = sas_code(b64d(qr["pk"]), keys.public)
    print(f"\nComputador: {qr['name']}")
    print(f"Código de verificação: {sas[:3]} {sas[3:]}  (confira se o popup mostra o mesmo)")
    accepted = input("Confirmar pareamento? [s/N] ").strip().lower() == "s"
    result = post(
        f"{base}/v1/pairing/sessions/{qr['pid']}/confirm",
        {"v": 1, "accepted": accepted},
        token=claimed["device_token"],
    )
    if not accepted:
        print("Pareamento recusado.")
        return
    state = load()
    state["link"] = {
        "relay": qr["relay"],
        "link_id": result["link_id"],
        "device_id": claimed["device_id"],
        "device_token": claimed["device_token"],
        "public": b64e(keys.public),
        "secret": b64e(keys.secret),
        "peer_device_id": claimed["peer"]["device_id"],
        "peer_name": qr["name"],
        "peer_public_key": qr["pk"],
    }
    save(state)
    print("Pareado! Agora rode: python tools/fake_phone.py run")


def cmd_add(args: argparse.Namespace) -> None:
    state = load()
    state["credentials"].append(
        {
            "hostname": args.hostname.lower(),
            "username": args.username,
            "password": args.password,
            "sensitive": args.sensitive,
        }
    )
    save(state)
    print(f"Credencial de teste adicionada para {args.hostname}.")


def cmd_list(_: argparse.Namespace) -> None:
    for c in load()["credentials"]:
        flag = " (sensível)" if c.get("sensitive") else ""
        print(f"- {c['hostname']}: {c['username']}{flag}")


def respond_fill(state: dict, body: dict, args: argparse.Namespace) -> dict:
    hostname = body["hostname"]
    base = {"request_id": body["request_id"], "hostname": hostname, "expires_at": now_ms() + 30_000}
    if args.locked:
        print("  → cofre bloqueado")
        return {**base, "result": "locked"}
    # Comparação EXATA do hostname (nunca por substring).
    matches = [c for c in state["credentials"] if c["hostname"] == hostname]
    if not matches:
        print(f"  → nenhuma credencial para {hostname}")
        return {**base, "result": "not_found"}
    credential = matches[0]
    mode = "automatic" if args.mode == "automatic" and not credential.get("sensitive") else "confirmation"
    if mode == "confirmation":
        answer = input(f"  Liberar {credential['username']} em {hostname}? [s/N] ").strip().lower()
        if answer != "s":
            print("  → recusado")
            return {**base, "result": "denied"}
    print(f"  → liberado ({mode})")
    return {
        **base,
        "result": "approved",
        "mode": mode,
        "title": hostname,
        "credential": {"username": credential["username"], "password": credential["password"]},
    }


def respond_save(state: dict, body: dict, args: argparse.Namespace) -> dict:
    base = {"request_id": body["request_id"], "hostname": body["hostname"]}
    if args.locked:
        return {**base, "result": "locked"}
    for c in state["credentials"]:
        if c["hostname"] == body["hostname"] and c["username"] == body["username"]:
            c["password"] = body["password"]
            save(state)
            print(f"  → senha atualizada para {body['username']} em {body['hostname']}")
            return {**base, "result": "updated"}
    state["credentials"].append(
        {
            "hostname": body["hostname"],
            "username": body["username"],
            "password": body["password"],
            "sensitive": False,
        }
    )
    save(state)
    print(f"  → nova senha salva para {body['username']} em {body['hostname']}")
    return {**base, "result": "saved"}


def cmd_run(args: argparse.Namespace) -> None:
    state = load()
    link = state.get("link")
    if not link:
        raise SystemExit("Ainda não pareado. Rode: python tools/fake_phone.py pair")
    own = KeyPair(b64d(link["public"]), b64d(link["secret"]))
    rx, tx = session_keys(own, b64d(link["peer_public_key"]), is_client=False)
    channel = Channel(link["link_id"], link["device_id"], link["peer_device_id"], rx, tx)
    with connect(link["relay"]) as ws:
        ws.send(json.dumps({"v": 1, "type": "auth", "device_id": link["device_id"],
                            "device_token": link["device_token"]}))
        reply = json.loads(ws.recv())
        if reply.get("type") != "auth.ok":
            raise SystemExit(f"Autenticação recusada: {reply.get('code')}")
        print(f"Conectado como celular simulado (modo {args.mode}). Ctrl+C para sair.")
        for raw in ws:
            frame = json.loads(raw)
            if frame["type"] == "link.revoked":
                print("Vínculo revogado pelo computador.")
                return
            if frame["type"] == "peer.status":
                print(f"Computador {'on-line' if frame['online'] else 'desconectado'}")
                continue
            if frame["type"] != "relay":
                continue
            try:
                payload = channel.open(frame)
            except (CryptoError, ReplayError) as exc:
                print(f"Mensagem recusada: {exc}")
                continue
            kind, body = payload["kind"], payload["body"]
            print(f"\nRecebido {kind}")
            if kind == "fill.request":
                answer = {"kind": "fill.response", "body": respond_fill(state, body, args)}
            elif kind == "credential.save":
                answer = {"kind": "credential.save.result", "body": respond_save(state, body, args)}
            else:
                print(f"  {json.dumps(body)}")
                continue
            ws.send(json.dumps(channel.seal(answer, corr=frame["corr"])))


def cmd_revoke(_: argparse.Namespace) -> None:
    state = load()
    link = state.get("link")
    if not link:
        raise SystemExit("Nada para revogar.")
    with connect(link["relay"]) as ws:
        ws.send(json.dumps({"v": 1, "type": "auth", "device_id": link["device_id"],
                            "device_token": link["device_token"]}))
        if json.loads(ws.recv()).get("type") == "auth.ok":
            ws.send(json.dumps({"v": 1, "type": "link.revoke", "link_id": link["link_id"]}))
    state.pop("link")
    save(state)
    print("Computador revogado.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    sub = parser.add_subparsers(required=True)

    p = sub.add_parser("pair", help="lê o código do QR e pareia")
    p.add_argument("qr", nargs="?", help="texto do QR (se omitido, é pedido no terminal)")
    p.add_argument("--name", default="Celular simulado")
    p.set_defaults(func=cmd_pair)

    a = sub.add_parser("add", help="adiciona credencial fictícia ao cofre simulado")
    a.add_argument("hostname")
    a.add_argument("username")
    a.add_argument("password")
    a.add_argument("--sensitive", action="store_true", help="exige confirmação mesmo no automático")
    a.set_defaults(func=cmd_add)

    sub.add_parser("list", help="lista credenciais").set_defaults(func=cmd_list)

    r = sub.add_parser("run", help="atende pedidos da extensão")
    r.add_argument("--mode", choices=["confirmation", "automatic"], default="confirmation")
    r.add_argument("--locked", action="store_true", help="simula cofre bloqueado")
    r.set_defaults(func=cmd_run)

    sub.add_parser("revoke", help="revoga o computador pareado").set_defaults(func=cmd_revoke)

    args = parser.parse_args()
    try:
        args.func(args)
    except KeyboardInterrupt:
        sys.exit(0)


if __name__ == "__main__":
    main()
