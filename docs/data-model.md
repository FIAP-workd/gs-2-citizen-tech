# Modelo de dados — Chave Segura

Implementação real dos bancos descritos no [README](../README.md) §5.
Campos `*_encrypted` / `*_wrapped` guardam `nonce || ciphertext || tag`.

## 1. Android (Room / SQLite — `chave_segura.db`)

### `vault_metadata` (uma linha)

| Campo | Tipo | Conteúdo |
| --- | --- | --- |
| id | INTEGER | Sempre 1 |
| kdf_version | INTEGER | Versão dos parâmetros do Argon2id (1) |
| salt | BLOB | 16 bytes aleatórios |
| ops_limit / mem_limit | INTEGER | 3 iterações / 64 MiB |
| wrapped_vault_key | BLOB | `Keystore(AES-GCM(KEK, VK))` — ADR 0001 §2 |
| biometric_vault_key | BLOB? | VK cifrada por chave do Keystore que exige biometria |
| created_at | INTEGER | ms |

### `credentials`

| Campo | Tipo | Conteúdo |
| --- | --- | --- |
| id | TEXT (UUID) | Identificador |
| title | TEXT | Nome do serviço (em claro, para a lista) |
| username_encrypted | BLOB | AES-256-GCM(VK), AAD `id\|username\|1` |
| password_encrypted | BLOB | AES-256-GCM(VK), AAD `id\|password\|1` |
| notes_encrypted | BLOB? | AES-256-GCM(VK), AAD `id\|notes\|1` |
| website_domain | TEXT | Hostname normalizado (busca exata) |
| category_id | TEXT? | Categoria |
| sensitive | INTEGER | 1 = sempre pede confirmação |
| created_at / updated_at | INTEGER | ms |

### `categories`

Semeadas na criação do cofre: Bancos e finanças (sensível), E-mail, Redes
sociais, Compras, Governo e saúde (sensível), Serviços, Outros.

### `trusted_devices`

| Campo | Conteúdo |
| --- | --- |
| id, device_name, created_at, last_seen | Identificação local do computador |
| public_key | Chave X25519 da extensão (veio do QR Code) |
| authorization_mode | `approval` ou `automatic` |
| status | `active` ou `revoked` |
| link_id, relay_url, peer_device_id | Roteamento no relay |
| own_device_id, own_public_key | Identidade do celular neste vínculo |
| own_token_wrapped, own_secret_wrapped | Token do relay e chave privada X25519, cifrados pelo Keystore |
| pending_revocation | Revogado localmente, falta avisar o relay |

### `security_events`

`id`, `event_type`, `device_id`, `credential_id`, `timestamp`, `result`.
Nunca contém senhas, tokens, domínios pedidos ou conteúdo de mensagens.

Fora do banco: `SharedPreferences` com tempo de bloqueio, duração do modo
automático, contador de erros de senha e nome do celular (nada secreto).
Backup automático desligado (`allowBackup=false` e `data_extraction_rules`).

## 2. Backend (SQLite — `relay.sqlite3`)

| Tabela | Campos | Observação |
| --- | --- | --- |
| `devices` | id, kind (`extension`/`android`), device_name, public_key, **token_hash**, status, created_at, last_seen | Só o SHA-256 do token |
| `pairing_sessions` | id, **token_hash**, extension_device_id, android_device_id, status (`open`, `claimed`, `completed`, `cancelled`, `expired`), created_at, expires_at, claimed_at | Uso único, 120 s |
| `communication_sessions` | id (= `link_id`), extension_device_id, android_device_id, status, created_at, revoked_at | Vínculo entre os dois |
| `fill_requests` | (link_id, **corr**), requester/target_device_id, status (`pending`, `answered`, `expired`), created_at, answered_at | Sem domínio nem credencial (threat-model §5) |

Não há tabela de usuários: no MVP o “usuário” é o conjunto de
dispositivos pareados (roadmap, Fase 4). Envelopes (`nonce`,
`ciphertext`) nunca são gravados.

## 3. Extensão (`chrome.storage.local`)

| Chave | Conteúdo |
| --- | --- |
| `settings` | Endereço do relay, nome do computador, modo de desenvolvimento, tamanho do gerador |
| `pairing` | link_id, device_id, device_token, chaves X25519, dados do celular, último `seq` |
| `autoSites` | Origens com preenchimento automático ligado (com permissão concedida) |

Senhas nunca são gravadas na extensão. Uma senha recém-gerada que não pôde
ser salva fica só na memória do service worker por até 5 minutos.
