// Cliente WebSocket do relay (contrato §4) e chamadas REST de pareamento.

import type { IncomingFrame, PeerInfo, RelayEnvelope } from "./types";

export interface RelayEndpoints {
  http: string;
  ws: string;
}

/** Aceita `http://host:8000`, `https://…`, `ws://…/v1/ws` e devolve os dois endereços. */
export function relayEndpoints(input: string): RelayEndpoints {
  const url = new URL(input.trim());
  const secure = url.protocol === "https:" || url.protocol === "wss:";
  const base = `${url.host}${url.pathname.replace(/\/v1\/ws\/?$/, "").replace(/\/$/, "")}`;
  return { http: `${secure ? "https" : "http"}://${base}`, ws: `${secure ? "wss" : "ws"}://${base}/v1/ws` };
}

export class RelayHttpError extends Error {
  constructor(readonly code: string, readonly status: number) {
    super(code);
  }
}

export interface CreatedPairing {
  pairing_id: string;
  pairing_token: string;
  expires_at: number;
  device_id: string;
  device_token: string;
}

export async function createPairingSession(
  httpBase: string,
  deviceName: string,
  publicKey: string,
): Promise<CreatedPairing> {
  const response = await fetch(`${httpBase}/v1/pairing/sessions`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ v: 1, device_name: deviceName, public_key: publicKey }),
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new RelayHttpError(body.code ?? "error", response.status);
  return body as CreatedPairing;
}

export type FrameHandler = (frame: IncomingFrame) => void;

/** Conexão autenticada com o relay, com ping periódico (mantém o service worker vivo). */
export class RelayConnection {
  private ws: WebSocket | null = null;
  private pingTimer: ReturnType<typeof setInterval> | null = null;
  private closedByUs = false;
  links: string[] = [];

  constructor(
    private readonly url: string,
    private readonly deviceId: string,
    private readonly deviceToken: string,
    private readonly onFrame: FrameHandler,
    private readonly onClose: (reason: string) => void,
  ) {}

  get isOpen(): boolean {
    return this.ws?.readyState === WebSocket.OPEN;
  }

  connect(timeoutMs = 8_000): Promise<string[]> {
    return new Promise((resolve, reject) => {
      let settled = false;
      const ws = new WebSocket(this.url);
      this.ws = ws;
      const timer = setTimeout(() => fail("timeout"), timeoutMs);
      const fail = (reason: string) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        ws.close();
        reject(new Error(reason));
      };

      ws.onopen = () => {
        ws.send(
          JSON.stringify({ v: 1, type: "auth", device_id: this.deviceId, device_token: this.deviceToken }),
        );
      };
      ws.onerror = () => fail("network");
      ws.onclose = () => {
        this.stopPing();
        if (!settled) fail("closed");
        else if (!this.closedByUs) this.onClose("closed");
      };
      ws.onmessage = (event) => {
        let frame: IncomingFrame;
        try {
          frame = JSON.parse(String(event.data));
        } catch {
          return;
        }
        if (!settled) {
          if (frame.type === "auth.ok") {
            settled = true;
            clearTimeout(timer);
            this.links = frame.links;
            this.startPing();
            resolve(frame.links);
          } else if (frame.type === "error") {
            fail(frame.code);
          }
          return;
        }
        this.onFrame(frame);
      };
    });
  }

  send(frame: RelayEnvelope | { v: 1; type: "link.revoke"; link_id: string }): void {
    if (!this.isOpen) throw new Error("peer_offline");
    this.ws!.send(JSON.stringify(frame));
  }

  close(): void {
    this.closedByUs = true;
    this.stopPing();
    this.ws?.close();
    this.ws = null;
  }

  private startPing(): void {
    this.stopPing();
    this.pingTimer = setInterval(() => {
      if (this.isOpen) this.ws!.send(JSON.stringify({ v: 1, type: "ping" }));
    }, 20_000);
  }

  private stopPing(): void {
    if (this.pingTimer) clearInterval(this.pingTimer);
    this.pingTimer = null;
  }
}

export type { PeerInfo };
