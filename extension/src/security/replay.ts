// Anti-replay (contrato §4.2): msg_id único, janela de ±60 s e seq crescente.

export const TS_WINDOW_MS = 60_000;

export class ReplayError extends Error {}

interface Header {
  msg_id: string;
  ts: number;
  seq: number;
}

export class ReplayGuard {
  private lastSeq = -1;
  private readonly seen = new Map<string, number>();

  constructor(private readonly now: () => number = Date.now) {}

  /** Verifica sem registrar: só aceite depois que a decifragem der certo. */
  check(header: Header): void {
    const now = this.now();
    if (!Number.isInteger(header.ts) || Math.abs(now - header.ts) > TS_WINDOW_MS) {
      throw new ReplayError("mensagem fora da janela de tempo");
    }
    if (this.seen.has(header.msg_id)) throw new ReplayError("mensagem repetida");
    if (!Number.isInteger(header.seq) || header.seq <= this.lastSeq) {
      throw new ReplayError("sequência inválida");
    }
  }

  accept(header: Header): void {
    const now = this.now();
    this.lastSeq = header.seq;
    for (const [id, at] of this.seen) if (now - at > TS_WINDOW_MS) this.seen.delete(id);
    this.seen.set(header.msg_id, now);
  }
}
