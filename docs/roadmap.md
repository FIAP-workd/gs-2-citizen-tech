# Roadmap de Desenvolvimento — Chave Segura

**Base:** [README.md](../README.md) e [architecture.md](architecture.md)\
**Objetivo:** ordenar o desenvolvimento do MVP em fases com entregas e
critérios de conclusão verificáveis.**Estado:** Fases 0 a 5 implementadas; Fase 6 com testes automatizados e
roteiro manual em [test-plan.md](test-plan.md) (falta o teste de
usabilidade com pessoas do público-alvo).

A ordem respeita a recomendação dos documentos: primeiro o cofre local
seguro, depois a extensão, depois o pareamento e os fluxos de
autorização.

------------------------------------------------------------------------

## Fase 0 — Preparação e decisões

1.  **Estrutura do monorepo:** `android/`, `extension/`, `backend/`,
    `docs/`, `docker-compose.yml`.
    -   Mover `.docs/architeture.md` para `docs/architecture.md`,
        conforme a estrutura prevista no README.
2.  **Unificar os módulos.** O README e a arquitetura divergem:
    -   Android: adotar `ui`, `domain`, `data`, `security`, `vault`,
        `pairing`, `communication`, `authorization`.
    -   Extensão: adotar `background`, `content`, `popup`, `domain`,
        `communication`, `security`, `pairing`.
3.  **Escolher as bibliotecas de criptografia** e registrar em um ADR
    (documento curto de decisão):
    -   Argon2id no Android (ex.: Bouncy Castle ou argon2kt).
    -   AES-256-GCM via `javax.crypto`, com chave protegida pelo
        Android Keystore.
    -   Canal ponta a ponta (E2E) com biblioteca disponível nos dois
        lados: Noise Protocol ou libsodium (`lazysodium-android` +
        `libsodium-wrappers`).
4.  **Contrato de mensagens:** definir o formato JSON dos envelopes
    trocados entre Android, backend e extensão.
5.  **CI no GitHub Actions:** build e testes dos três projetos.
6.  **`docs/threat-model.md`:** ameaças consideradas e mitigações.

**Critério de conclusão:** os três projetos compilam vazios no CI e as
decisões de criptografia e o contrato de mensagens estão documentados.

## Fase 1 — Cofre local no Android

> Une as Etapas 1 e 2 do README: a persistência já nasce criptografada
> para evitar retrabalho.

1.  Projeto Kotlin + Jetpack Compose com navegação básica.
2.  Camada `security`:
    -   salt aleatório e Argon2id com parâmetros versionados;
    -   chave aleatória do cofre protegida pela chave derivada;
    -   AES-256-GCM com nonce único por operação.
3.  Room com as tabelas `credentials`, `categories` e `vault_metadata`,
    gravando apenas os campos sensíveis cifrados.
4.  Telas de criação da senha mestra e de desbloqueio, com bloqueio
    automático por inatividade.
5.  CRUD de credenciais: cadastrar, editar, consultar e excluir. A
    senha só é exibida após autenticação.
6.  Testes unitários: derivação de chave, cifragem/decifragem e senha
    mestra incorreta.

**Critério de conclusão:** cadastrar uma credencial, fechar e reabrir o
app e recuperá-la somente após o desbloqueio. O arquivo do banco não
contém dados sensíveis em texto aberto.

## Fase 2 — Funcionalidades do cofre

1.  Gerador de senhas com `SecureRandom`, tamanho e tipos de caractere
    configuráveis.
2.  Diagnóstico de senhas fracas e reutilizadas, calculado em memória
    após o desbloqueio.
3.  Categorias e busca por nome, categoria ou domínio.
4.  Biometria via BiometricPrompt, ligada a uma chave do Keystore, como
    atalho de desbloqueio (nunca substituindo a senha mestra).
5.  Marcação de credencial como "sensível", usada pelo modo automático.
6.  Acessibilidade: fontes e botões grandes, linguagem simples e
    feedback claro de sucesso ou recusa.

**Critério de conclusão:** gerar uma senha forte, identificar uma senha
repetida e desbloquear o cofre por biometria.

## Fase 3 — Extensão Chrome isolada

> Sem pareamento, a extensão ainda não tem acesso ao cofre. Nesta fase
> ela usa uma **fonte de credenciais simulada (mock)** atrás de uma
> interface, que será substituída pelo canal real na Fase 5.

1.  Projeto Manifest V3 em TypeScript (Vite ou esbuild).
2.  **Módulo `domain`, com testes primeiro:**
    -   normalizar o hostname e comparar de forma exata;
    -   bloquear casos como `gmail.com.site-falso.com`;
    -   exigir HTTPS (com exceção explícita para desenvolvimento);
    -   recusar iframes de origens não autorizadas.
