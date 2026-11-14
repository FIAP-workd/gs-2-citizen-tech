# ADR 0001 — Bibliotecas e esquemas de criptografia

**Status:** Aceito (Fase 0)\
**Data:** 2026-11-14\
**Contexto relacionado:** [architecture.md](../architecture.md) §6,
[message-contract.md](../message-contract.md),
[threat-model.md](../threat-model.md)

## Contexto

O MVP precisa de três capacidades criptográficas:

1.  derivar uma chave a partir da senha mestra (Argon2id);
2.  cifrar campos do cofre em repouso no Android (AES-256-GCM, com
    apoio do Android Keystore);
3.  estabelecer um canal ponta a ponta (E2E) entre Android e extensão
    Chrome que o backend relay não consiga ler nem alterar.

A arquitetura proíbe algoritmos próprios e pede bibliotecas mantidas,
disponíveis nos dois lados do canal.

## Decisão

### Uma única biblioteca para KDF e canal: libsodium

| Lado     | Biblioteca                                                        | Uso                                    |
| -------- | ----------------------------------------------------------------- | -------------------------------------- |
| Android  | `com.goterl:lazysodium-android` (+ `net.java.dev.jna:jna` @aar)   | Argon2id (`crypto_pwhash`) e canal E2E |
| Extensão | `libsodium-wrappers` (WASM)                                       | Canal E2E                              |
| Android  | `javax.crypto` + Android Keystore (plataforma)                    | AES-256-GCM do cofre                   |

Usar libsodium nos dois lados elimina divergências de implementação no
canal E2E e já fornece Argon2id no Android, dispensando Bouncy Castle
ou argon2kt.

### 1. Derivação de chave (Argon2id)

-   Função: `crypto_pwhash` com `ALG_ARGON2ID13`.
-   Salt: 16 bytes aleatórios (`randombytes_buf`), um por cofre.
-   Parâmetros iniciais (`kdf_version = 1`): **memória 64 MiB,
    iterações 3, paralelismo 1** (libsodium usa p = 1). Atende ao mínimo
    da OWASP (19 MiB, t = 2). Serão calibrados na Fase 1 para ficar
    entre 0,5 s e 1 s em um aparelho intermediário.
-   Saída: 32 bytes (KEK — *key encryption key*).
-   `vault_metadata` guarda `kdf_version`, `salt`, `memlimit` e
    `opslimit`, permitindo migrar parâmetros sem perder o cofre.

### 2. Hierarquia de chaves do cofre

``` text
KEK    = Argon2id(senha mestra, salt)            -- 32 B, só em memória
VK     = 32 B aleatórios                         -- vault key
wrap1  = AES-256-GCM(KEK, VK)
wrap2  = AES-256-GCM(chave do Keystore, wrap1)   -- gravado em vault_metadata
campo  = AES-256-GCM(VK, valor, AAD)             -- gravado em credentials
```

-   **VK** é gerada uma vez com `SecureRandom` e nunca é gravada em
    claro.
-   **Camada 1:** VK é cifrada pela KEK (AES-256-GCM).
-   **Camada 2:** o resultado é cifrado novamente por uma chave AES-256
    não exportável do **Android Keystore** (preferindo StrongBox/TEE).
    Assim, copiar o banco para outro aparelho não basta para atacar a
    senha mestra por força bruta: é preciso também o hardware original.
-   **Biometria (Fase 2):** uma segunda cópia de VK é protegida por outra
    chave do Keystore com `setUserAuthenticationRequired(true)` e
    invalidada ao cadastrar nova biometria. A cópia protegida pela senha
    mestra continua sendo obrigatória.

### 3. Cifragem dos campos (AES-256-GCM)

-   API: `javax.crypto.Cipher` com `AES/GCM/NoPadding`, tag de 128 bits.
-   Nonce: 12 bytes aleatórios por operação, gravados junto ao
    ciphertext (`nonce || ciphertext || tag`).
-   **AAD:** `credential_id || nome_do_campo || versão_do_esquema`.
    Impede que um ciphertext seja copiado para outra linha ou campo sem
    ser detectado.
-   Falha de tag → operação interrompida, nenhum dado parcial é
    devolvido.

### 4. Canal E2E Android ↔ extensão

