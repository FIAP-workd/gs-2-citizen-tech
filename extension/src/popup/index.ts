// Interface do popup: pareamento, preencher, gerar-e-salvar e configurações.

import qrcode from "qrcode-generator";
import type { ActionResult, BackgroundState, PopupRequest } from "../background/messages";
import { checkPageUrl, describeOriginProblem } from "../domain";
import type { PairingView } from "../pairing";

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;

let state: BackgroundState | null = null;
let tab: chrome.tabs.Tab | undefined;
let countdownTimer: ReturnType<typeof setInterval> | null = null;

function send<T>(request: PopupRequest): Promise<T> {
  return chrome.runtime.sendMessage(request) as Promise<T>;
}

function show(id: string, visible: boolean): void {
  $(id).hidden = !visible;
}

function say(text: string, tone: "ok" | "error" | "info" = "info"): void {
  const el = $<HTMLParagraphElement>("message");
  el.textContent = text;
  el.className = `message ${tone}`;
  el.hidden = !text;
}

function drawQr(text: string): void {
  const qr = qrcode(0, "M");
  qr.addData(text);
  qr.make();
  const canvas = $<HTMLCanvasElement>("qr");
  const ctx = canvas.getContext("2d")!;
  const count = qr.getModuleCount();
  const margin = 2;
  const cell = Math.floor(canvas.width / (count + margin * 2));
  const offset = Math.floor((canvas.width - cell * count) / 2);
  ctx.fillStyle = "#fff";
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.fillStyle = "#000";
  for (let row = 0; row < count; row++) {
    for (let col = 0; col < count; col++) {
      if (qr.isDark(row, col)) ctx.fillRect(offset + col * cell, offset + row * cell, cell, cell);
    }
  }
}

function startCountdown(expiresAt: number): void {
  if (countdownTimer) clearInterval(countdownTimer);
  const tick = () => {
    const left = Math.max(0, Math.round((expiresAt - Date.now()) / 1000));
    $("countdown").textContent = `${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")}`;
  };
  tick();
  countdownTimer = setInterval(tick, 1000);
}

function renderPairing(view: PairingView): void {
  show("pair-idle", view.stage === "idle" || view.stage === "failed" || view.stage === "done");
  show("pair-waiting", view.stage === "waiting");
  show("pair-claimed", view.stage === "claimed");
  if (view.stage === "waiting" && view.qrText && view.expiresAt) {
    drawQr(view.qrText);
    startCountdown(view.expiresAt);
  }
  if (view.stage === "claimed") {
    $("claimed-name").textContent = view.peerName ?? "";
    const sas = view.sas ?? "";
    $("sas").textContent = `${sas.slice(0, 3)} ${sas.slice(3)}`;
  }
  if (view.stage === "failed" && view.message) say(view.message, "error");
  if (view.stage === "done") say(`Pronto! Este computador está conectado a ${view.peerName}.`, "ok");
}

function currentOrigin(): string | null {
  if (!state || !tab?.url) return null;
  const check = checkPageUrl(tab.url, state.settings);
  return check.ok ? check.origin : null;
}

function render(): void {
  if (!state) return;
  const pill = $("peer-status");
  if (!state.paired) {
    pill.textContent = "Não pareado";
    pill.className = "pill";
  } else {
    pill.textContent = state.peerOnline ? `${state.peerName}: on-line` : `${state.peerName}: desconectado`;
    pill.className = `pill ${state.peerOnline ? "online" : "offline"}`;
  }

  show("unpaired", !state.paired);
  show("paired", state.paired);
  show("unpair", state.paired);
  if (!state.paired) renderPairing(state.pairing);

  if (state.paired) {
    const check = checkPageUrl(tab?.url, state.settings);
    $("site-host").textContent = check.ok ? check.hostname : tab?.url ? new URL(tab.url).host || "—" : "—";
    const problem = $("site-problem");
    problem.hidden = check.ok;
    if (!check.ok) problem.textContent = describeOriginProblem(check.reason);
    for (const id of ["fill", "generate", "auto"]) {
      ($(id) as HTMLButtonElement | HTMLInputElement).disabled = !check.ok;
    }
    const origin = currentOrigin();
    $<HTMLInputElement>("auto").checked = Boolean(origin && state.autoSites.includes(origin));
    $<HTMLSelectElement>("length").value = String(state.settings.generatorLength);
    show("retry-save", Boolean(state.pendingSave));
  }

  const settings = state.settings;
  const relayInput = $<HTMLInputElement>("relay-url");
  if (document.activeElement !== relayInput) relayInput.value = settings.relayUrl;
  const nameInput = $<HTMLInputElement>("device-name");
  if (document.activeElement !== nameInput) nameInput.value = settings.deviceName;
  $<HTMLInputElement>("allow-dev").checked = settings.allowDevHttp;
}

