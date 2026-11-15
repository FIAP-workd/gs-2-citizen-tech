// Teste de fumaça ponta a ponta: usa os módulos reais da extensão (canal E2E e
// cliente do relay) contra um backend em execução e o celular simulado.
//
//   node scripts/run-e2e-smoke.mjs http://localhost:8000 <arquivo-do-qr>
//
// Grava o texto do QR no arquivo indicado e espera o celular simulado parear.

import { writeFileSync } from "node:fs";
import { Channel, generateKeyPair, sasCode } from "../src/communication/channel";
import { RelayConnection, createPairingSession, relayEndpoints } from "../src/communication/relay";
import type { IncomingFrame, Payload, PeerInfo, RelayEnvelope } from "../src/communication/types";
import { encodeQrPayload } from "../src/pairing";

const [relayUrl = "http://localhost:8000", qrFile = "qr.txt"] = process.argv.slice(2);

function waitFrame(queue: IncomingFrame[], type: string, ms = 20_000): Promise<IncomingFrame> {
  const deadline = Date.now() + ms;
  return new Promise((resolve, reject) => {
    const tick = () => {
      const index = queue.findIndex((f) => f.type === type);
      if (index >= 0) return resolve(queue.splice(index, 1)[0]!);
      if (Date.now() > deadline) return reject(new Error(`sem ${type}`));
      setTimeout(tick, 50);
    };
    tick();
  });
}

const endpoints = relayEndpoints(relayUrl);
const keys = await generateKeyPair();
const session = await createPairingSession(endpoints.http, "Chrome — teste E2E", keys.publicKey);
const frames: IncomingFrame[] = [];
const relay = new RelayConnection(endpoints.ws, session.device_id, session.device_token, (f) => frames.push(f), () => {});
await relay.connect();
writeFileSync(
  qrFile,
  encodeQrPayload({ v: 1, t: "cs-pair", relay: endpoints.ws, pid: session.pairing_id, tok: session.pairing_token, pk: keys.publicKey, name: "Chrome — teste E2E" }),
);
console.log("QR gravado; aguardando o celular…");

const claimed = (await waitFrame(frames, "pairing.claimed")) as { peer: PeerInfo };
console.log("SAS da extensão:", await sasCode(keys.publicKey, claimed.peer.public_key));
const completed = (await waitFrame(frames, "pairing.completed")) as { link_id: string };
relay.close();

const relay2 = new RelayConnection(endpoints.ws, session.device_id, session.device_token, (f) => frames.push(f), () => {});
await relay2.connect();
const channel = await Channel.create({
  linkId: completed.link_id,
  ownDeviceId: session.device_id,
  peerDeviceId: claimed.peer.device_id,
  own: keys,
  peerPublicKey: claimed.peer.public_key,
});
// Espera o celular ficar on-line.
for (;;) {
  const status = (await waitFrame(frames, "peer.status", 30_000)) as { online: boolean };
  if (status.online) break;
}

async function ask(payload: Payload, id: string): Promise<Payload> {
  relay2.send(channel.seal(payload, id));
  const env = (await waitFrame(frames, "relay")) as RelayEnvelope;
  return channel.open(env);
}

const fill = await ask({ kind: "fill.request", body: { request_id: "r-1", origin: "http://localhost:8080", hostname: "localhost", top_level: true } }, "r-1");
console.log("fill.response:", JSON.stringify(fill));

const fake = await ask({ kind: "fill.request", body: { request_id: "r-2", origin: "http://localhost.golpe.test", hostname: "localhost.golpe.test", top_level: true } }, "r-2");
console.log("fill.response (domínio falso):", JSON.stringify(fake));

const saved = await ask({ kind: "credential.save", body: { request_id: "r-3", origin: "http://localhost:8080", hostname: "localhost", title: "localhost", username: "nova@exemplo.com", password: "Gerada#123456789", generated: true } }, "r-3");
console.log("credential.save.result:", JSON.stringify(saved));

relay2.close();
process.exit(0);
