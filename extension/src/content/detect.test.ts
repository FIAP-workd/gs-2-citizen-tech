// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from "vitest";
import { detectFields, summarize } from "./detect";
import { fillLogin, fillNewPassword } from "./fill";

const opts = { checkLayout: false };

function page(html: string): Document {
  document.body.innerHTML = html;
  return document;
}

beforeEach(() => {
  document.body.innerHTML = "";
});

describe("detectFields", () => {
  it("encontra usuário e senha de um login convencional", () => {
    const doc = page(`
      <form>
        <input name="busca" type="search">
        <input id="email" type="email" autocomplete="username">
        <input id="senha" type="password" autocomplete="current-password">
        <button>Entrar</button>
      </form>`);
    const fields = detectFields(doc, opts);
    expect(fields.kind).toBe("login");
    expect(fields.username?.id).toBe("email");
    expect(fields.passwords.map((p) => p.id)).toEqual(["senha"]);
  });

  it("reconhece cadastro por autocomplete=new-password", () => {
    const doc = page(`
      <form>
        <input id="u" name="usuario">
        <input id="p" type="password" autocomplete="new-password">
      </form>`);
    expect(detectFields(doc, opts).kind).toBe("signup");
  });

  it("reconhece cadastro com senha e confirmação", () => {
    const doc = page(`
      <form>
        <input id="u" type="email">
        <input id="p1" type="password">
        <input id="p2" type="password">
      </form>`);
    const fields = detectFields(doc, opts);
    expect(fields.kind).toBe("signup");
    expect(fields.newPasswords).toHaveLength(2);
  });

  it("ignora campos ocultos (armadilha para capturar a senha)", () => {
    const doc = page(`
      <form>
        <input id="u" type="text">
        <input id="armadilha" type="password" style="display:none">
        <div hidden><input id="armadilha2" type="password"></div>
        <input id="armadilha3" type="password" style="opacity:0">
        <input id="real" type="password">
      </form>`);
    const fields = detectFields(doc, opts);
    expect(fields.passwords.map((p) => p.id)).toEqual(["real"]);
  });

  it("ignora campos desabilitados ou somente leitura", () => {
    const doc = page(`<input type="password" disabled><input type="password" readonly>`);
    expect(detectFields(doc, opts).kind).toBe("none");
  });

  it("devolve none sem campo de senha", () => {
    expect(detectFields(page(`<form><input type="text"></form>`), opts).kind).toBe("none");
  });

  it("o resumo enviado ao service worker não inclui o conteúdo da senha", () => {
    const doc = page(`<form><input id="u" value="ana@exemplo.com"><input type="password" value="segredo"></form>`);
    const summary = summarize(detectFields(doc, opts));
    expect(JSON.stringify(summary)).not.toContain("segredo");
    expect(summary.usernameValue).toBe("ana@exemplo.com");
  });
});

describe("preenchimento", () => {
  it("preenche login e dispara eventos de input", () => {
    const doc = page(`<form><input id="u"><input id="p" type="password"></form>`);
    const events: string[] = [];
    doc.getElementById("p")!.addEventListener("input", () => events.push("input"));
    expect(fillLogin(detectFields(doc, opts), "ana", "s3nh@")).toBe(true);
    expect((doc.getElementById("u") as HTMLInputElement).value).toBe("ana");
    expect((doc.getElementById("p") as HTMLInputElement).value).toBe("s3nh@");
    expect(events).toEqual(["input"]);
  });

  it("preenche senha nova e confirmação com o mesmo valor", () => {
    const doc = page(`<form><input id="u"><input id="p1" type="password"><input id="p2" type="password"></form>`);
    expect(fillNewPassword(detectFields(doc, opts), "Nova#Senha")).toBe(true);
    expect((doc.getElementById("p1") as HTMLInputElement).value).toBe("Nova#Senha");
    expect((doc.getElementById("p2") as HTMLInputElement).value).toBe("Nova#Senha");
  });
});
