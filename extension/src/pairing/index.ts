// Conteúdo do QR Code de pareamento (contrato §3.2).

export interface QrPayload {
  v: 1;
  t: "cs-pair";
  relay: string;
  pid: string;
  tok: string;
  pk: string;
  name: string;
}

/**
 * JSON do QR somente com caracteres ASCII (acentos viram \uXXXX), para que
 * qualquer leitor de QR Code devolva exatamente o mesmo texto.
 */
export function encodeQrPayload(payload: QrPayload): string {
  return JSON.stringify(payload).replace(
    /[\u0080-￿]/g,
    (c) => `\\u${c.charCodeAt(0).toString(16).padStart(4, "0")}`,
  );
}

export function decodeQrPayload(text: string): QrPayload | null {
  try {
    const data = JSON.parse(text);
    if (data?.v !== 1 || data?.t !== "cs-pair") return null;
    for (const key of ["relay", "pid", "tok", "pk", "name"]) {
      if (typeof data[key] !== "string" || !data[key]) return null;
    }
    return data as QrPayload;
  } catch {
    return null;
  }
}

export type PairingStage = "idle" | "waiting" | "claimed" | "done" | "failed";

export interface PairingView {
  stage: PairingStage;
  qrText?: string;
  expiresAt?: number;
  sas?: string;
  peerName?: string;
  message?: string;
}
