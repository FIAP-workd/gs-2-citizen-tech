// Validação da resposta antes do preenchimento (contrato §5.2, threat-model E8/M3).

import { hostnamesMatch } from "../domain";
import type { FillResponse } from "../communication/types";

export interface PendingFill {
  requestId: string;
  hostname: string;
  tabId: number;
}

export type FillDecision =
  | { fill: true; username: string; password: string; mode: string; title?: string }
  | { fill: false; reason: string };

const MESSAGES: Record<string, string> = {
  denied: "O pedido foi recusado no celular.",
  not_found: "Não há conta salva para este site no celular.",
  locked: "O cofre está bloqueado. Desbloqueie o app Chave Segura no celular.",
  error: "O celular não conseguiu responder. Tente de novo.",
};

/**
 * Decide se uma resposta pode ser usada. Falha fechada: qualquer divergência
 * de pedido, domínio, validade ou formato impede o preenchimento.
 */
export function evaluateFillResponse(
  response: FillResponse,
  pending: PendingFill | undefined,
  currentHostname: string | null,
  now: number,
  consumed: Set<string>,
): FillDecision {
  const body = response.body;
  if (!pending || body.request_id !== pending.requestId) {
    return { fill: false, reason: "Resposta inesperada ignorada." };
  }
  if (consumed.has(body.request_id)) {
    return { fill: false, reason: "Resposta repetida ignorada." };
  }
  consumed.add(body.request_id);
  if (body.result !== "approved") {
    return { fill: false, reason: MESSAGES[body.result] ?? MESSAGES.error! };
  }
  if (!hostnamesMatch(body.hostname, pending.hostname)) {
    return { fill: false, reason: "O celular respondeu para outro site. Preenchimento bloqueado." };
  }
  if (!currentHostname || !hostnamesMatch(currentHostname, pending.hostname)) {
    return { fill: false, reason: "A página mudou de endereço. Preenchimento bloqueado." };
  }
  if (typeof body.expires_at !== "number" || body.expires_at < now) {
    return { fill: false, reason: "A autorização expirou. Peça novamente." };
  }
  const credential = body.credential;
  if (
    !credential ||
    typeof credential.username !== "string" ||
    typeof credential.password !== "string" ||
    credential.password.length === 0
  ) {
    return { fill: false, reason: MESSAGES.error! };
  }
  return {
    fill: true,
    username: credential.username,
    password: credential.password,
    mode: body.mode ?? "confirmation",
    title: body.title,
  };
}
