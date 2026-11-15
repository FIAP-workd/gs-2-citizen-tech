// Service worker: coordena pareamento, solicitações de preenchimento,
// gerar-e-salvar e o preenchimento automático por site.
//
// Guarda as chaves e o canal E2E. O content script recebe apenas a
// credencial da página atual, depois de todas as validações.

import { Channel, DecryptError, generateKeyPair, sasCode } from "../communication/channel";
import {
  RelayConnection,
  RelayHttpError,
  createPairingSession,
  relayEndpoints,
} from "../communication/relay";
import type {
  CredentialSaveResult,
  FillResponse,
  IncomingFrame,
  Payload,
  PeerInfo,
} from "../communication/types";
import type { ContentRequest, DetectReply, FillReply } from "../content/messages";
import { checkPageUrl, describeOriginProblem, hostnamesMatch, normalizeHostname } from "../domain";
import { encodeQrPayload, type PairingView } from "../pairing";
import { evaluateFillResponse } from "../security";
import { DEFAULT_GENERATOR, generatePassword } from "../security/generator";
import { ReplayError } from "../security/replay";
import { store, type PairingRecord } from "../storage";
import type { ActionResult, BackgroundState, PopupRequest } from "./messages";

const FILL_TIMEOUT_MS = 90_000; // inclui o tempo de o usuário confirmar no celular
const SAVE_TIMEOUT_MS = 30_000;
const IDLE_CLOSE_MS = 5 * 60_000;
const PENDING_SAVE_TTL_MS = 5 * 60_000;
const AUTO_COOLDOWN_MS = 30_000;
const AUTO_SCRIPT_ID = "cs-auto";

// ---------------------------------------------------------------- estado ---

let relay: RelayConnection | null = null;
let channel: Channel | null = null;
let peerOnline = false;
let idleTimer: ReturnType<typeof setTimeout> | null = null;

interface Waiter {
  requestId: string;
  msgIds: Set<string>;
  resolve: (payload: Payload) => void;
  reject: (error: Error) => void;
  timer: ReturnType<typeof setTimeout>;
}
const waiters = new Map<string, Waiter>();
const consumed = new Set<string>();
const autoCooldown = new Map<number, number>();

interface PendingSave {
  origin: string;
  hostname: string;
  username: string;
  password: string;
  expiresAt: number;
}
let pendingSave: PendingSave | null = null;

interface PairingSession {
  view: PairingView;
  connection: RelayConnection;
  keys: { publicKey: string; secretKey: string };
  deviceId: string;
  deviceToken: string;
  relayWs: string;
  peer?: PeerInfo;
  timer: ReturnType<typeof setTimeout>;
}
let pairing: PairingSession | null = null;

function broadcast(): void {
  chrome.runtime.sendMessage({ type: "bg.changed" }).catch(() => undefined);
}

class UserError extends Error {}

// ----------------------------------------------------------- conexão ---

function touch(): void {
  if (idleTimer) clearTimeout(idleTimer);
  idleTimer = setTimeout(() => disconnect(), IDLE_CLOSE_MS);
}

function disconnect(): void {
  relay?.close();
  relay = null;
  channel = null;
  peerOnline = false;
  broadcast();
}

async function handleRevoked(): Promise<void> {
  disconnect();
  await store.savePairing(null);
  for (const waiter of waiters.values()) waiter.reject(new Error("device_revoked"));
  broadcast();
}

