import { describe, expect, it } from "vitest";
import type { FillResponse } from "../communication/types";
import { evaluateFillResponse, type PendingFill } from "./index";

const NOW = 1_000_000;
const pending: PendingFill = { requestId: "r1", hostname: "exemplo.com", tabId: 1 };

function response(overrides: Partial<FillResponse["body"]> = {}): FillResponse {
  return {
    kind: "fill.response",
    body: {
      request_id: "r1",
      result: "approved",
      mode: "confirmation",
      hostname: "exemplo.com",
      credential: { username: "ana", password: "segredo" },
      expires_at: NOW + 30_000,
      ...overrides,
    },
  };
}

describe("evaluateFillResponse", () => {
  it("libera uma resposta válida", () => {
    const decision = evaluateFillResponse(response(), pending, "exemplo.com", NOW, new Set());
    expect(decision).toMatchObject({ fill: true, username: "ana", password: "segredo" });
  });

  it("processa a mesma resposta uma única vez", () => {
    const consumed = new Set<string>();
    expect(evaluateFillResponse(response(), pending, "exemplo.com", NOW, consumed).fill).toBe(true);
    expect(evaluateFillResponse(response(), pending, "exemplo.com", NOW, consumed).fill).toBe(false);
  });

  it("bloqueia resposta para outro domínio", () => {
    const decision = evaluateFillResponse(
      response({ hostname: "exemplo.com.site-falso.com" }),
      pending,
      "exemplo.com",
      NOW,
      new Set(),
    );
    expect(decision.fill).toBe(false);
  });

  it("bloqueia quando a página mudou de endereço", () => {
    const decision = evaluateFillResponse(response(), pending, "outro.com", NOW, new Set());
    expect(decision.fill).toBe(false);
  });

  it("bloqueia autorização expirada", () => {
    const decision = evaluateFillResponse(
      response({ expires_at: NOW - 1 }),
      pending,
      "exemplo.com",
      NOW,
      new Set(),
    );
    expect(decision).toEqual({ fill: false, reason: "A autorização expirou. Peça novamente." });
  });

  it("ignora resposta sem pedido correspondente", () => {
    expect(evaluateFillResponse(response(), undefined, "exemplo.com", NOW, new Set()).fill).toBe(false);
    expect(
      evaluateFillResponse(response({ request_id: "r9" }), pending, "exemplo.com", NOW, new Set()).fill,
    ).toBe(false);
  });

  it.each(["denied", "not_found", "locked", "error"] as const)("não preenche quando %s", (result) => {
    const decision = evaluateFillResponse(
      response({ result, credential: undefined }),
      pending,
      "exemplo.com",
      NOW,
      new Set(),
    );
    expect(decision.fill).toBe(false);
  });
});
