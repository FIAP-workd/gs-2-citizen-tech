// Registrado apenas nos sites em que o usuário ligou o preenchimento automático
// (permissão de site concedida explicitamente). Ao encontrar um formulário de
// login no frame de topo, pede ao service worker que inicie a solicitação.
// Quem decide se libera sem confirmação é o celular (política do modo automático).

import { detectFields } from "./detect";

declare global {
  interface Window {
    __chaveSeguraAuto?: boolean;
  }
}

function tryStart(): boolean {
  if (detectFields(document).kind !== "login") return false;
  chrome.runtime.sendMessage({ type: "cs.autoStart" }).catch(() => undefined);
  return true;
}

if (window.top === window && !window.__chaveSeguraAuto) {
  window.__chaveSeguraAuto = true;
  if (!tryStart()) {
    // Páginas que montam o formulário depois do carregamento (SPA): observa por 10 s.
    const observer = new MutationObserver(() => {
      if (tryStart()) observer.disconnect();
    });
    observer.observe(document.documentElement, { childList: true, subtree: true });
    setTimeout(() => observer.disconnect(), 10_000);
  }
}