3.  Detecção de formulários HTML convencionais (campo de senha visível
    e campo de usuário associado).
4.  Popup para iniciar a solicitação e mostrar seu estado.
5.  Preenchimento: o content script recebe somente a credencial da
    página atual, e a referência é limpa logo após o uso.
6.  Permissões mínimas: preferir `activeTab` a `<all_urls>`.
7.  **Site de demonstração** com formulários de login fictícios, usado
    também nos testes da Fase 6.

**Critério de conclusão:** preencher o formulário de teste e bloquear o
preenchimento em um domínio semelhante ao original.

## Fase 4 — Backend relay e pareamento

1.  FastAPI + SQLite com as tabelas `devices`, `pairing_sessions`,
    `communication_sessions` e `fill_requests`.
    -   Sugestão: no MVP, tratar o "usuário" como o conjunto de
        dispositivos pareados, sem login por e-mail.
2.  Endpoint de pareamento: token aleatório, de uso único e com
    validade curta (ex.: 2 minutos).
3.  WebSocket de relay: encaminha apenas envelopes opacos, com rate
    limiting e logs sem segredos.
4.  Extensão gera seu par de chaves e exibe o QR Code.
5.  Android lê o QR (CameraX + ML Kit), mostra o nome do computador e
    pede confirmação; o dispositivo é gravado em `trusted_devices`.
6.  **Proteção contra servidor malicioso:**
    -   o QR Code inclui a chave pública (ou fingerprint) da extensão,
        para que o Android verifique a identidade sem depender do
        servidor;
    -   exibir um código curto de verificação nas duas telas.
7.  Canal E2E com a biblioteca escolhida na Fase 0, com identificador
    único e timestamp em cada mensagem para impedir replay.
8.  Revogação de dispositivo: marcada localmente primeiro e propagada
    ao backend quando houver conexão.
9.  `docker-compose.yml` para subir o backend.

**Critério de conclusão:** conectar o celular ao Chrome e trocar
mensagens; o banco e os logs do servidor contêm apenas conteúdo cifrado.

## Fase 5 — Autorização e autofill

1.  **Modo confirmação (padrão):** a extensão solicita, o Android exibe
    serviço e domínio, o usuário aprova ou recusa, e a credencial é
    enviada pelo canal E2E.
2.  A extensão revalida origem e sessão antes de preencher.
3.  **Modo automático** (módulo `authorization`):
    -   confiança explícita e revogável por dispositivo;
    -   cofre desbloqueado;
    -   sessão de confiança com duração limitada e configurável;
    -   domínio idêntico ao da credencial;
    -   credenciais sensíveis sempre exigem confirmação.
4.  Falhar de forma fechada: Android desconectado, sessão expirada ou
    erro de verificação nunca liberam credencial; respostas duplicadas
    são processadas uma única vez.
5.  Registro em `security_events`, sem senhas, tokens ou conteúdo de
    mensagens.
6.  Tela de dispositivos confiáveis com opção de revogar.

**Critério de conclusão:** demonstrar os dois modos de autorização,
bloquear um domínio incompatível e recusar solicitações de um
dispositivo revogado.

## Fase 6 — Testes, documentação e demonstração

1.  Automatizar os testes negativos da arquitetura (seção 11.3): senha
    mestra incorreta, QR expirado ou reutilizado, dispositivo revogado,
    domínio semelhante, mensagem alterada, replay, sessão expirada,
    servidor lendo conteúdo e banco local copiado.
2.  Testes de integração ponta a ponta Android ↔ backend ↔ extensão.
3.  Documentação: `docs/data-model.md`, `docs/test-plan.md` e a seção
    de recuperação de acesso (o que se perde, sem porta dos fundos).
4.  Teste de usabilidade com pessoas do público-alvo.
5.  Roteiro da demonstração conforme a seção 10 do README, apenas com
    credenciais fictícias, conferindo os critérios de aceite da seção 15
    da arquitetura.

**Critério de conclusão:** jornada completa demonstrada, com evidências
de segurança e usabilidade.

------------------------------------------------------------------------

## Trabalho em paralelo

Depois da Fase 0, as frentes podem avançar ao mesmo tempo:

| Frente   | Fases                                    |
| -------- | ---------------------------------------- |
| Android  | 1 e 2                                    |
| Extensão | 3 (com mock e site de demonstração)      |
| Backend  | 4 (API de pareamento e relay)            |

As frentes se encontram no pareamento e no canal E2E (Fases 4 e 5). Por
isso o contrato de mensagens precisa estar definido na Fase 0.
