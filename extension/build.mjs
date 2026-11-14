// Empacota os pontos de entrada da extensão em dist/ e copia os arquivos estáticos.
import { build } from "esbuild";
import { cpSync, rmSync } from "node:fs";

const outdir = "dist";

rmSync(outdir, { recursive: true, force: true });

const common = {
  outdir,
  bundle: true,
  target: "chrome120",
  minify: true,
  logLevel: "info",
};

// Service worker e popup rodam como módulos ES.
await build({
  ...common,
  entryPoints: { background: "src/background/index.ts", popup: "src/popup/index.ts" },
  format: "esm",
});

// Content scripts injetados por chrome.scripting não podem ser módulos ES.
await build({
  ...common,
  entryPoints: { content: "src/content/index.ts" },
  format: "iife",
});

cpSync("public", outdir, { recursive: true });
