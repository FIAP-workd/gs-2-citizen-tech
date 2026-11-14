# Contrato de mensagens — Chave Segura

**Versão do protocolo:** 1\
**Status:** Aceito (Fase 0)\
**Relacionado:** [ADR 0001](adr/0001-bibliotecas-criptografia.md),
[threat-model.md](threat-model.md)

Este documento define o formato das mensagens trocadas entre o
aplicativo Android, o backend relay e a extensão Chrome. É o ponto de
encontro das três frentes de desenvolvimento: qualquer mudança aqui
precisa ser combinada entre elas e incrementar a versão.

## 1. Camadas

``` text
Android ──TLS──► Backend relay ──TLS──► Extensão
   │                                       │
   └──────────── E2E (libsodium) ──────────┘
```

| Camada                | Quem lê                | Conteúdo                                         |
| --------------------- | ---------------------- | ------------------------------------------------ |
| REST de pareamento    | Backend                | Criação e confirmação de pareamentos             |
| Frames de controle WS | Backend                | Autenticação, eventos de pareamento, erros       |
| Envelope E2E          | Backend vê o cabeçalho | Roteamento, anti-replay                          |
| Payload E2E           | Somente Android/extensão | Solicitações e respostas de preenchimento      |

O backend **nunca** recebe domínio da página, nome do serviço, usuário
ou senha: esses dados existem apenas dentro do payload E2E.

## 2. Convenções

-   JSON em UTF-8, nomes de campos em `snake_case`.
-   Binários (chaves, nonces, ciphertext) em **base64url sem padding**.
-   Identificadores: UUID v4 em texto (`msg_id`, `device_id`, `link_id`,
    `pairing_id`, `request_id`).
-   Horários: milissegundos desde a época Unix, UTC (`ts`, `expires_at`).
-   Todo objeto de nível superior tem `v` (versão do protocolo).
    Receptores recusam versões desconhecidas com `unsupported_version`.
-   Campos desconhecidos são ignorados; campos obrigatórios ausentes
    geram `invalid_message`.
-   Tamanho máximo de um frame WebSocket: **16 KiB**.

## 3. Pareamento

### 3.1 Criar sessão (extensão → backend)

`POST /v1/pairing/sessions`

``` json
{
  "v": 1,
  "device_name": "Chrome — Notebook da Ana",
  "public_key": "<X25519 da extensão, base64url>"
}
```

Resposta `201`:

``` json
{
  "v": 1,
  "pairing_id": "4f6c1c1e-7d0a-4c8e-9b8e-2d9b5a3f0c11",
  "pairing_token": "<32 bytes aleatórios, base64url>",
  "expires_at": 1763150520000,
  "device_id": "b1c2…",
  "device_token": "<token do dispositivo, base64url>"
}
```

-   `pairing_token`: uso único, validade de **120 s**.
-   `device_token`: autentica a extensão no WebSocket. O dispositivo
    fica com estado `pending` até a confirmação.
-   O backend guarda apenas o SHA-256 dos tokens.

### 3.2 Conteúdo do QR Code

``` json
{
  "v": 1,
  "t": "cs-pair",
  "relay": "wss://relay.exemplo/v1/ws",
  "pid": "4f6c1c1e-7d0a-4c8e-9b8e-2d9b5a3f0c11",
  "tok": "<pairing_token>",
  "pk": "<X25519 da extensão>",
  "name": "Chrome — Notebook da Ana"
}
```

O QR não contém credenciais, senha mestra nem chaves privadas. A chave
pública `pk` vem do QR (canal físico) e é comparada com a que o backend
informar; divergência aborta o pareamento.

### 3.3 Reivindicar sessão (Android → backend)

`POST /v1/pairing/sessions/{pairing_id}/claim`

``` json
{
  "v": 1,
  "pairing_token": "<tok do QR>",
  "device_name": "Celular da Ana",
  "public_key": "<X25519 do Android>"
}
```

