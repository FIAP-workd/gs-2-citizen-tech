# Guia de testes — Chave Segura

Este guia mostra **como testar cada etapa do projeto**, do cofre local no
Android até o preenchimento automático no Chrome. Cada etapa tem:
o que testar, o passo a passo, o resultado esperado e o critério de
conclusão (o mesmo do [README](../README.md) §7 e do
[roadmap](roadmap.md)).

> Use **somente contas e senhas fictícias**. Nada aqui deve ser testado
> com a sua conta real de banco, e-mail ou rede social.

## Sumário

- [0. Preparação do ambiente](#0-preparação-do-ambiente)
- [1. Testes automatizados (um comando por projeto)](#1-testes-automatizados)
- [Etapa 1 — Fundação do aplicativo](#etapa-1--fundação-do-aplicativo)
- [Etapa 2 — Segurança e geração de senhas](#etapa-2--segurança-e-geração-de-senhas)
- [Etapa 3 — Extensão Chrome](#etapa-3--extensão-chrome)
- [Etapa 4 — Pareamento e comunicação](#etapa-4--pareamento-e-comunicação)
- [Etapa 5 — Autorização, autofill e gerar-e-salvar](#etapa-5--autorização-autofill-e-gerar-e-salvar)
- [Etapa 6 — Testes negativos de segurança](#etapa-6--testes-negativos-de-segurança)
- [Roteiro da demonstração](#roteiro-da-demonstração)
- [Solução de problemas](#solução-de-problemas)

---

## 0. Preparação do ambiente

### Ferramentas

| Ferramenta | Versão | Para quê |
| --- | --- | --- |
| Python | 3.12 | Backend relay e ferramentas de teste |
| Node.js | 24 | Build e testes da extensão |
| Android Studio | Ladybug ou mais novo (JDK 17 + SDK 35) | App Android |
| Google Chrome | 120 ou mais novo | Extensão |
| Docker (opcional) | — | Subir o backend sem instalar Python |

### 0.1 Backend relay

```bash
cd backend
python -m venv .venv
.venv/Scripts/activate          # Windows; no Linux/macOS: source .venv/bin/activate
pip install -e ".[dev]"
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Ou, na raiz do repositório: `docker compose up --build`.

Confira em `http://localhost:8000/health` → `{"status":"ok"}`.

### 0.2 Site de demonstração

Em outro terminal, na raiz do repositório:

```bash
python -m http.server 8080 -d demo-site
```

Abra `http://localhost:8080`. São páginas fictícias de login, cadastro,
campos ocultos (armadilha) e formulário que aparece depois.

### 0.3 Extensão

```bash
cd extension
npm ci
npm run build
```

No Chrome: `chrome://extensions` → ligue **Modo do desenvolvedor** →
**Carregar sem compactação** → escolha a pasta `extension/dist`.
Fixe o ícone da extensão na barra.

No popup, abra **Configurações** e:

1. **Endereço do servidor:** `http://localhost:8000` (veja 0.5 sobre o celular).
2. Marque **Modo de desenvolvimento** (o site de demonstração usa HTTP em `localhost`).
3. Clique em **Salvar configurações**.

### 0.4 App Android

Abra a pasta `android/` no Android Studio, deixe o Gradle sincronizar e
rode a configuração `app` em um emulador (API 26+) ou celular com
**depuração USB**. A build de depuração aceita o relay em HTTP; a de
produção exige HTTPS.

> Na primeira abertura, o Android Studio pode pedir para gerar o Gradle
> wrapper. Aceite e faça commit de `gradlew`, `gradlew.bat` e
> `gradle/wrapper/`.

### 0.5 Fazer o celular enxergar o servidor

O endereço configurado na extensão vai dentro do QR Code; o celular usa
esse endereço. A forma mais simples, para **emulador e celular via USB**:

```bash
adb reverse tcp:8000 tcp:8000
```

Assim `localhost:8000` no celular aponta para o computador, e a extensão
pode continuar com `http://localhost:8000`.

Sem cabo (Wi-Fi): use o IP do computador na rede nas Configurações da
extensão (ex.: `http://192.168.0.10:8000`), libere a porta 8000 no
firewall e mantenha o backend com `--host 0.0.0.0`.

### 0.6 Sem Android? Use o celular simulado

`tools/fake_phone.py` faz o papel do app Android (pareamento, aprovação,
modo automático, salvar senhas) usando uma implementação independente do
protocolo. Serve para testar a extensão e o backend sem aparelho.

```bash
pip install -r tools/requirements.txt     # ou use o .venv do backend
python tools/fake_phone.py add localhost ana@exemplo.com "Senha-Demo-123"
python tools/fake_phone.py pair           # cole o código copiado no popup
python tools/fake_phone.py run            # atende os pedidos (pergunta no terminal)
```

---

## 1. Testes automatizados

| Projeto | Comando | Testes |
| --- | --- | --- |
| Backend | `cd backend && ruff check . && pytest` | 35: pareamento, QR expirado/reutilizado, WebSocket, relay, revogação, rate limit, servidor sem acesso ao conteúdo |
| Extensão | `cd extension && npm run ci` | 54: domínios, gerador, detecção de formulários, canal E2E, anti-replay, validação da resposta, **interoperabilidade com Python** |
| Android | `cd android && ./gradlew testDebugUnitTest` | Cofre (senha errada, banco copiado, AAD), gerador, força, diagnóstico, domínios, política de autorização, anti-replay, QR, SAS |
| Ponta a ponta | ver abaixo | Extensão (TypeScript) ↔ backend real ↔ celular simulado (Python) |

O CI (`.github/workflows/ci.yml`) roda os três primeiros em todo push e
pull request.

**Teste ponta a ponta sem navegador** (com o backend rodando na porta 8000):

```bash
# terminal 1
cd extension && npm run smoke -- http://localhost:8000 qr.txt
# terminal 2, depois que qr.txt aparecer
python tools/fake_phone.py add localhost ana@exemplo.com "Senha-Demo-123"
python tools/fake_phone.py pair "$(cat extension/qr.txt)"
python tools/fake_phone.py run --mode automatic
```

Resultado esperado no terminal 1: o mesmo SAS das duas pontas, um
`fill.response` **approved/automatic**, um **not_found** para
`localhost.golpe.test` e um `credential.save.result` **saved**.

**Vetores de teste do canal E2E:** `docs/test-vectors/e2e-v1.json`
(gerado por `python tools/gen_test_vectors.py`). A extensão e o Android
verificam AAD e SAS contra ele.

---

## Etapa 1 — Fundação do aplicativo

**Objetivo:** criar o cofre, cadastrar, editar, consultar e excluir
credenciais, persistindo tudo cifrado.

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 1.1 | Abra o app pela primeira vez | Tela “Bem-vindo ao Chave Segura” pedindo a senha mestra |
| 1.2 | Tente `123456` como senha mestra | Recusa: “Use pelo menos 10 caracteres” / “fácil de adivinhar” |
| 1.3 | Crie com `cavalo bateria grampo 42` (duas vezes) | Leva 1–2 s (Argon2id) e abre o cofre vazio |
| 1.4 | **Nova conta**: Banco Exemplo · `localhost` · `ana@exemplo.com` · `Senha-Demo-123` · categoria Bancos | Salva; a lista mostra “🏦 Banco Exemplo”, usuário e domínio. Bancos liga “sensível” sozinho |
| 1.5 | Toque na conta | Senha aparece como `••••`; **Mostrar senha** exibe; **Copiar senha** avisa que some em 30 s |
| 1.6 | **Editar** → troque o usuário → Salvar | Detalhe mostra o novo usuário e a data de atualização |
| 1.7 | Busque por `banco`, `localhost` e pela categoria | A conta aparece nos três casos |
| 1.8 | Feche o app (tire dos recentes) e abra de novo | Tela “Cofre bloqueado”: nada aparece antes da senha mestra |
| 1.9 | Desbloqueie | A conta continua lá, com os mesmos dados |
| 1.10 | Crie outra conta e **Excluir conta** | Pede confirmação e some da lista |

**Critério de conclusão:** cadastrar uma senha, fechar o app, abri-lo
novamente e recuperá-la **somente após** a senha mestra (1.4 → 1.9).

---

## Etapa 2 — Segurança e geração de senhas

**Objetivo:** Argon2id, AES-256-GCM com Keystore, gerador, diagnóstico,
biometria e bloqueio automático.

### 2.1 Senha mestra incorreta e limite de tentativas

1. Bloqueie (Ajustes → **Bloquear o cofre agora**).
2. Digite uma senha errada → “Senha mestra incorreta. O cofre continua bloqueado.”
3. Erre 5 vezes seguidas → “Muitas tentativas erradas. Tente de novo às HH:MM.”
   O tempo dobra a cada novo erro.
4. Segurança → **Histórico de segurança** mostra “Senha mestra incorreta” (sem a senha digitada).

### 2.2 Banco sem texto aberto (critério da etapa)

Com uma conta cadastrada (ex.: senha `Senha-Demo-123`):

```bash
adb exec-out run-as com.chavesegura cat databases/chave_segura.db > copia.db
adb exec-out run-as com.chavesegura cat databases/chave_segura.db-wal > copia.db-wal
python tools/inspect_db.py copia.db "Senha-Demo-123" "ana@exemplo.com"
```

Esperado: as colunas `username_encrypted`, `password_encrypted` e
`notes_encrypted` aparecem como `<N bytes cifrados…>`, e a procura termina
com **“não encontrado (ok)”** para cada texto (código de saída 0).
O domínio (`website_domain`) e o título ficam legíveis por decisão de
projeto (busca exata; [architecture.md](architecture.md) §5.1).

> `run-as` funciona na build de depuração. No emulador também dá para usar
> o **Device Explorer** do Android Studio: `/data/data/com.chavesegura/databases/`.

### 2.3 Banco copiado para outro aparelho

Copie o `chave_segura.db` para outro emulador com o app instalado (Device
Explorer → Upload) e tente desbloquear com a senha certa.
Esperado: “Este cofre não pode ser aberto neste aparelho”. A chave do
Keystore do aparelho original é necessária (ADR 0001 §2).
Teste automatizado equivalente: `VaultCryptoTest.banco copiado para outro aparelho…`.

### 2.4 Gerador de senhas

1. Nova conta → **Gerar senha forte**: ajuste o tamanho (12–40) e os tipos
   de caractere; **Gerar outra**; **Usar esta senha**.
2. Esperado: força “Muito forte”, cerca de 100+ bits para 20 caracteres,
   sem caracteres ambíguos (0/O, 1/l/I).
3. **Gerar senha forte e salvar** (em Nova conta, com nome e site
   preenchidos): gera e grava a conta num toque.
4. Segurança → **Gerador de senhas** → **Usar esta senha** abre uma
   conta nova já com a senha.

### 2.5 Diagnóstico de senhas fracas e repetidas

1. Cadastre: “Loja” com `123456`, “E-mail” e “Rede” com a **mesma** senha.
2. Aba **Segurança**.
3. Esperado: “⚠️ Senhas fracas (1)” com Loja e “🔁 Senhas repetidas” com
   E-mail e Rede. Tocar no item abre a conta.

### 2.6 Biometria

1. Cadastre uma digital no emulador (Configurações → Segurança; depois
   `adb -e emu finger touch 1` para encostar o dedo).
2. Ajustes → **Desbloquear com biometria** → confirme a digital.
3. Bloqueie; na tela de bloqueio aparece **Usar biometria** → desbloqueia.
4. Cadastre **outra** digital no aparelho e tente de novo → o app pede a
   senha mestra e desativa a biometria (chave invalidada).

### 2.7 Bloqueio por inatividade e capturas de tela

1. Ajustes → bloquear depois de **1 minuto**. Não toque no app por 1 min → cofre bloqueia sozinho.
2. Tente tirar uma captura de tela ou veja o app nos recentes → tela preta (`FLAG_SECURE`).

**Critério de conclusão:** gerar uma senha forte, identificar uma senha
repetida, desbloquear por biometria e demonstrar que o banco não contém
credenciais em texto aberto.

---

## Etapa 3 — Extensão Chrome

**Objetivo:** detectar domínio e formulário, e preencher só no domínio
certo. Para esta etapa, use o **celular simulado** (0.6) ou o app já
pareado (Etapa 4).

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 3.1 | Abra o popup em `http://localhost:8080/login.html` **sem** o modo de desenvolvimento | “Esta página não usa conexão segura (HTTPS)” e botões desativados |
| 3.2 | Ligue o modo de desenvolvimento e reabra o popup | “Site atual: localhost” |
| 3.3 | Abra o popup em `chrome://extensions` | “Esta página não aceita preenchimento” |
| 3.4 | Em `login.html`, **Preencher login** (celular simulado `run`, responda `s`) | E-mail e senha preenchidos; aviso verde na página |
| 3.5 | Repita, respondendo `n` | “O pedido foi recusado no celular.” Nada preenchido |
| 3.6 | Abra `http://127.0.0.1:8080/login.html` e **Preencher login** | “Não há conta salva para este site” — o domínio `127.0.0.1` não é `localhost` |
| 3.7 | `armadilha.html` → Preencher | Só o campo visível recebe a senha; a página mostra “OK: nenhum campo oculto recebeu a senha” |
| 3.8 | Página sem formulário (`index.html`) → Preencher | “Nenhum formulário de login encontrado nesta página.” |
| 3.9 | Atalho **Alt+Shift+L** em `login.html` | Mesmo efeito do botão |

**Domínio parecido (opcional, mais realista):** adicione ao arquivo
`hosts` (`C:\Windows\System32\drivers\etc\hosts`):

```text
127.0.0.1  banco.test
127.0.0.1  banco.test.golpe.test
```

Cadastre a conta para `banco.test`. Em `http://banco.test:8080/login.html`
o preenchimento funciona; em `http://banco.test.golpe.test:8080/login.html`
é bloqueado, e o app Android mostra “Bloqueado: … não é banco.test. Pode
ser um site falso.”

**Testes automatizados:** `src/domain/index.test.ts` (lista de domínios
falsos), `src/content/detect.test.ts` (campos ocultos, desabilitados) e
`src/security/index.test.ts` (resposta para outro domínio, expirada,
repetida).

**Critério de conclusão:** preencher o formulário de teste com uma
credencial cadastrada (3.4) e bloquear o domínio semelhante (3.6).

---

## Etapa 4 — Pareamento e comunicação

**Objetivo:** conectar celular e Chrome por QR Code, sem o servidor
conseguir ler o conteúdo.

### 4.1 Pareamento normal

1. Extensão → **Parear com o celular**: aparece o QR Code e a contagem de 2:00.
2. App → aba **Computadores** → **Parear computador** → **Ler QR Code com a câmera**.
   No emulador sem câmera: no popup, “Sem câmera? Copiar o código” e, no app,
   cole em “Sem câmera? Cole o código” (no emulador, o texto copiado no PC pode
   ser colado com Ctrl+V no campo).
3. O app mostra o nome do computador → **Continuar**.
4. **Os dois mostram o mesmo número de 6 dígitos** (ex.: `817 452`).
5. App → **Os números são iguais — conectar**.
6. Esperado: o app mostra “✅ Pronto!”; o popup passa a mostrar
   “Meu celular: on-line” e os botões de preencher; o app lista o
   computador em modo **Confirmar no celular**.

### 4.2 Pareamento recusado

Repita até o passo 4 e toque **São diferentes — cancelar**.
Esperado: popup “O pareamento foi recusado no celular”; nada é salvo.

### 4.3 QR Code expirado e reutilizado

- **Expirado:** gere o QR, espere a contagem chegar a 0:00 e leia. App: “O
  código expirou”. Popup: “O QR Code expirou”.
- **Reutilizado:** copie o código, pareie normalmente e tente colar o mesmo
  código de novo (ou `python tools/fake_phone.py pair "<código>"`).
  Esperado: “Este código já foi usado.”

### 4.4 O servidor não lê as credenciais (critério da etapa)

Depois de preencher um login (Etapa 5):

```bash
python tools/inspect_db.py backend/data/relay.sqlite3 "Senha-Demo-123" "ana@exemplo.com" "localhost"
```

Esperado: o banco do relay só tem dispositivos (nome, chave pública, hash
do token), sessões e `fill_requests` com `corr` opaco — e a procura
termina com “não encontrado (ok)”. Nos logs do `uvicorn` aparecem só
linhas como `relay from=9bf35cb9 to=7a17bf3f entregue=True`, sem domínio,
usuário, senha nem ciphertext. Teste automatizado:
`test_fluxo_completo_e2e_sem_vazar_dados_ao_servidor`.

> Com Docker, o banco fica no volume: `docker compose cp backend:/srv/data/relay.sqlite3 relay.sqlite3`.

**Critério de conclusão:** celular e Chrome conectados e trocando
mensagens, com banco e logs do servidor contendo apenas conteúdo cifrado
ou metadados.

---

## Etapa 5 — Autorização, autofill e gerar-e-salvar

Pré-requisito: Etapa 4 concluída, app aberto e cofre **desbloqueado**,
conta “Banco Exemplo” para `localhost` (desligue “sensível” para testar o
automático, ou crie outra conta não sensível).

### 5.1 Modo confirmação (padrão)

1. `http://localhost:8080/login.html` → popup → **Preencher login**.
2. No celular aparece **“Liberar senha?”** com computador, site e conta.
3. **Permitir** → campos preenchidos; aviso “Conta preenchida após aprovação no celular”.
4. Repita e toque **Recusar** → “O pedido foi recusado no celular”.
5. Se não responder em 60 s → recusado automaticamente.

### 5.2 Modo automático

1. App → Computadores → **Automático neste computador** → **Ligar**.
   Aparece “Automático até HH:MM”.
2. Preencha de novo → **sem pergunta** no celular; aviso “preenchida
   automaticamente (dispositivo confiável)”; o app mostra “Preenchido
   automaticamente…”.
3. **Conta sensível:** marque a conta como sensível → o celular volta a
   perguntar, com o aviso “Conta sensível: sempre pede confirmação”.
4. **Expiração:** Ajustes → modo automático por **1 minuto (para testes)**
   → Computadores → **Renovar confiança** → espere 1 min → o pedido volta a
   pedir confirmação (“A confiança automática expirou”).
5. **Bloqueio do cofre encerra a confiança:** bloqueie e desbloqueie; o
   automático recomeça a contar a partir do desbloqueio.

### 5.3 Preencher ao abrir o site

1. Em `login.html`, popup → marque **Preencher automaticamente ao abrir este
   site** → o Chrome pede permissão só para `localhost` → **Permitir**.
2. Recarregue a página: o pedido sai sozinho (no modo automático, preenche
   sem tocar em nada).
3. `spa.html` (formulário que aparece depois de 2 s) também é preenchido.
4. Desmarque a opção → a permissão do site é removida.

### 5.4 Criar senha e salvar automaticamente

1. `http://localhost:8080/cadastro.html` → digite um e-mail fictício.
2. Popup → escolha o tamanho → **Gerar senha forte e salvar**.
3. Esperado: os campos “Crie uma senha” e “Repita a senha” recebem a mesma
   senha forte; o popup diz “Senha forte criada, preenchida e salva no cofre
   do celular”; o celular mostra “Nova senha salva: … em localhost”; a conta
   aparece no cofre **sem nenhuma confirmação**.
4. Clique em **Criar conta** na página → “confirmação confere”.
5. Repita com o mesmo e-mail → “atualizada no cofre” (não duplica).
6. **Cofre bloqueado:** bloqueie o app e repita → a senha é preenchida, o
   popup avisa que o cofre está bloqueado e mostra **Tentar salvar de novo**.
   Desbloqueie e clique → salva (o pedido fica guardado por 5 min só na
   memória da extensão).
7. Sem e-mail no formulário → “Digite primeiro o seu e-mail ou usuário”.

### 5.5 Revogação

1. **Pelo celular:** Computadores → **Desconectar este computador** →
   confirmar. O popup passa a “Não pareado”; **Preencher login** pede
   para parear de novo.
2. **Pelo computador:** Configurações → **Desconectar este computador do
   celular**. O app recebe o aviso e marca o computador como desconectado.
3. **Revogação sem internet:** desligue o Wi-Fi do celular, revogue no app
   (fica “Será avisado ao servidor na próxima conexão”), religue — o
   servidor é avisado e a extensão perde o acesso.

**Critério de conclusão:** demonstrar os dois modos (5.1 e 5.2), bloquear
um domínio incompatível (Etapa 3.6) e recusar pedidos de um computador
revogado (5.5).

---

## Etapa 6 — Testes negativos de segurança

Tabela da [arquitetura §11.3](architecture.md) e do
[modelo de ameaças](threat-model.md), com o teste automatizado e o manual.

| Cenário | Como reproduzir à mão | Esperado | Teste automatizado |
| --- | --- | --- | --- |
| Senha mestra incorreta | Etapa 2.1 | Cofre não abre; atraso após 5 erros | `VaultCryptoTest`, `UnlockThrottleTest` |
| Banco local copiado | Etapas 2.2 e 2.3 | Campos cifrados; não abre em outro aparelho | `VaultCryptoTest`, `FieldCipherTest` |
| QR Code expirado | Etapa 4.3 | `pairing_expired` | `test_qr_expirado_e_recusado` |
| QR Code reutilizado | Etapa 4.3 | `pairing_used` | `test_qr_reutilizado_e_recusado` |
| Servidor troca a chave da extensão | — | App aborta: “identidade do computador não confere com o QR” + SAS diferente | `PairingClient.claim` (verificação), `test_reivindicacao_devolve_chave_publica_da_extensao` |
| Dispositivo revogado | Etapa 5.5 | `device_revoked`; nada é liberado | `test_dispositivo_revogado_nao_volta_a_conectar`, `AuthorizationPolicyTest` |
| Domínio semelhante | Etapa 3.6 | Bloqueado | `domain/index.test.ts`, `DomainsTest` |
| Mensagem alterada | — | Falha de integridade; descartada | `test_servidor_que_altera_o_cabecalho_e_detectado`, `channel.test.ts` |
| Mensagem repetida (replay) | — | Recusada | `test_mensagem_repetida_e_recusada`, `ReplayGuardTest` |
| Reflexão da própria mensagem | — | Recusada (chaves direcionais) | `test_reflexao_para_o_proprio_remetente_falha` |
| Resposta tardia / outra página | Mude de página antes de aprovar | “A página mudou de endereço” | `security/index.test.ts` |
| Sessão automática expirada | Etapa 5.2 passo 4 | Volta a pedir confirmação | `AuthorizationPolicyTest`, `TrustSessionsTest` |
| Aplicativo desconectado | Feche o app e peça preenchimento | “O celular está desconectado. Abra o app…” | `test_destinatario_offline` |
| Cofre bloqueado | Bloqueie e peça preenchimento | “O cofre está bloqueado” | `AuthorizationPolicyTest` |
| Campos ocultos | Etapa 3.7 | Só campos visíveis recebem a senha | `detect.test.ts` |
| Servidor lendo conteúdo | Etapa 4.4 | Só metadados e conteúdo cifrado | `test_fluxo_completo_e2e_sem_vazar_dados_ao_servidor` |
| Inundação de mensagens | — | `rate_limited` | `test_rate_limit_do_relay`, `test_rate_limit_de_pareamento` |
| Frame grande demais | — | `invalid_message` | `test_frame_grande_demais_e_recusado` |

### Recuperação de acesso (o que se perde)

| Situação | O que acontece |
| --- | --- |
| Esqueceu a senha mestra, tem o celular | Biometria (se ativada) ainda abre o cofre; troque as senhas importantes. Sem biometria, o cofre não abre |
| Perdeu o celular, lembra a senha mestra | O cofre fica no aparelho perdido e protegido pela senha mestra + Keystore; revogue o computador pela extensão. Não há cópia no servidor |
| Perdeu o celular **e** esqueceu a senha | As senhas guardadas não podem ser recuperadas. Não existe porta dos fundos (architecture.md §9.4) |

Teste: em Ajustes, o card “Se eu esquecer a senha mestra?” explica isso ao usuário.

### Teste de usabilidade (roteiro curto)

Peça a 3–5 pessoas do público-alvo, sem ajuda, que: (1) criem o cofre,
(2) guardem uma conta, (3) gerem uma senha forte, (4) pareiem o
computador e (5) entrem no site de demonstração. Anote tempo, dúvidas e
erros. Critérios: concluir cada tarefa em até 2 minutos e entender as
mensagens de recusa/bloqueio.

---

## Roteiro da demonstração

Seguindo o README §10, só com dados fictícios (5–7 minutos):

1. **Problema:** muitas contas, senhas repetidas (mostrar o diagnóstico com `123456` repetida).
2. **Cofre:** criar conta “Banco Exemplo” e mostrar que o banco está cifrado (`inspect_db.py`).
3. **Pareamento:** QR Code + mesmo código de 6 dígitos nas duas telas.
4. **Confirmação:** preencher `login.html` aprovando no celular.
5. **Automático:** ligar o modo automático e preencher sem tocar no celular.
6. **Gerar e salvar:** `cadastro.html` → senha forte criada no computador e salva no celular.
7. **Bloqueio:** abrir `127.0.0.1:8080/login.html` (ou `banco.test.golpe.test`) e mostrar o bloqueio.
8. **Revogação:** desconectar o computador no celular e mostrar o pedido recusado.

---

## Solução de problemas

| Sintoma | Causa provável | Solução |
| --- | --- | --- |
| Popup: “Não foi possível falar com o servidor” | Backend parado ou endereço errado | Rode o `uvicorn` e confira `http://localhost:8000/health` |
| App: “Não foi possível falar com o servidor” no pareamento | Celular não alcança o endereço do QR | `adb reverse tcp:8000 tcp:8000`, ou use o IP do PC na extensão |
| “Esta página não usa conexão segura” no site de demo | Modo de desenvolvimento desligado | Configurações → marcar modo de desenvolvimento |
| “O celular está desconectado” | App fechado, em segundo plano ou tela apagada | Abra o app e desbloqueie o cofre (ele mantém a tela ligada enquanto aberto) |
| “O cofre está bloqueado” | Bloqueio por inatividade | Desbloqueie; ajuste o tempo em Ajustes |
| “A extensão não tem acesso a esta página” | O atalho/clique não concedeu `activeTab` | Clique no ícone da extensão na própria aba |
| Pedidos sempre pedem confirmação no automático | Confiança expirada ou conta sensível | Computadores → Renovar confiança; desmarque “sensível” |
| Gradle não sincroniza | Sem wrapper/SDK | Gere o wrapper no Android Studio; instale o SDK 35 |
