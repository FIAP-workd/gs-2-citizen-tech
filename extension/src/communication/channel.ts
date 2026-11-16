// Canal E2E com libsodium (ADR 0001 §4): crypto_kx + XChaCha20-Poly1305.
// O cabeçalho do envelope entra como AAD: se o relay alterar qualquer campo,
// a decifragem falha.

import sodium from "libsodium-wrappers";
import { ReplayGuard } from "../security/replay";
import { PROTOCOL_VERSION, type EnvelopeHeader, type Payload, type RelayEnvelope } from "./types";

export const ready = sodium.ready;

const B64 = () => sodium.base64_variants.URLSAFE_NO_PADDING;

export function toB64(bytes: Uint8Array): string {
  return sodium.to_base64(bytes, B64());
}

export function fromB64(text: string): Uint8Array {
  return sodium.from_base64(text, B64());
}

export interface KeyPairB64 {
  publicKey: string;
  secretKey: string;
}

export async function generateKeyPair(): Promise<KeyPairB64> {
  await sodium.ready;
  const kp = sodium.crypto_kx_keypair();
  return { publicKey: toB64(kp.publicKey), secretKey: toB64(kp.privateKey) };
}

/** Código de verificação de 6 dígitos mostrado nas duas telas (contrato §3.5). */
export async function sasCode(extensionPk: string, androidPk: string): Promise<string> {
  await sodium.ready;
  const label = new TextEncoder().encode("chave-segura/sas/v1");
  const input = new Uint8Array([...label, ...fromB64(extensionPk), ...fromB64(androidPk)]);
  const digest = sodium.crypto_generichash(32, input, null);
  const value = new DataView(digest.buffer, digest.byteOffset, 4).getUint32(0, false);
  return String(value % 1_000_000).padStart(6, "0");
}

export function aadFor(header: EnvelopeHeader): string {
  return [
    `cs/${PROTOCOL_VERSION}`,
    header.msg_id,
    header.link_id,
    header.from,
    header.to,
    String(header.ts),
    String(header.seq),
    header.corr ?? "",
  ].join("|");
}

export interface ChannelInit {
  linkId: string;
  ownDeviceId: string;
  peerDeviceId: string;
  own: KeyPairB64;
  peerPublicKey: string;
  /** Último `seq` enviado (persistido para não repetir após reiniciar). */
  seq?: number;
  now?: () => number;
}

export class DecryptError extends Error {}

/** Canal do lado da extensão (cliente do crypto_kx). */
export class Channel {
  readonly linkId: string;
  readonly ownDeviceId: string;
  readonly peerDeviceId: string;
  seq: number;
  private readonly rx: Uint8Array;
  private readonly tx: Uint8Array;
  private readonly guard: ReplayGuard;
  private readonly now: () => number;

  private constructor(init: ChannelInit, rx: Uint8Array, tx: Uint8Array) {
    this.linkId = init.linkId;
    this.ownDeviceId = init.ownDeviceId;
    this.peerDeviceId = init.peerDeviceId;
    // Semeado com o relógio para continuar crescente mesmo após reinícios.
    this.seq = Math.max(init.seq ?? 0, (init.now ?? Date.now)());
    this.now = init.now ?? Date.now;
    this.rx = rx;
    this.tx = tx;
    this.guard = new ReplayGuard(this.now);
  }

  static async create(init: ChannelInit, role: "client" | "server" = "client"): Promise<Channel> {
    await sodium.ready;
    const pk = fromB64(init.own.publicKey);
    const sk = fromB64(init.own.secretKey);
    const peer = fromB64(init.peerPublicKey);
    const keys =
      role === "client"
        ? sodium.crypto_kx_client_session_keys(pk, sk, peer)
        : sodium.crypto_kx_server_session_keys(pk, sk, peer);
    return new Channel(init, keys.sharedRx, keys.sharedTx);
  }

  seal(payload: Payload, corr = ""): RelayEnvelope {
    this.seq += 1;
    const header: EnvelopeHeader = {
      msg_id: crypto.randomUUID(),
      link_id: this.linkId,
      from: this.ownDeviceId,
      to: this.peerDeviceId,
      ts: this.now(),
      seq: this.seq,
      corr,
    };
    const nonce = sodium.randombytes_buf(24);
    const plaintext = new TextEncoder().encode(JSON.stringify(payload));
    const ciphertext = sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(
      plaintext,
      aadFor(header),
      null,
      nonce,
      this.tx,
    );
    plaintext.fill(0);
    return { v: 1, type: "relay", ...header, nonce: toB64(nonce), ciphertext: toB64(ciphertext) };
  }

  /** Decifra e valida; lança `DecryptError` ou `ReplayError` (falha fechada). */
  open(envelope: RelayEnvelope): Payload {
    if (envelope.from !== this.peerDeviceId || envelope.to !== this.ownDeviceId) {
      throw new DecryptError("remetente inesperado");
    }
    if (envelope.link_id !== this.linkId) throw new DecryptError("vínculo inesperado");
    this.guard.check(envelope);
    let plaintext: Uint8Array;
    try {
      plaintext = sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(
        null,
        fromB64(envelope.ciphertext),
        aadFor(envelope),
        fromB64(envelope.nonce),
        this.rx,
      );
    } catch {
      throw new DecryptError("falha de autenticação da mensagem");
    }
    this.guard.accept(envelope);
    const payload = JSON.parse(new TextDecoder().decode(plaintext)) as Payload;
    plaintext.fill(0);
    return payload;
  }
}