Escolhemos as construções de alto nível da libsodium em vez de Noise
Protocol: as implementações de Noise para Kotlin/Android e para
navegador são pouco mantidas, enquanto a libsodium tem bindings maduros
nos dois lados.

-   **Identidade:** cada dispositivo gera um par X25519 estático com
    `crypto_kx_keypair`. A chave privada nunca sai do dispositivo.
-   **Pareamento:** o QR Code exibido pela extensão contém a chave
    pública dela. O Android verifica a chave pelo QR (canal físico), não
    pelo servidor. Ambos exibem um **código curto de verificação (SAS)**
    de 6 dígitos derivado de
    `crypto_generichash(32, "chave-segura/sas/v1" || pk_extensao || pk_android)`.
-   **Chaves de sessão:** `crypto_kx_client_session_keys` (extensão) e
    `crypto_kx_server_session_keys` (Android) produzem chaves
    direcionais `rx`/`tx`, o que impede reflexão de mensagens.
-   **Cifra:** `crypto_aead_xchacha20poly1305_ietf_encrypt`, nonce
    aleatório de 24 bytes por mensagem e o cabeçalho do envelope como
    AAD (formato canônico em [message-contract.md](../message-contract.md)).
-   **Anti-replay:** `msg_id` único, `ts` com janela de ±60 s e `seq`
    estritamente crescente por remetente; o receptor guarda os `msg_id`
    vistos durante a janela.

### 5. Extensão no navegador

-   `libsodium-wrappers` carrega WASM; o manifesto declara
    `script-src 'self' 'wasm-unsafe-eval'` para as páginas da extensão.
-   A chave privada X25519 fica em `chrome.storage.local`, acessível
    apenas à extensão. Chaves de sessão ficam em memória do service
    worker ou em `chrome.storage.session` (não persiste em disco).
-   O content script nunca recebe chaves, apenas a credencial da página
    atual no momento do preenchimento.

### 6. Aleatoriedade

-   Android: `SecureRandom` (VK, nonces GCM) e `randombytes_buf`
    (libsodium).
-   Extensão: `crypto.getRandomValues` / `randombytes_buf`.
-   Backend: `secrets.token_urlsafe(32)` para tokens de pareamento e de
    dispositivo; o servidor guarda apenas o SHA-256 dos tokens.

## Alternativas consideradas

| Alternativa                         | Motivo da rejeição                                                         |
| ----------------------------------- | -------------------------------------------------------------------------- |
| Bouncy Castle para Argon2id         | Implementação em Java puro, lenta com 64 MiB em aparelhos modestos.        |
| argon2kt                            | Viável, mas acrescenta uma dependência nativa só para a KDF.               |
| Noise Protocol (XX/IK)              | Melhor sigilo futuro, mas sem bibliotecas mantidas nos dois lados.         |
| WebCrypto (ECDH P-256 + AES-GCM)    | Exigiria combinar primitivas manualmente e manter duas implementações.     |
| SQLCipher para o banco inteiro      | Cifra o arquivo, mas não impede a exposição de campos após o desbloqueio; pode ser somado no futuro. |

## Consequências

-   **Positivas:** uma biblioteca comum no canal E2E; APIs de alto nível,
    difíceis de usar incorretamente; parâmetros versionados.
-   **Limitação aceita no MVP:** sem *forward secrecy*. As chaves de
    sessão derivam de chaves estáticas; se a chave privada de um
    dispositivo vazar, mensagens antigas gravadas poderiam ser lidas.
    Mitigação: o relay não armazena envelopes; evolução prevista para
    troca de chaves efêmeras assinadas ou Noise IK.
-   **Limitação aceita no MVP:** a chave privada da extensão fica em
    `chrome.storage.local` sem proteção de hardware.
-   O tamanho do APK aumenta com as bibliotecas nativas da libsodium.

## Referências

-   libsodium — [Key exchange](https://doc.libsodium.org/key_exchange),
    [XChaCha20-Poly1305](https://doc.libsodium.org/secret-key_cryptography/aead/chacha20-poly1305/xchacha20-poly1305_construction),
    [Password hashing](https://doc.libsodium.org/password_hashing/default_phf)
-   OWASP — [Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
-   Android — [Keystore System](https://developer.android.com/privacy-and-security/keystore)
