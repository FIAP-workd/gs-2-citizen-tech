import { describe, expect, it } from "vitest";
import { relayEndpoints } from "../communication/relay";
import { decodeQrPayload, encodeQrPayload, type QrPayload } from "./index";

const payload: QrPayload = {
  v: 1,
  t: "cs-pair",
  relay: "ws://192.168.0.10:8000/v1/ws",
  pid: "4f6c1c1e-7d0a-4c8e-9b8e-2d9b5a3f0c11",
  tok: "abc",
  pk: "def",
  name: "Chrome — Notebook da Ana",
};

describe("QR Code de pareamento", () => {
  it("codifica só em ASCII e decodifica de volta", () => {
    const text = encodeQrPayload(payload);
    expect(/^[\x20-\x7e]*$/.test(text)).toBe(true);
    expect(decodeQrPayload(text)).toEqual(payload);
  });

  it("não contém senha, chave privada nem token do dispositivo", () => {
    expect(Object.keys(JSON.parse(encodeQrPayload(payload))).sort()).toEqual(
      ["name", "pid", "pk", "relay", "t", "tok", "v"],
    );
  });

  it("recusa conteúdo que não é de pareamento", () => {
    expect(decodeQrPayload("https://exemplo.com")).toBeNull();
    expect(decodeQrPayload(JSON.stringify({ ...payload, t: "outro" }))).toBeNull();
  });
});

describe("relayEndpoints", () => {
  it("deriva HTTP e WebSocket do endereço configurado", () => {
    expect(relayEndpoints("http://localhost:8000")).toEqual({
      http: "http://localhost:8000",
      ws: "ws://localhost:8000/v1/ws",
    });
    expect(relayEndpoints("https://relay.exemplo/")).toEqual({
      http: "https://relay.exemplo",
      ws: "wss://relay.exemplo/v1/ws",
    });
    expect(relayEndpoints("wss://relay.exemplo/v1/ws").http).toBe("https://relay.exemplo");
  });
});
