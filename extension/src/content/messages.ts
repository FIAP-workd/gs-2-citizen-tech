// Mensagens entre o service worker e o content script.

import type { FormSummary } from "./detect";

export type ContentRequest =
  | { type: "cs.detect" }
  | { type: "cs.fill"; hostname: string; username: string; password: string }
  | { type: "cs.fillGenerated"; hostname: string; password: string }
  | { type: "cs.notice"; text: string; tone: "ok" | "warn" | "error" };

export interface DetectReply {
  hostname: string;
  top: boolean;
  form: FormSummary;
}

export interface FillReply {
  filled: boolean;
  reason?: string;
}
