// Normalização e validação de domínios (architecture.md §8, threat-model E1–E3).
//
// Regra central: o hostname da página é comparado de forma EXATA com o domínio
// cadastrado, depois de normalizado. Nunca por substring, sufixo ou prefixo.
// Assim `gmail.com.site-falso.com` nunca é tratado como `gmail.com`.

export const EXTENSION_PROTOCOL_VERSION = 1;

/** Hosts aceitos em HTTP somente quando o modo de desenvolvimento está ligado. */
const DEV_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]"]);
const DEV_SUFFIXES = [".localhost", ".test"];

/**
 * Normaliza um hostname ou URL: minúsculas, sem ponto final, IDN em punycode,
 * sem porta, usuário ou caminho. Devolve `null` se a entrada não for válida.
 */
export function normalizeHostname(input: string): string | null {
  const raw = input.trim();
  if (!raw) return null;
  const candidate = /^[a-z][a-z0-9+.-]*:\/\//i.test(raw) ? raw : `https://${raw}`;
  let url: URL;
  try {
    url = new URL(candidate);
  } catch {
    return null;
  }
  let host = url.hostname.toLowerCase();
  while (host.endsWith(".")) host = host.slice(0, -1);
  if (!host || host.includes("..")) return null;
  return host;
}

/** Comparação exata após normalização. */
export function hostnamesMatch(a: string, b: string): boolean {
  const left = normalizeHostname(a);
  const right = normalizeHostname(b);
  return left !== null && left === right;
}

export function isDevHost(hostname: string): boolean {
  return DEV_HOSTS.has(hostname) || DEV_SUFFIXES.some((suffix) => hostname.endsWith(suffix));
}

/**
 * Indica se `hostname` contém `knownDomain` sem ser igual a ele — padrão comum
 * de phishing (`gmail.com.site-falso.com`, `login-gmail.com`).
 */
export function looksLike(hostname: string, knownDomain: string): boolean {
  const host = normalizeHostname(hostname);
  const known = normalizeHostname(knownDomain);
  if (!host || !known || host === known) return false;
  return host.includes(known) || host.includes(known.split(".")[0] ?? known);
}

export type OriginCheck =
  | { ok: true; origin: string; hostname: string; secure: boolean }
  | { ok: false; reason: "invalid" | "insecure" | "unsupported" };

export interface OriginPolicy {
  /** Permite HTTP em localhost e *.test (exceção explícita de desenvolvimento). */
  allowDevHttp: boolean;
}

/**
 * Decide se a página pode receber credenciais.
 * - exige HTTPS;
 * - HTTP só em hosts de desenvolvimento e com a exceção ligada;
 * - recusa origens opacas (`data:`, `blob:`, `file:`, `chrome:` …).
 */
export function checkPageUrl(pageUrl: string | undefined, policy: OriginPolicy): OriginCheck {
  if (!pageUrl) return { ok: false, reason: "invalid" };
  let url: URL;
  try {
    url = new URL(pageUrl);
  } catch {
    return { ok: false, reason: "invalid" };
  }
  if (url.protocol !== "https:" && url.protocol !== "http:") {
    return { ok: false, reason: "unsupported" };
  }
  const hostname = normalizeHostname(url.href);
  if (!hostname || url.origin === "null") return { ok: false, reason: "invalid" };
  if (url.protocol === "http:" && !(policy.allowDevHttp && isDevHost(hostname))) {
    return { ok: false, reason: "insecure" };
  }
  return { ok: true, origin: url.origin, hostname, secure: url.protocol === "https:" };
}

/** Texto simples para o usuário a partir do motivo de bloqueio. */
export function describeOriginProblem(reason: "invalid" | "insecure" | "unsupported"): string {
  switch (reason) {
    case "insecure":
      return "Esta página não usa conexão segura (HTTPS). O preenchimento foi bloqueado.";
    case "unsupported":
      return "Esta página não aceita preenchimento (página interna do navegador ou arquivo).";
    default:
      return "Não foi possível verificar o endereço desta página.";
  }
}