async function ensureConnected(): Promise<{ relay: RelayConnection; channel: Channel; record: PairingRecord }> {
  const record = await store.pairing();
  if (!record) throw new UserError("Nenhum celular pareado. Abra a extensão e clique em “Parear com o celular”.");
  if (relay?.isOpen && channel) {
    touch();
    return { relay, channel, record };
  }
  const nextChannel = await Channel.create({
    linkId: record.linkId,
    ownDeviceId: record.deviceId,
    peerDeviceId: record.peerDeviceId,
    own: record.keys,
    peerPublicKey: record.peerPublicKey,
    seq: record.seq,
  });
  const connection = new RelayConnection(
    record.relayWs,
    record.deviceId,
    record.deviceToken,
    onFrame,
    () => {
      if (relay === connection) {
        relay = null;
        channel = null;
        peerOnline = false;
        broadcast();
      }
    },
  );
  let links: string[];
  try {
    links = await connection.connect();
  } catch (error) {
    const code = (error as Error).message;
    if (code === "device_revoked" || code === "auth_failed") {
      await handleRevoked();
      throw new UserError("Este computador foi desconectado do celular. Faça o pareamento de novo.");
    }
    throw new UserError("Não foi possível falar com o servidor. Verifique o endereço nas configurações.");
  }
  if (!links.includes(record.linkId)) {
    connection.close();
    await handleRevoked();
    throw new UserError("Este computador foi desconectado do celular. Faça o pareamento de novo.");
  }
  relay?.close();
  relay = connection;
  channel = nextChannel;
  touch();
  broadcast();
  return { relay: connection, channel: nextChannel, record };
}

async function sendPayload(payload: Payload, requestId: string): Promise<string> {
  const { relay: conn, channel: chan, record } = await ensureConnected();
  const envelope = chan.seal(payload, requestId);
  await store.savePairing({ ...record, seq: chan.seq });
  conn.send(envelope);
  return envelope.msg_id;
}

function waitFor(requestId: string, timeoutMs: number): Waiter & { promise: Promise<Payload> } {
  let waiter!: Waiter;
  const promise = new Promise<Payload>((resolve, reject) => {
    const timer = setTimeout(() => {
      waiters.delete(requestId);
      reject(new Error("timeout"));
    }, timeoutMs);
    waiter = {
      requestId,
      msgIds: new Set(),
      timer,
      resolve: (payload) => {
        clearTimeout(timer);
        waiters.delete(requestId);
        resolve(payload);
      },
      reject: (error) => {
        clearTimeout(timer);
        waiters.delete(requestId);
        reject(error);
      },
    };
  });
  waiters.set(requestId, waiter);
  return Object.assign(waiter, { promise });
}

function onFrame(frame: IncomingFrame): void {
  switch (frame.type) {
    case "relay": {
      if (!channel) return;
      let payload: Payload;
      try {
        payload = channel.open(frame);
      } catch (error) {
        // Falha de integridade ou replay: tratada como mensagem inexistente.
        if (error instanceof DecryptError || error instanceof ReplayError) {
          console.warn("Chave Segura: mensagem recusada", error.message);
        }
        return;
      }
      if (payload.kind === "fill.response" || payload.kind === "credential.save.result") {
        const waiter = waiters.get(payload.body.request_id);
        if (waiter && frame.corr === waiter.requestId) waiter.resolve(payload);
      }
      return;
    }
    case "error": {
      for (const waiter of waiters.values()) {
        if (frame.ref && waiter.msgIds.has(frame.ref)) waiter.reject(new Error(frame.code));
      }
      if (frame.code === "device_revoked") void handleRevoked();
      return;
    }
    case "peer.status":
      peerOnline = frame.online;
      broadcast();
      return;
    case "link.revoked":
      void handleRevoked();
      return;
    default:
      return;
  }
}

function describeError(error: unknown): string {
  if (error instanceof UserError) return error.message;
  const code = (error as Error)?.message;
  switch (code) {
    case "peer_offline":
      return "O celular está desconectado. Abra o app Chave Segura no celular e desbloqueie o cofre.";
    case "timeout":
      return "O celular não respondeu a tempo. Tente de novo.";
    case "rate_limited":
      return "Muitos pedidos seguidos. Aguarde alguns segundos.";
    case "device_revoked":
      return "Este computador foi desconectado do celular. Faça o pareamento de novo.";
    default:
      return "Algo deu errado. Tente de novo.";
  }
}

// ---------------------------------------------------------- páginas ---