Resposta `200`:

``` json
{
  "v": 1,
  "device_id": "a9d8…",
  "device_token": "<token do Android>",
  "peer": { "device_id": "b1c2…", "device_name": "Chrome — Notebook da Ana", "public_key": "<X25519 da extensão>" }
}
```

Erros: `410 pairing_expired`, `409 pairing_used`, `404 pairing_not_found`.
O token é consumido nesta chamada, com ou sem sucesso posterior.

O backend avisa a extensão com o frame `pairing.claimed` (§4.3). As
duas telas exibem o **código de verificação (SAS)** de 6 dígitos
definido no ADR 0001.

### 3.4 Confirmar (Android → backend)

`POST /v1/pairing/sessions/{pairing_id}/confirm` com
`Authorization: Bearer <device_token do Android>`

``` json
{ "v": 1, "accepted": true }
```

Resposta `200`: `{ "v": 1, "link_id": "e7f0…" }`. Com `accepted: false`
a sessão é cancelada e o dispositivo `pending` da extensão é removido.
O backend envia `pairing.completed` (ou `pairing.cancelled`) à extensão.

## 4. WebSocket

Endpoint: `wss://<relay>/v1/ws`. Cada frame é um objeto JSON com `type`.

### 4.1 Autenticação

Primeiro frame do cliente, em até 5 s após conectar:

``` json
{ "v": 1, "type": "auth", "device_id": "a9d8…", "device_token": "<token>" }
```

Resposta: `{ "v": 1, "type": "auth.ok", "links": ["e7f0…"] }` ou
`error` seguido do fechamento da conexão.

### 4.2 Envelope E2E (`relay`)

``` json
{
  "v": 1,
  "type": "relay",
  "msg_id": "0d6b7a54-3f0e-4a52-8a51-0f6b7e3b2c90",
  "link_id": "e7f0…",
  "from": "b1c2…",
  "to": "a9d8…",
  "ts": 1763150400000,
  "seq": 7,
  "corr": "c3a1…",
  "nonce": "<24 bytes, base64url>",
  "ciphertext": "<XChaCha20-Poly1305, base64url>"
}
```

| Campo        | Função                                                                 |
| ------------ | ---------------------------------------------------------------------- |
| `msg_id`     | Único por mensagem; base do anti-replay.                               |
| `link_id`    | Vínculo de pareamento ativo entre os dois dispositivos.                |
| `from`/`to`  | Roteamento. O backend confere que `from` é o dispositivo autenticado.  |
| `ts`         | Horário do remetente. Janela aceita: ±60 s.                            |
| `seq`        | Contador crescente por remetente e `link_id`.                          |
| `corr`       | Identificador opaco e aleatório da solicitação, igual em pedido e resposta. Permite ao backend acompanhar estado e expiração em `fill_requests` sem conhecer o domínio. |
| `nonce`      | Nonce aleatório da AEAD.                                               |
| `ciphertext` | Payload E2E (§5) cifrado, com tag.                                     |

**AAD** (dados autenticados, não cifrados): a string ASCII

``` text
cs/1|<msg_id>|<link_id>|<from>|<to>|<ts>|<seq>|<corr>
```

Qualquer alteração do cabeçalho pelo relay invalida a tag. `corr` vazio
é representado por string vazia.

**O backend:** valida formato e tamanho, confere `from`, `link_id` ativo
e não revogado, aplica rate limiting e encaminha. Não armazena
`nonce` nem `ciphertext` e não os registra em logs. Se o destinatário
estiver offline, responde `peer_offline` (sem fila no MVP).

**O receptor:** recusa mensagens fora da janela de `ts`, com `seq` não
crescente ou `msg_id` já visto; falha de decifragem é tratada como
mensagem inexistente (sem resposta detalhada ao remetente).

### 4.3 Frames de controle (backend → cliente)