async function refresh(): Promise<void> {
  state = await send<BackgroundState>({ type: "getState" });
  render();
}

async function runAction(button: HTMLButtonElement, busyText: string, request: PopupRequest): Promise<void> {
  const original = button.textContent;
  button.disabled = true;
  button.textContent = busyText;
  say("");
  try {
    const result = await send<ActionResult>(request);
    say(result.message, result.ok ? "ok" : "error");
  } finally {
    button.disabled = false;
    button.textContent = original;
    await refresh();
  }
}

function bind(): void {
  $("pair-start").addEventListener("click", async () => {
    say("");
    const view = await send<PairingView>({ type: "startPairing" });
    if (view.stage === "failed" && view.message) say(view.message, "error");
    await refresh();
  });

  $("pair-cancel").addEventListener("click", async () => {
    await send({ type: "cancelPairing" });
    await refresh();
  });

  $("copy-qr").addEventListener("click", async () => {
    const text = state?.pairing.qrText;
    if (!text) return;
    await navigator.clipboard.writeText(text);
    say("Código copiado. Cole no app do celular.", "ok");
  });

  $("fill").addEventListener("click", () => {
    if (tab?.id === undefined) return;
    say("Pedido enviado. Confirme no celular, se ele pedir.");
    void runAction($("fill"), "Aguardando o celular…", { type: "fill", tabId: tab.id });
  });

  $("generate").addEventListener("click", () => {
    if (tab?.id === undefined) return;
    const length = Number($<HTMLSelectElement>("length").value);
    void runAction($("generate"), "Gerando e salvando…", { type: "generateAndSave", tabId: tab.id, length });
  });

  $("length").addEventListener("change", async () => {
    if (!state) return;
    const generatorLength = Number($<HTMLSelectElement>("length").value);
    await send({ type: "saveSettings", settings: { ...state.settings, generatorLength } });
  });

  $("retry-save").addEventListener("click", () => {
    void runAction($("retry-save"), "Salvando…", { type: "retrySave" });
  });

  $("auto").addEventListener("change", async (event) => {
    const input = event.target as HTMLInputElement;
    const origin = currentOrigin();
    if (!origin) return;
    const url = new URL(origin);
    const pattern = `${url.protocol}//${url.hostname}/*`;
    if (input.checked) {
      // Precisa ser chamado direto no clique (gesto do usuário).
      const granted = await chrome.permissions.request({ origins: [pattern] });
      if (!granted) {
        input.checked = false;
        say("Sem a permissão deste site, o preenchimento automático fica desligado.", "error");
        return;
      }
    }
    const result = await send<ActionResult>({ type: "setAuto", origin, enabled: input.checked });
    say(result.message, result.ok ? "ok" : "error");
    await refresh();
  });

  $("settings").addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!state) return;
    const relayUrl = $<HTMLInputElement>("relay-url").value.trim();
    try {
      const parsed = new URL(relayUrl);
      if (!["http:", "https:", "ws:", "wss:"].includes(parsed.protocol)) throw new Error();
    } catch {
      say("Endereço do servidor inválido. Exemplo: http://localhost:8000", "error");
      return;
    }
    const result = await send<ActionResult>({
      type: "saveSettings",
      settings: {
        ...state.settings,
        relayUrl,
        deviceName: $<HTMLInputElement>("device-name").value.trim() || state.settings.deviceName,
        allowDevHttp: $<HTMLInputElement>("allow-dev").checked,
      },
    });
    say(result.message, "ok");
    await refresh();
  });

  $("unpair").addEventListener("click", () => {
    if (!confirm("Desconectar este computador? Será preciso parear de novo para preencher senhas.")) return;
    void runAction($("unpair"), "Desconectando…", { type: "unpair" });
  });

  chrome.runtime.onMessage.addListener((message) => {
    if (message?.type === "bg.changed") void refresh();
  });
}

async function main(): Promise<void> {
  [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  bind();
  await refresh();
}

void main();
