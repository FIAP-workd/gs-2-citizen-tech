// Armazenamento local da extensão (chrome.storage.local, acessível só à extensão).
// Nunca guarda senhas: apenas configurações, a identidade X25519 e o vínculo.

import type { KeyPairB64 } from "./communication/channel";

export interface Settings {
  relayUrl: string;
  deviceName: string;
  /** Exceção de desenvolvimento: aceita HTTP em localhost e *.test. */
  allowDevHttp: boolean;
  generatorLength: number;
}

export const DEFAULT_SETTINGS: Settings = {
  relayUrl: "http://localhost:8000",
  deviceName: "Chrome neste computador",
  allowDevHttp: false,
  generatorLength: 20,
};

export interface PairingRecord {
  linkId: string;
  deviceId: string;
  deviceToken: string;
  relayWs: string;
  keys: KeyPairB64;
  peerDeviceId: string;
  peerName: string;
  peerPublicKey: string;
  pairedAt: number;
  seq: number;
}

interface Schema {
  settings: Settings;
  pairing: PairingRecord | null;
  autoSites: string[];
}

async function read<K extends keyof Schema>(key: K, fallback: Schema[K]): Promise<Schema[K]> {
  const data = await chrome.storage.local.get(key);
  return (data[key] as Schema[K] | undefined) ?? fallback;
}

async function write<K extends keyof Schema>(key: K, value: Schema[K]): Promise<void> {
  await chrome.storage.local.set({ [key]: value });
}

export const store = {
  async settings(): Promise<Settings> {
    return { ...DEFAULT_SETTINGS, ...(await read("settings", DEFAULT_SETTINGS)) };
  },
  saveSettings: (value: Settings) => write("settings", value),
  pairing: () => read("pairing", null),
  savePairing: (value: PairingRecord | null) => write("pairing", value),
  autoSites: () => read("autoSites", []),
  saveAutoSites: (value: string[]) => write("autoSites", value),
};
