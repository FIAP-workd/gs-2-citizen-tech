// Preenchimento dos campos detectados.

import type { DetectedFields } from "./detect";

/** Define o valor como um usuário faria, para que frameworks (React, Vue) percebam. */
export function setInputValue(input: HTMLInputElement, value: string): void {
  const view = input.ownerDocument.defaultView;
  const proto = view?.HTMLInputElement.prototype ?? HTMLInputElement.prototype;
  const setter = Object.getOwnPropertyDescriptor(proto, "value")?.set;
  input.focus();
  if (setter) setter.call(input, value);
  else input.value = value;
  input.dispatchEvent(new Event("input", { bubbles: true }));
  input.dispatchEvent(new Event("change", { bubbles: true }));
  input.blur();
}

export function fillLogin(fields: DetectedFields, username: string, password: string): boolean {
  const target = fields.passwords[0];
  if (!target) return false;
  if (fields.username && username) setInputValue(fields.username, username);
  setInputValue(target, password);
  return true;
}

/** Preenche senha nova + confirmação. Sem formulário de cadastro, usa o campo de senha único. */
export function fillNewPassword(fields: DetectedFields, password: string): boolean {
  const targets = fields.newPasswords.length > 0 ? fields.newPasswords : fields.passwords.slice(0, 1);
  if (targets.length === 0) return false;
  for (const input of targets) setInputValue(input, password);
  return true;
}
