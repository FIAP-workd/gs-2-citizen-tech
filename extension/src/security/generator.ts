// Gerador de senhas fortes com crypto.getRandomValues (sem viés de módulo).

export interface GeneratorOptions {
  length: number;
  lowercase: boolean;
  uppercase: boolean;
  digits: boolean;
  symbols: boolean;
}

export const DEFAULT_GENERATOR: GeneratorOptions = {
  length: 20,
  lowercase: true,
  uppercase: true,
  digits: true,
  symbols: true,
};

// Sem caracteres ambíguos (0/O, 1/l/I) para facilitar a leitura em voz alta.
const SETS = {
  lowercase: "abcdefghijkmnopqrstuvwxyz",
  uppercase: "ABCDEFGHJKLMNPQRSTUVWXYZ",
  digits: "23456789",
  symbols: "!@#$%&*-_=+?",
} as const;

export const MIN_LENGTH = 8;
export const MAX_LENGTH = 64;

/** Inteiro uniforme em [0, max) por amostragem com rejeição. */
export function randomIndex(max: number, random: (buf: Uint32Array<ArrayBuffer>) => void = fillRandom): number {
  if (max <= 0 || max > 2 ** 32) throw new RangeError("max inválido");
  const limit = Math.floor(2 ** 32 / max) * max;
  const buf = new Uint32Array(1);
  for (;;) {
    random(buf);
    const value = buf[0]!;
    if (value < limit) return value % max;
  }
}

function fillRandom(buf: Uint32Array<ArrayBuffer>): void {
  crypto.getRandomValues(buf);
}

export function generatePassword(options: GeneratorOptions = DEFAULT_GENERATOR): string {
  const length = Math.round(options.length);
  if (!Number.isFinite(length) || length < MIN_LENGTH || length > MAX_LENGTH) {
    throw new RangeError(`O tamanho deve ficar entre ${MIN_LENGTH} e ${MAX_LENGTH}.`);
  }
  const groups = (Object.keys(SETS) as (keyof typeof SETS)[])
    .filter((key) => options[key])
    .map((key) => SETS[key]);
  if (groups.length === 0) throw new RangeError("Escolha ao menos um tipo de caractere.");

  const alphabet = groups.join("");
  // Garante ao menos um caractere de cada grupo escolhido.
  const chars = groups.map((group) => group[randomIndex(group.length)]!);
  while (chars.length < length) chars.push(alphabet[randomIndex(alphabet.length)]!);
  // Embaralha (Fisher–Yates) para que os obrigatórios não fiquem no início.
  for (let i = chars.length - 1; i > 0; i--) {
    const j = randomIndex(i + 1);
    [chars[i], chars[j]] = [chars[j]!, chars[i]!];
  }
  return chars.join("");
}

/** Entropia estimada em bits para senhas geradas com as opções dadas. */
export function estimatedEntropyBits(options: GeneratorOptions): number {
  const size = (Object.keys(SETS) as (keyof typeof SETS)[])
    .filter((key) => options[key])
    .reduce((total, key) => total + SETS[key].length, 0);
  return size === 0 ? 0 : Math.floor(options.length * Math.log2(size));
}