async function inspectTab(tabId: number, urlHint?: string) {
  const settings = await store.settings();
  const tab = await chrome.tabs.get(tabId);
  const check = checkPageUrl(urlHint ?? tab.url, settings);
  if (!check.ok) throw new UserError(describeOriginProblem(check.reason));
  try {
    await chrome.scripting.executeScript({ target: { tabId, frameIds: [0] }, files: ["content.js"] });
  } catch {
    throw new UserError("A extensão não tem acesso a esta página. Clique no ícone da extensão nela.");
  }
  const detect = (await chrome.tabs.sendMessage(tabId, { type: "cs.detect" } satisfies ContentRequest, {
    frameId: 0,
  })) as DetectReply;
  if (!detect?.top || !hostnamesMatch(detect.hostname, check.hostname)) {
    throw new UserError("Não foi possível confirmar o endereço da página.");
  }
  return { check, detect };
}

async function currentHostname(tabId: number): Promise<string | null> {
  try {
    const tab = await chrome.tabs.get(tabId);
    return tab.url ? normalizeHostname(tab.url) : null;
  } catch {
    return null;
  }
}

function notify(tabId: number, result: ActionResult): void {
  const message: ContentRequest = {
    type: "cs.notice",
    text: result.message,
    tone: result.ok ? "ok" : "warn",
  };
  chrome.tabs.sendMessage(tabId, message, { frameId: 0 }).catch(() => undefined);
}

// ------------------------------------------------------ preenchimento ---

async function requestFill(tabId: number, urlHint?: string): Promise<ActionResult> {
  const { check, detect } = await inspectTab(tabId, urlHint);
  if (detect.form.kind === "none") {
    throw new UserError("Nenhum formulário de login encontrado nesta página.");
  }

  const requestId = crypto.randomUUID();
  const waiter = waitFor(requestId, FILL_TIMEOUT_MS);
  try {
    const msgId = await sendPayload(
      {
        kind: "fill.request",
        body: { request_id: requestId, origin: check.origin, hostname: check.hostname, top_level: true },
      },
      requestId,
    );
    waiter.msgIds.add(msgId);
  } catch (error) {
    waiter.reject(error as Error);
  }
  const reply = (await waiter.promise) as FillResponse;
  if (reply.kind !== "fill.response") throw new Error("invalid");

  const decision = evaluateFillResponse(
    reply,
    { requestId, hostname: check.hostname, tabId },
    await currentHostname(tabId),
    Date.now(),
    consumed,
  );
  if (!decision.fill) return { ok: false, message: decision.reason };

  const fill: ContentRequest = {
    type: "cs.fill",
    hostname: check.hostname,
    username: decision.username,
    password: decision.password,
  };
  const result = (await chrome.tabs.sendMessage(tabId, fill, { frameId: 0 })) as FillReply;
  fill.password = "";
  decision.password = "";
  await sendPayload({ kind: "fill.ack", body: { request_id: requestId, filled: result.filled } }, requestId).catch(
    () => undefined,
  );
  if (!result.filled) {
    return { ok: false, message: "Não foi possível preencher os campos desta página." };
  }
  const how = decision.mode === "automatic" ? "automaticamente (dispositivo confiável)" : "após aprovação no celular";
  return { ok: true, message: `Conta preenchida ${how}.` };
}

// --------------------------------------------------- gerar e salvar ---

const SAVE_MESSAGES: Record<CredentialSaveResult["body"]["result"], string> = {
  saved: "Senha forte criada, preenchida e salva no cofre do celular.",
  updated: "Senha forte criada, preenchida e atualizada no cofre do celular.",
  locked: "A senha foi colocada no formulário, mas o cofre do celular está bloqueado. Desbloqueie o app e clique em “Tentar salvar de novo”.",
  denied: "O celular recusou salvar esta senha.",
  error: "O celular não conseguiu salvar. Clique em “Tentar salvar de novo”.",
};

