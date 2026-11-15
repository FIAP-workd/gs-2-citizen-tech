// Aviso discreto na própria página (usado no preenchimento automático, quando o
// popup está fechado). Fica em um shadow root fechado para a página não ler.

const COLORS = { ok: "#1b5e20", warn: "#7a4f01", error: "#b71c1c" } as const;

export function showNotice(text: string, tone: keyof typeof COLORS): void {
  const host = document.createElement("div");
  host.style.cssText = "position:fixed;z-index:2147483647;right:16px;bottom:16px;";
  const root = host.attachShadow({ mode: "closed" });
  const box = document.createElement("div");
  box.setAttribute("role", "status");
  box.textContent = `Chave Segura: ${text}`;
  box.style.cssText = [
    "font:600 16px/1.4 system-ui,sans-serif",
    "color:#fff",
    `background:${COLORS[tone]}`,
    "padding:12px 16px",
    "border-radius:10px",
    "max-width:360px",
    "box-shadow:0 4px 16px rgba(0,0,0,.25)",
  ].join(";");
  root.append(box);
  document.documentElement.append(host);
  setTimeout(() => host.remove(), 6000);
}
