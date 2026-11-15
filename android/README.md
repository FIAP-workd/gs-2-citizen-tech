# Chave Segura — Android

Kotlin + Jetpack Compose. O celular é o cofre e a autoridade de autorização.

Requisitos: Android Studio (JDK 17 e Android SDK 35). Na primeira abertura,
gere o Gradle wrapper e faça commit de `gradlew`, `gradlew.bat` e
`gradle/wrapper/` — o CI usa o wrapper quando ele existir.

```bash
./gradlew assembleDebug testDebugUnitTest
```

## Pacotes (`app/src/main/java/com/chavesegura/`)

| Pacote | Conteúdo |
| --- | --- |
| `security` | Argon2id (libsodium), AES-256-GCM, chave do Android Keystore, hierarquia KEK → VK, biometria |
| `data` | Room: `credentials`, `categories`, `vault_metadata`, `trusted_devices`, `security_events` |
| `vault` | Sessão de desbloqueio (bloqueio por inatividade), repositório que cifra/decifra cada campo, limite de tentativas |
| `domain` | Gerador de senhas, força, diagnóstico de senhas fracas/repetidas, normalização de domínios |
| `pairing` | Leitura do QR, reivindicação/confirmação no relay, verificação da chave pelo QR, SAS |
| `communication` | Envelope do contrato, anti-replay, canal E2E (crypto_kx + XChaCha20-Poly1305), WebSocket (OkHttp) |
| `authorization` | Política do modo automático, sessões de confiança, coordenação de pedidos de preenchimento e de “gerar e salvar” |
| `ui` | Telas: criação/desbloqueio, cofre, conta, gerador, computadores, pareamento, aprovação, segurança, histórico, ajustes |

## Funcionamento resumido

- O app conecta ao relay **enquanto está aberto** (e mantém a tela ligada
  com o cofre desbloqueado). Fechado ou em segundo plano, a extensão recebe
  “celular desconectado” — falha fechada.
- Novos computadores começam no modo **confirmação**. O modo **automático**
  vale por tempo limitado, termina ao bloquear o cofre e nunca libera contas
  sensíveis sem perguntar.
- Senhas criadas pela extensão (“Gerar senha forte e salvar”) são gravadas
  automaticamente se o cofre estiver aberto.

## Testes unitários (`app/src/test`)

Rodam na JVM, sem aparelho: a libsodium e o Keystore ficam atrás de
interfaces (`PasswordKdf`, `DeviceKeyWrapper`, `E2eCrypto`), e os testes usam
substitutos. Os vetores `docs/test-vectors/e2e-v1.json` conferem AAD e SAS
com a extensão.

Roteiro manual completo: [docs/test-plan.md](../docs/test-plan.md).

## Observação sobre a libsodium

`lazysodium-android` e `jna` entram como `@aar` (trazem as bibliotecas
nativas). As chamadas ficam concentradas em `security/Kdf.kt` e
`communication/E2eCrypto.kt`; se uma versão nova mudar alguma assinatura,
é só nesses dois arquivos.
