// Tipos do contrato de mensagens (docs/message-contract.md).

export const PROTOCOL_VERSION = 1;

export interface EnvelopeHeader {
  msg_id: string;
  link_id: string;
  from: string;
  to: string;
  ts: number;
  seq: number;
  corr: string;
}

export interface RelayEnvelope extends EnvelopeHeader {
  v: 1;
  type: "relay";
  nonce: string;
  ciphertext: string;
}

export interface FillRequest {
  kind: "fill.request";
  body: { request_id: string; origin: string; hostname: string; top_level: boolean };
}

export type FillResult = "approved" | "denied" | "not_found" | "locked" | "error";

export interface FillResponse {
  kind: "fill.response";
  body: {
    request_id: string;
    result: FillResult;
    mode?: "confirmation" | "automatic";
    hostname: string;
    title?: string;
    credential?: { username: string; password: string };
    expires_at: number;
  };
}

export interface FillAck {
  kind: "fill.ack";
  body: { request_id: string; filled: boolean };
}

export interface CredentialSave {
  kind: "credential.save";
  body: {
    request_id: string;
    origin: string;
    hostname: string;
    title: string;
    username: string;
    password: string;
    generated: boolean;
  };
}

export type SaveResult = "saved" | "updated" | "locked" | "denied" | "error";

export interface CredentialSaveResult {
  kind: "credential.save.result";
  body: { request_id: string; result: SaveResult; hostname: string };
}

export type Payload = FillRequest | FillResponse | FillAck | CredentialSave | CredentialSaveResult;

export type ControlFrame =
  | { v: 1; type: "auth.ok"; links: string[] }
  | { v: 1; type: "pairing.claimed"; pairing_id: string; peer: PeerInfo }
  | { v: 1; type: "pairing.completed"; pairing_id: string; link_id: string }
  | { v: 1; type: "pairing.cancelled"; pairing_id: string }
  | { v: 1; type: "peer.status"; link_id: string; online: boolean }
  | { v: 1; type: "link.revoked"; link_id: string }
  | { v: 1; type: "error"; code: string; ref?: string }
  | { v: 1; type: "pong" };

export type IncomingFrame = ControlFrame | RelayEnvelope;

export interface PeerInfo {
  device_id: string;
  device_name: string;
  public_key: string;
}
