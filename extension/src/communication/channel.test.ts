import sodium from "libsodium-wrappers";
import { beforeAll, describe, expect, it } from "vitest";
import { ReplayError } from "../security/replay";
import { Channel, DecryptError, aadFor, fromB64, generateKeyPair, sasCode, toB64 } from "./channel";
import type { FillRequest, RelayEnvelope } from "./types";
import vectors from "../../../docs/test-vectors/e2e-v1.json";



function fromSeed(seed: string) {
  const kp = sodium.crypto_kx_seed_keypair(fromB64(seed));
  return { publicKey: toB64(kp.publicKey), secretKey: toB64(kp.privateKey) };
}

const request: FillRequest = {
  kind: "fill.request",
  body: { request_id: "r1", origin: "https://a.com", hostname: "a.com", top_level: true },
};

beforeAll(async () => {
  await sodium.ready;
});

describe("interoperabilidade com a implementação de referência (Python)", () => {
  it("gera as mesmas chaves públicas e o mesmo SAS", async () => {
    const ext = fromSeed(vectors.extension.seed);
    const android = fromSeed(vectors.android.seed);
    expect(ext.publicKey).toBe(vectors.extension.public_key);
    expect(android.publicKey).toBe(vectors.android.public_key);
    expect(await sasCode(ext.publicKey, android.publicKey)).toBe(vectors.sas);
  });

  it("monta a AAD no formato canônico", () => {
    expect(aadFor(vectors.envelope)).toBe(vectors.aad);
  });

  it("decifra um envelope produzido em Python, no papel do Android", async () => {
    const env = vectors.envelope as RelayEnvelope;
    const android = await Channel.create(
      {
        linkId: env.link_id,
        ownDeviceId: env.to,
        peerDeviceId: env.from,
        own: fromSeed(vectors.android.seed),
        peerPublicKey: vectors.extension.public_key,
        now: () => env.ts,
      },
      "server",
    );
    expect(JSON.stringify(android.open(env))).toBe(vectors.plaintext);
  });
});

async function pairOfChannels(now = () => Date.now()) {
  const ext = await generateKeyPair();
  const android = await generateKeyPair();
  const base = { linkId: "L", now };
  const extChannel = await Channel.create({
    ...base,
    ownDeviceId: "E",
    peerDeviceId: "A",
    own: ext,
    peerPublicKey: android.publicKey,
  });
  const androidChannel = await Channel.create(
    { ...base, ownDeviceId: "A", peerDeviceId: "E", own: android, peerPublicKey: ext.publicKey },
    "server",
  );
  return { extChannel, androidChannel };
}

describe("Channel", () => {
  it("cifra e decifra nos dois sentidos", async () => {
    const { extChannel, androidChannel } = await pairOfChannels();
    const envelope = extChannel.seal(request, "r1");
    expect(envelope.ciphertext).not.toContain("a.com");
    expect(androidChannel.open(envelope)).toEqual(request);
    const back = androidChannel.seal(request);
    expect(extChannel.open(back)).toEqual(request);
  });

  it("detecta cabeçalho alterado pelo servidor", async () => {
    const { extChannel, androidChannel } = await pairOfChannels();
    const envelope = extChannel.seal(request, "r1");
    expect(() => androidChannel.open({ ...envelope, corr: "r2" })).toThrow(DecryptError);
  });

  it("detecta conteúdo alterado", async () => {
    const { extChannel, androidChannel } = await pairOfChannels();
    const envelope = extChannel.seal(request);
    const bytes = fromB64(envelope.ciphertext);
    bytes[0]! ^= 1;
    expect(() => androidChannel.open({ ...envelope, ciphertext: toB64(bytes) })).toThrow(
      DecryptError,
    );
  });

  it("recusa mensagem repetida (replay)", async () => {
    const { extChannel, androidChannel } = await pairOfChannels();
    const envelope = extChannel.seal(request);
    androidChannel.open(envelope);
    expect(() => androidChannel.open(envelope)).toThrow(ReplayError);
  });

  it("recusa mensagem fora da janela de tempo", async () => {
    let clock = 1_000_000;
    const { extChannel, androidChannel } = await pairOfChannels(() => clock);
    const envelope = extChannel.seal(request);
    clock += 61_000;
    expect(() => androidChannel.open(envelope)).toThrow(ReplayError);
  });

  it("recusa a própria mensagem refletida de volta", async () => {
    const { extChannel } = await pairOfChannels();
    const envelope = extChannel.seal(request);
    expect(() => extChannel.open({ ...envelope, from: "A", to: "E" })).toThrow(DecryptError);
  });
});