| `type`              | Para      | Corpo                                                      |
| ------------------- | --------- | ---------------------------------------------------------- |
| `auth.ok`           | ambos     | `links`                                                    |
| `pairing.claimed`   | extensão  | `pairing_id`, `peer: {device_id, device_name, public_key}` |
| `pairing.completed` | extensão  | `pairing_id`, `link_id`                                    |
| `pairing.cancelled` | extensão  | `pairing_id`                                               |
| `peer.status`       | ambos     | `link_id`, `online` (bool)                                 |
| `link.revoked`      | ambos     | `link_id`                                                  |
| `error`             | ambos     | `code`, `ref` (opcional: `msg_id` relacionado)             |
| `pong`              | ambos     | —                                                          |

Cliente → backend, além de `auth` e `relay`: `ping` e
`link.revoke` (`{ "link_id": "…" }`, enviado pelo Android).

## 5. Payload E2E

Depois de decifrado, o payload é JSON:

``` json
{ "kind": "fill.request", "body": { … } }
```

### 5.1 `fill.request` (extensão → Android)

``` json
{
  "kind": "fill.request",
  "body": {
    "request_id": "c3a1…",
    "origin": "https://accounts.exemplo.com",
    "hostname": "accounts.exemplo.com",
    "top_level": true
  }
}
```

`request_id` é igual ao `corr` do envelope. O Android compara
`hostname` exatamente com o domínio cadastrado (Fase 3/5).

### 5.2 `fill.response` (Android → extensão)

``` json
{
  "kind": "fill.response",
  "body": {
    "request_id": "c3a1…",
    "result": "approved",
    "mode": "confirmation",
    "hostname": "accounts.exemplo.com",
    "credential": { "username": "ana@exemplo.com", "password": "…" },
    "expires_at": 1763150430000
  }
}
```

| `result`    | Significado                                       | `credential` |
| ----------- | ------------------------------------------------- | ------------ |
| `approved`  | Liberada (por confirmação ou política automática) | presente     |
| `denied`    | Usuário recusou                                   | ausente      |
| `not_found` | Nenhuma credencial para o domínio                 | ausente      |
| `locked`    | Cofre bloqueado                                   | ausente      |
| `error`     | Falha interna; falhar fechado                     | ausente      |

`mode`: `confirmation` ou `automatic`. A extensão só preenche se
`hostname` da resposta for idêntico ao da página atual e `expires_at`
(curto, ex.: 30 s) não tiver passado. Respostas duplicadas para o mesmo
`request_id` são descartadas.

### 5.3 `fill.ack` (extensão → Android)

``` json
{ "kind": "fill.ack", "body": { "request_id": "c3a1…", "filled": true } }
```

Usado para registrar `security_events` no Android. Não contém dados
dos campos.

## 6. Códigos de erro

| Código                | Origem   | Situação                                        |
| --------------------- | -------- | ----------------------------------------------- |
| `unsupported_version` | ambos    | `v` desconhecido                                |
| `invalid_message`     | ambos    | JSON inválido, campo ausente ou frame > 16 KiB  |
| `auth_failed`         | backend  | Token inválido ou dispositivo inexistente       |
| `pairing_not_found`   | backend  | `pairing_id` inexistente                        |
| `pairing_expired`     | backend  | QR com mais de 120 s                            |
| `pairing_used`        | backend  | Token de pareamento já consumido                |
| `device_revoked`      | backend  | Dispositivo ou vínculo revogado                 |
| `peer_offline`        | backend  | Destinatário sem conexão                        |
| `rate_limited`        | backend  | Limite de requisições excedido                  |
| `replay_detected`     | clientes | `msg_id` repetido, `seq` ou `ts` inválidos (só registro local) |

## 7. Versionamento

-   Mudanças compatíveis (campos opcionais novos) não alteram `v`.
-   Mudanças incompatíveis incrementam `v` e o prefixo da AAD
    (`cs/2|…`). Durante a transição, o backend pode aceitar as duas
    versões; os clientes negociam pela versão do QR Code.
