import { describe, expect, it } from "vitest";
import { estimatedEntropyBits, generatePassword, randomIndex } from "./generator";

describe("generatePassword", () => {
  it("respeita o tamanho e inclui todos os tipos escolhidos", () => {
    for (let i = 0; i < 200; i++) {
      const password = generatePassword({
        length: 12,
        lowercase: true,
        uppercase: true,
        digits: true,
        symbols: true,
      });
      expect(password).toHaveLength(12);
      expect(password).toMatch(/[a-z]/);
      expect(password).toMatch(/[A-Z]/);
      expect(password).toMatch(/[2-9]/);
      expect(password).toMatch(/[!@#$%&*\-_=+?]/);
    }
  });

  it("usa só os tipos escolhidos", () => {
    const password = generatePassword({
      length: 30,
      lowercase: false,
      uppercase: false,
      digits: true,
      symbols: false,
    });
    expect(password).toMatch(/^[2-9]{30}$/);
  });

  it("não repete senhas", () => {
    const seen = new Set(Array.from({ length: 500 }, () => generatePassword()));
    expect(seen.size).toBe(500);
  });

  it("recusa configurações inválidas", () => {
    expect(() => generatePassword({ length: 4, lowercase: true, uppercase: false, digits: false, symbols: false })).toThrow();
    expect(() => generatePassword({ length: 16, lowercase: false, uppercase: false, digits: false, symbols: false })).toThrow();
  });

  it("estima entropia de uma senha padrão acima de 100 bits", () => {
    expect(estimatedEntropyBits({ length: 20, lowercase: true, uppercase: true, digits: true, symbols: true })).toBeGreaterThan(100);
  });
});

describe("randomIndex", () => {
  it("rejeita valores que causariam viés", () => {
    const values = [0xffffffff, 7];
    const index = randomIndex(10, (buf) => {
      buf[0] = values.shift()!;
    });
    expect(index).toBe(7);
  });
});
