import { describe, expect, it } from "vitest";
import {
  EXTENSION_PROTOCOL_VERSION,
  checkPageUrl,
  hostnamesMatch,
  looksLike,
  normalizeHostname,
} from "./index";

describe("normalizeHostname", () => {
  it("normaliza maiúsculas, ponto final, porta e caminho", () => {
    expect(normalizeHostname("HTTPS://Accounts.Exemplo.com.:443/login?x=1")).toBe(
      "accounts.exemplo.com",
    );
    expect(normalizeHostname("exemplo.com")).toBe("exemplo.com");
  });

  it("converte domínios internacionais para punycode", () => {
    expect(normalizeHostname("https://bánco.com")).toBe("xn--bnco-5na.com");
  });

  it("recusa entradas inválidas", () => {
    expect(normalizeHostname("")).toBeNull();
    expect(normalizeHostname("https://")).toBeNull();
    expect(normalizeHostname("a..b")).toBeNull();
  });
});

describe("hostnamesMatch — domínio semelhante ao original", () => {
  it("aceita apenas o mesmo hostname", () => {
    expect(hostnamesMatch("https://gmail.com/login", "gmail.com")).toBe(true);
  });

  it.each([
    "gmail.com.site-falso.com",
    "login-gmail.com",
    "gmail.co",
    "mail.gmail.com",
    "gmail.com-seguranca.net",
    "xn--gmal-nza.com", // gmaíl.com
  ])("bloqueia %s", (fake) => {
    expect(hostnamesMatch(fake, "gmail.com")).toBe(false);
  });

  it("identifica imitações para alertar o usuário", () => {
    expect(looksLike("gmail.com.site-falso.com", "gmail.com")).toBe(true);
    expect(looksLike("gmail.com", "gmail.com")).toBe(false);
  });
});

describe("checkPageUrl", () => {
  const strict = { allowDevHttp: false };
  const dev = { allowDevHttp: true };

  it("aceita HTTPS", () => {
    expect(checkPageUrl("https://exemplo.com/entrar", strict)).toEqual({
      ok: true,
      origin: "https://exemplo.com",
      hostname: "exemplo.com",
      secure: true,
    });
  });

  it("bloqueia HTTP fora do modo de desenvolvimento", () => {
    expect(checkPageUrl("http://exemplo.com", strict)).toEqual({ ok: false, reason: "insecure" });
    expect(checkPageUrl("http://localhost:8080", strict)).toEqual({ ok: false, reason: "insecure" });
  });

  it("aceita HTTP só em hosts de desenvolvimento com a exceção ligada", () => {
    expect(checkPageUrl("http://localhost:8080/login.html", dev).ok).toBe(true);
    expect(checkPageUrl("http://banco.test/", dev).ok).toBe(true);
    expect(checkPageUrl("http://exemplo.com", dev)).toEqual({ ok: false, reason: "insecure" });
  });

  it("recusa origens opacas e páginas internas", () => {
    expect(checkPageUrl("data:text/html,<form>", dev).ok).toBe(false);
    expect(checkPageUrl("file:///C:/login.html", dev).ok).toBe(false);
    expect(checkPageUrl("chrome://settings", dev).ok).toBe(false);
    expect(checkPageUrl(undefined, dev).ok).toBe(false);
  });
});

it("expõe a versão do protocolo de mensagens", () => {
  expect(EXTENSION_PROTOCOL_VERSION).toBe(1);
});
