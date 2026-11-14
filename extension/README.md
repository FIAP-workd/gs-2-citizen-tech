# Chave Segura — Extensão Chrome

TypeScript + Manifest V3, empacotada com esbuild. Módulos em `src/`:
`background`, `content`, `popup`, `domain`, `communication`, `security`, `pairing`.

```bash
npm ci
npm run ci        # typecheck + testes (vitest) + build
```

Para carregar no Chrome: `chrome://extensions` → modo do desenvolvedor →
"Carregar sem compactação" → pasta `dist/`.
