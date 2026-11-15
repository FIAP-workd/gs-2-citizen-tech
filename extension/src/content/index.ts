// Content script: detecção de formulários e preenchimento.
// Injetado sob demanda (activeTab) ou registrado só nos sites em que o usuário
// ligou o preenchimento automático. Nunca recebe o cofre nem chaves: apenas a
// credencial da página atual, no momento do preenchimento.

import { hostnamesMatch } from "../domain";
import { detectFields, summarize } from "./detect";
import { fillLogin, fillNewPassword } from "./fill";
import type { ContentRequest, DetectReply, FillReply } from "./messages";
import { showNotice } from "./notice";

declare global {
  interface Window {
    __chaveSeguraContent?: boolean;
  }
}

const isTop = window.top === window;

function sameHost(expected: string): boolean {
  return isTop && hostnamesMatch(location.hostname, expected);
}

function handle(message: ContentRequest): DetectReply | FillReply | undefined {
  switch (message.type) {
    case "cs.detect":
      return { hostname: location.hostname, top: isTop, form: summarize(detectFields(document)) };

    case "cs.fill": {
      // Revalida a origem dentro da própria página antes de inserir a senha.
      if (!sameHost(message.hostname)) return { filled: false, reason: "origin" };
      let password: string | null = message.password;
      const filled = fillLogin(detectFields(document), message.username, password);
      password = null;
      return { filled, reason: filled ? undefined : "no_form" };
    }

    case "cs.fillGenerated": {
      if (!sameHost(message.hostname)) return { filled: false, reason: "origin" };
      const filled = fillNewPassword(detectFields(document), message.password);
      return { filled, reason: filled ? undefined : "no_form" };
    }

    case "cs.notice":
      if (isTop) showNotice(message.text, message.tone);
      return undefined;
  }
}

if (!window.__chaveSeguraContent) {
  window.__chaveSeguraContent = true;
  chrome.runtime.onMessage.addListener((message: ContentRequest, sender, sendResponse) => {
    if (sender.id !== chrome.runtime.id) return false;
    sendResponse(handle(message));
    return false;
  });
}
