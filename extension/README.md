# Chave Segura — Extensão Chrome

TypeScript + Manifest V3, empacotada com esbuild.

```bash
npm ci
npm run ci        # typecheck + testes (vitest) + build
npm run smoke -- http://localhost:8000 qr.txt   # teste ponta a ponta com o celular simulado
```

Para carregar no Chrome: `chrome://extensions` → modo do desenvolvedor →
“Carregar sem compactação” → pasta `dist/`.

## O que ela faz

- **Preencher login:** detecta o formulário, pede a credencial ao celular
  pelo canal E2E e preenche só se o hostname for **exatamente** o da conta,
  a página for HTTPS (ou localhost/`*.test` no modo de desenvolvimento) e a
  autorização não tiver expirado.
- **Gerar senha forte e salvar:** gera a senha no navegador
  (`crypto.getRandomValues`), preenche senha e confirmação e manda o celular
  salvar (`credential.save`).
- **Preencher ao abrir o site:** com permissão pedida só para aquele site,
  registra um content script que inicia o pedido quando a página de login
  carrega. O celular decide se libera sem confirmar.
- **Pareamento por QR Code** com código de verificação de 6 dígitos.
- Atalho **Alt+Shift+L**.

## Módulos (`src/`)

| Módulo | Conteúdo |
| --- | --- |
| `domain` | Normalização e comparação exata de hostnames; política de HTTPS |
| `security` | Gerador de senhas, anti-replay, validação da resposta antes de preencher |
| `communication` | Canal E2E (libsodium), cliente WebSocket e REST do relay, tipos do contrato |
| `pairing` | Conteúdo do QR Code |
| `content` | Detecção de formulários (só campos visíveis), preenchimento, aviso na página, início automático |
| `background` | Service worker: coordena tudo e guarda as chaves |
| `popup` | Interface |

Permissões: `activeTab`, `scripting`, `storage`; permissões de site só
opcionais, pedidas quando o usuário liga o preenchimento automático.

Roteiro de testes: [docs/test-plan.md](../docs/test-plan.md).