async function sendSave(save: PendingSave): Promise<ActionResult> {
  const requestId = crypto.randomUUID();
  const waiter = waitFor(requestId, SAVE_TIMEOUT_MS);
  try {
    const msgId = await sendPayload(
      {
        kind: "credential.save",
        body: {
          request_id: requestId,
          origin: save.origin,
          hostname: save.hostname,
          title: save.hostname,
          username: save.username,
          password: save.password,
          generated: true,
        },
      },
      requestId,
    );
    waiter.msgIds.add(msgId);
  } catch (error) {
    waiter.reject(error as Error);
  }
  let reply: Payload;
  try {
    reply = await waiter.promise;
  } catch (error) {
    pendingSave = save;
    broadcast();
    return {
      ok: false,
      message: `A senha foi colocada no formulário, mas ainda não foi salva. ${describeError(error)} Depois clique em “Tentar salvar de novo”.`,
    };
  }
  if (reply.kind !== "credential.save.result" || !hostnamesMatch(reply.body.hostname, save.hostname)) {
    pendingSave = save;
    return { ok: false, message: SAVE_MESSAGES.error };
  }
  const ok = reply.body.result === "saved" || reply.body.result === "updated";
  pendingSave = ok || reply.body.result === "denied" ? null : save;
  broadcast();
  return { ok, message: SAVE_MESSAGES[reply.body.result] };
}

async function generateAndSave(tabId: number, length: number): Promise<ActionResult> {
  const { check, detect } = await inspectTab(tabId);
  if (detect.form.kind === "none") {
    throw new UserError("Nenhum campo de senha encontrado nesta página.");
  }
  const username = detect.form.usernameValue;
  if (!username) {
    throw new UserError("Digite primeiro o seu e-mail ou usuário no formulário e tente de novo.");
  }
  await ensureConnected(); // falha cedo, antes de alterar a página, se não houver vínculo

  const password = generatePassword({ ...DEFAULT_GENERATOR, length });
  const fill: ContentRequest = { type: "cs.fillGenerated", hostname: check.hostname, password };
  const result = (await chrome.tabs.sendMessage(tabId, fill, { frameId: 0 })) as FillReply;
  fill.password = "";
  if (!result.filled) throw new UserError("Não foi possível preencher o campo de senha.");

  return sendSave({
    origin: check.origin,
    hostname: check.hostname,
    username,
    password,
    expiresAt: Date.now() + PENDING_SAVE_TTL_MS,
  });
}

async function retrySave(): Promise<ActionResult> {
  if (!pendingSave || pendingSave.expiresAt < Date.now()) {
    pendingSave = null;
    throw new UserError("Não há senha aguardando para ser salva.");
  }
  return sendSave(pendingSave);
}

// ------------------------------------------------------------ pareamento ---

function endPairing(view: PairingView): void {
  if (!pairing) return;
  clearTimeout(pairing.timer);
  pairing.connection.close();
  pairing.view = view;
  broadcast();
}

async function startPairing(): Promise<PairingView> {
  if (pairing && (pairing.view.stage === "waiting" || pairing.view.stage === "claimed")) {
    endPairing({ stage: "idle" });
  }
  const settings = await store.settings();
  const endpoints = relayEndpoints(settings.relayUrl);
  const keys = await generateKeyPair();
  let created;
  try {
    created = await createPairingSession(endpoints.http, settings.deviceName, keys.publicKey);
  } catch (error) {
    if (error instanceof RelayHttpError && error.code === "rate_limited") {
      throw new UserError("Muitas tentativas de pareamento. Aguarde um minuto.");
    }
    throw new UserError("Não foi possível falar com o servidor. Verifique o endereço nas configurações.");
  }

  const session: PairingSession = {
    view: {
      stage: "waiting",
      expiresAt: created.expires_at,
      qrText: encodeQrPayload({
        v: 1,
        t: "cs-pair",
        relay: endpoints.ws,
        pid: created.pairing_id,
        tok: created.pairing_token,
        pk: keys.publicKey,
        name: settings.deviceName,
      }),
    },
    keys,
    deviceId: created.device_id,
    deviceToken: created.device_token,
    relayWs: endpoints.ws,
    timer: setTimeout(() => {
      if (pairing === session && session.view.stage === "waiting") {
        endPairing({ stage: "failed", message: "O QR Code expirou. Clique em “Parear” para gerar outro." });
      }
    }, Math.max(1_000, created.expires_at - Date.now())),
    connection: new RelayConnection(
      endpoints.ws,
      created.device_id,
      created.device_token,
      (frame) => void onPairingFrame(session, frame),
      () => {
        if (pairing === session && session.view.stage !== "done" && session.view.stage !== "failed") {
          endPairing({ stage: "failed", message: "A conexão com o servidor caiu. Tente de novo." });
        }
      },
    ),
  };
  pairing = session;
  try {
    await session.connection.connect();
  } catch {
    endPairing({ stage: "failed", message: "Não foi possível falar com o servidor. Tente de novo." });
  }
  broadcast();
  return session.view;
}

