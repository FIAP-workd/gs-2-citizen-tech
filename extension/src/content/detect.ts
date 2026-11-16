// Detecção de formulários HTML convencionais de login e de cadastro (Fase 3).
//
// Regras de segurança (threat-model E4): só campos visíveis, habilitados e
// editáveis; campos ocultos nunca recebem a senha.

export type FormKind = "login" | "signup" | "none";

export interface DetectedFields {
  kind: FormKind;
  username: HTMLInputElement | null;
  passwords: HTMLInputElement[];
  /** Campos que recebem uma senha nova (cadastro/troca de senha). */
  newPasswords: HTMLInputElement[];
}

export interface DetectOptions {
  /** Confere o tamanho na tela (desligado nos testes, pois o jsdom não faz layout). */
  checkLayout: boolean;
}

const USERNAME_TYPES = new Set(["text", "email", "tel", ""]);
const USERNAME_HINT = /(user|usu[aá]rio|login|e-?mail|cpf|conta|account|identifier)/i;

export function isVisible(el: HTMLElement, options: DetectOptions): boolean {
  if (el.hidden || el.closest("[hidden], [aria-hidden='true'], [inert]")) return false;
  const view = el.ownerDocument.defaultView;
  for (let node: HTMLElement | null = el; node; node = node.parentElement) {
    const style = view?.getComputedStyle(node);
    if (!style) break;
    if (style.display === "none" || style.visibility === "hidden" || style.visibility === "collapse") {
      return false;
    }
    if (Number.parseFloat(style.opacity || "1") < 0.1) return false;
  }
  if (options.checkLayout) {
    const rect = el.getBoundingClientRect();
    if (rect.width < 2 || rect.height < 2) return false;
  }
  return true;
}

function isUsable(input: HTMLInputElement, options: DetectOptions): boolean {
  return !input.disabled && !input.readOnly && isVisible(input, options);
}

function autocompleteTokens(input: HTMLInputElement): string[] {
  return (input.getAttribute("autocomplete") ?? "").toLowerCase().split(/\s+/).filter(Boolean);
}

function usernameScore(input: HTMLInputElement): number {
  const tokens = autocompleteTokens(input);
  if (tokens.includes("username")) return 4;
  if (tokens.includes("email") || input.type === "email") return 3;
  const hint = `${input.name} ${input.id} ${input.placeholder} ${input.getAttribute("aria-label") ?? ""}`;
  return USERNAME_HINT.test(hint) ? 2 : 1;
}

/** Ordem de documento: `a` vem antes de `b`? */
function precedes(a: Element, b: Element): boolean {
  return Boolean(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING);
}

export function detectFields(
  doc: Document,
  options: DetectOptions = { checkLayout: true },
): DetectedFields {
  const allPasswords = Array.from(doc.querySelectorAll<HTMLInputElement>("input[type='password']"))
    .filter((input) => isUsable(input, options));
  if (allPasswords.length === 0) {
    return { kind: "none", username: null, passwords: [], newPasswords: [] };
  }

  // Usa o primeiro formulário (ou o documento) que tenha campo de senha.
  const container: ParentNode = allPasswords[0]!.form ?? doc;
  const passwords = allPasswords.filter((input) => (input.form ?? doc) === container);
  const first = passwords[0]!;

  const candidates = Array.from(container.querySelectorAll<HTMLInputElement>("input"))
    .filter((input) => USERNAME_TYPES.has(input.type) && isUsable(input, options))
    .filter((input) => precedes(input, first));
  let username: HTMLInputElement | null = null;
  let best = 0;
  for (const input of candidates) {
    // Empate: fica o campo mais próximo do campo de senha (o último).
    const score = usernameScore(input);
    if (score >= best) {
      best = score;
      username = input;
    }
  }

  const marked = passwords.filter((input) => autocompleteTokens(input).includes("new-password"));
  const current = passwords.filter((input) => autocompleteTokens(input).includes("current-password"));
  let newPasswords: HTMLInputElement[] = [];
  if (marked.length > 0) newPasswords = marked;
  else if (passwords.length >= 2 && current.length === 0) newPasswords = passwords;

  return {
    kind: newPasswords.length > 0 ? "signup" : "login",
    username,
    passwords,
    newPasswords,
  };
}

/** Resumo sem valores de campos, seguro para mandar ao service worker. */
export interface FormSummary {
  kind: FormKind;
  hasUsername: boolean;
  usernameValue: string;
  passwordFields: number;
}

export function summarize(fields: DetectedFields): FormSummary {
  return {
    kind: fields.kind,
    hasUsername: fields.username !== null,
    // Só o usuário digitado (e-mail/login) — nunca o conteúdo de campos de senha.
    usernameValue: fields.username?.value.trim().slice(0, 256) ?? "",
    passwordFields: fields.passwords.length,
  };
}
