// Mensagens entre o popup e o service worker.

import type { PairingView } from "../pairing";
import type { Settings } from "../storage";

export type PopupRequest =
  | { type: "getState" }
  | { type: "saveSettings"; settings: Settings }
  | { type: "startPairing" }
  | { type: "cancelPairing" }
  | { type: "fill"; tabId: number }
  | { type: "generateAndSave"; tabId: number; length: number }
  | { type: "retrySave" }
  | { type: "setAuto"; origin: string; enabled: boolean }
  | { type: "unpair" };

export interface BackgroundState {
  paired: boolean;
  peerName?: string;
  peerOnline: boolean;
  connected: boolean;
  settings: Settings;
  pairing: PairingView;
  pendingSave?: { hostname: string; username: string };
  autoSites: string[];
}

export interface ActionResult {
  ok: boolean;
  message: string;
}