async function onPairingFrame(session: PairingSession, frame: IncomingFrame): Promise<void> {
  if (pairing !== session) return;
  if (frame.type === "pairing.claimed") {
    session.peer = frame.peer;
    clearTimeout(session.timer);
    session.timer = setTimeout(() => {
      if (pairing === session && session.view.stage === "claimed") {
        endPairing({ stage: "failed", message: "O pareamento não foi confirmado a tempo." });
      }
    }, 180_000);
    session.view = {
      stage: "claimed",
      peerName: frame.peer.device_name,
      sas: await sasCode(session.keys.publicKey, frame.peer.public_key),
    };
    broadcast();
  } else if (frame.type === "pairing.completed" && session.peer) {
    await store.savePairing({
      linkId: frame.link_id,
      deviceId: session.deviceId,
      deviceToken: session.deviceToken,
      relayWs: session.relayWs,
      keys: session.keys,
      peerDeviceId: session.peer.device_id,
      peerName: session.peer.device_name,
      peerPublicKey: session.peer.public_key,
      pairedAt: Date.now(),
      seq: 0,
    });
    endPairing({ stage: "done", peerName: session.peer.device_name });
    disconnect();
    ensureConnected().catch(() => undefined);
  } else if (frame.type === "pairing.cancelled") {
    endPairing({ stage: "failed", message: "O pareamento foi recusado no celular." });
  }
}

async function unpair(): Promise<ActionResult> {
  try {
    const { relay: conn, record } = await ensureConnected();
    conn.send({ v: 1, type: "link.revoke", link_id: record.linkId });
  } catch {
    // Sem conexão: apaga localmente; o celular recusa este computador ao revogar lá.
  }
  await handleRevoked();
  return { ok: true, message: "Este computador foi desconectado do celular." };
}

// ------------------------------------------------- automático por site ---

function sitePattern(origin: string): string {
  const url = new URL(origin);
  return `${url.protocol}//${url.hostname}/*`;
}

async function syncAutoScripts(): Promise<void> {
  const sites = await store.autoSites();
  const existing = await chrome.scripting.getRegisteredContentScripts({ ids: [AUTO_SCRIPT_ID] });
  if (existing.length) await chrome.scripting.unregisterContentScripts({ ids: [AUTO_SCRIPT_ID] });
  const allowed: string[] = [];
  for (const origin of sites) {
    if (await chrome.permissions.contains({ origins: [sitePattern(origin)] })) allowed.push(origin);
  }
  if (allowed.length !== sites.length) await store.saveAutoSites(allowed);
  if (allowed.length === 0) return;
  await chrome.scripting.registerContentScripts([
    {
      id: AUTO_SCRIPT_ID,
      matches: allowed.map(sitePattern),
      js: ["content.js", "autostart.js"],
      runAt: "document_idle",
      allFrames: false,
    },
  ]);
}

