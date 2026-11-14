import { describe, expect, it } from "vitest";
import { EXTENSION_PROTOCOL_VERSION } from "./index";

describe("domain", () => {
  it("expõe a versão do protocolo de mensagens", () => {
    expect(EXTENSION_PROTOCOL_VERSION).toBe(1);
  });
});