async function setAuto(origin: string, enabled: boolean): Promise<ActionResult> {
  const sites = new Set(await store.autoSites());
  if (enabled) sites.add(origin);
  else {
    sites.delete(origin);
    await chrome.permissions.remove({ origins: [sitePattern(origin)] }).catch(() => undefined);
  }
  await store.saveAutoSites([...sites]);
  await syncAutoScripts();
  return {
    ok: true,
    message: enabled
      ? "Preenchimento automático ligado neste site. O celular ainda decide se libera sem confirmação."
      : "Preenchimento automático desligado neste site.",
  };
}

async function onAutoStart(sender: chrome.runtime.MessageSender): Promise<void> {
  const tabId = sender.tab?.id;
  if (tabId === undefined || sender.frameId !== 0 || !sender.url) return;
  const origin = new URL(sender.url).origin;
  if (!(await store.autoSites()).includes(origin) || !(await store.pairing())) return;
  const last = autoCooldown.get(tabId) ?? 0;
  if (Date.now() - last < AUTO_COOLDOWN_MS) return;
  autoCooldown.set(tabId, Date.now());
  const result = await requestFill(tabId, sender.url).catch(
    (error): ActionResult => ({ ok: false, message: describeError(error) }),
  );
  notify(tabId, result);
}

// ----------------------------------------------------------- mensagens ---

async function getState(): Promise<BackgroundState> {
  const [settings, record, autoSites] = await Promise.all([
    store.settings(),
    store.pairing(),
    store.autoSites(),
  ]);
  if (record && !relay?.isOpen) ensureConnected().catch(() => undefined);
  if (pendingSave && pendingSave.expiresAt < Date.now()) pendingSave = null;
  return {
    paired: Boolean(record),
    peerName: record?.peerName,
    peerOnline,
    connected: Boolean(relay?.isOpen),
    settings,
    pairing: pairing?.view ?? { stage: "idle" },
    pendingSave: pendingSave ? { hostname: pendingSave.hostname, username: pendingSave.username } : undefined,
    autoSites,
  };
}

async function withNotice(tabId: number, action: Promise<ActionResult>): Promise<ActionResult> {
  const result = await action.catch((error): ActionResult => ({ ok: false, message: describeError(error) }));
  notify(tabId, result);
  return result;
}

async function handlePopup(request: PopupRequest): Promise<unknown> {
  switch (request.type) {
    case "getState":
      return getState();
    case "saveSettings":
      await store.saveSettings(request.settings);
      return { ok: true, message: "Configurações salvas." };
    case "startPairing":
      return startPairing().catch((error) => {
        pairing = null;
        return { stage: "failed", message: describeError(error) } satisfies PairingView;
      });
    case "cancelPairing":
      endPairing({ stage: "idle" });
      return { ok: true };
    case "fill":
      return withNotice(request.tabId, requestFill(request.tabId));
    case "generateAndSave":
      return withNotice(request.tabId, generateAndSave(request.tabId, request.length));
    case "retrySave":
      return retrySave().catch((error): ActionResult => ({ ok: false, message: describeError(error) }));
    case "setAuto":
      return setAuto(request.origin, request.enabled);
    case "unpair":
      return unpair();
  }
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (sender.id !== chrome.runtime.id) return false;
  if (message?.type === "cs.autoStart") {
    void onAutoStart(sender);
    return false;
  }
  // Mensagens do popup vêm de uma página da extensão, nunca de uma aba.
  if (sender.tab) return false;
  handlePopup(message as PopupRequest).then(sendResponse, (error) =>
    sendResponse({ ok: false, message: describeError(error) }),
  );
  return true;
});

chrome.commands?.onCommand.addListener((command, tab) => {
  if (command === "fill-login" && tab?.id !== undefined) {
    void withNotice(tab.id, requestFill(tab.id));
  }
});

chrome.runtime.onInstalled.addListener(() => void syncAutoScripts());
chrome.runtime.onStartup.addListener(() => void syncAutoScripts());
chrome.permissions.onRemoved.addListener(() => void syncAutoScripts());
