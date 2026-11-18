# Modelo de dados — Chave Segura

Implementação real dos bancos descritos no [README](../README.md) §5.
Campos `*_encrypted` / `*_wrapped` guardam `nonce || ciphertext || tag`.

## 1. Android (Room / SQLite — `chave_segura.db`, versão 2)

- **Versionamento e migrações:** `AppDatabase` (`version = 2`, `exportSchema = true`) com `MIGRATION_1_2` preservando os dados cifrados e normalizando categorias órfãs antes de aplicar integridade referencial.
- **Seeding automático:** `RoomDatabase.Callback.onCreate` semeia as categorias padrão (`INSERT OR IGNORE`) logo na criação do banco.
- **Backup desativado:** `allowBackup=false` e `data_extraction_rules.xml` impedem saída do banco para nuvem ou transferência.

### `vault_metadata` (uma linha)

| Campo | Tipo | Conteúdo |
| --- | --- | --- |
| id | INTEGER PK | Sempre 1 |
| kdf_version | INTEGER | Versão dos parâmetros do Argon2id (1) |
| salt | BLOB | 16 bytes aleatórios |
| ops_limit / mem_limit | INTEGER | 3 iterações / 64 MiB |
| wrapped_vault_key | BLOB | `Keystore(AES-GCM(KEK, VK))` — ADR 0001 §2 |
| biometric_vault_key | BLOB? | VK cifrada por chave do Keystore que exige biometria |
| created_at | INTEGER | ms |

### `credentials`

| Campo | Tipo | Conteúdo |
| --- | --- | --- |
| id | TEXT (UUID) PK | Identificador |
| title | TEXT | Nome do serviço (em claro, para a lista) |
| username_encrypted | BLOB | AES-256-GCM(VK), AAD `id\|username\|1` |
| password_encrypted | BLOB | AES-256-GCM(VK), AAD `id\|password\|1` |
| notes_encrypted | BLOB? | AES-256-GCM(VK), AAD `id\|notes\|1` |
| website_domain | TEXT | Hostname normalizado (indexado para busca exata) |
| category_id | TEXT? FK | `REFERENCES categories(id) ON UPDATE CASCADE ON DELETE SET NULL` (indexado) |
| sensitive | INTEGER | 1 = sempre pede confirmação |
| created_at / updated_at | INTEGER | ms |

### `categories`

Semeada na criação do banco e garantida na criação do cofre: Bancos e finanças (`bancos`, sensível), E-mail (`email`), Redes sociais (`redes`), Compras (`compras`), Governo e saúde (`governo`, sensível), Serviços (`servicos`), Outros (`outros`).

### `trusted_devices`

| Campo | Tipo / Restrição | Conteúdo |
| --- | --- | --- |
| id | TEXT PK | Identificador local do computador |
| device_name, created_at, last_seen | TEXT / INTEGER | Metadados locais e última atividade |
| public_key | TEXT | Chave X25519 da extensão (veio do QR Code) |
| authorization_mode | TEXT | `approval` ou `automatic` |
| status | TEXT (indexado) | `active` ou `revoked` |
| link_id | TEXT (UNIQUE) | Identificador do vínculo no relay |
| relay_url, peer_device_id | TEXT | Roteamento no relay |
| own_device_id, own_public_key | TEXT | Identidade do celular neste vínculo |
| own_token_wrapped, own_secret_wrapped | BLOB | Token do relay e chave privada X25519, cifrados pelo Keystore |
| pending_revocation | INTEGER | Revogado localmente, falta avisar o relay |
| last_tx_seq | INTEGER (default 0) | Maior `seq` enviado, persistido para manter monotonicidade entre reinícios |
| last_rx_seq | INTEGER (default -1) | Maior `seq` recebido e autenticado, persistido contra replay após reconexão |

### `security_events`

`id`, `event_type`, `device_id` (indexado), `credential_id` (indexado), `timestamp` (indexado), `result`.
Nunca contém senhas, tokens, domínios pedidos ou conteúdo de mensagens.
Retenção limitada automaticamente em `SecurityLog` (`MAX_EVENTS = 1000`, poda ao ultrapassar `1100` registros).

Fora do banco: `SharedPreferences` com tempo de bloqueio, duração do modo automático, contador de erros de senha e nome do celular (nada secreto).

## 2. Backend (SQLite — `relay.sqlite3`, versão 2)

- **Configuração SQLite:** `PRAGMA foreign_keys = ON`, `PRAGMA busy_timeout = 5000` e, em arquivo, `PRAGMA journal_mode = WAL` + `PRAGMA synchronous = NORMAL` com checkpoint no encerramento (`close()`).
- **Migrações versionadas:** tabela `schema_migrations` + `PRAGMA user_version` (v1 esquema inicial; v2 integridade referencial, `CHECK`/`UNIQUE` e índices).
- **Saúde e ciclo de vida:** `/health` valida conectividade com `SELECT 1`; `expire_stale_records` e `purge_expired_records` expiram sessões/solicitações vencidas, revogam dispositivos `pending` órfãos e removem metadados antigos respeitando a janela de retenção (`24 h` padrão).

| Tabela | Campos e restrições | Índices | Observação |
| --- | --- | --- | --- |
| `devices` | `id` PK, `kind` (`extension`/`android`), `device_name`, `public_key`, `token_hash`, `status` (`pending`/`active`/`revoked`), `created_at`, `last_seen` | `uq_devices_token_hash` (UNIQUE), `idx_devices_status` | Só o SHA-256 do token |
| `pairing_sessions` | `id` PK, `token_hash`, `extension_device_id` FK→`devices`, `android_device_id` FK→`devices`, `status` (`open`/`claimed`/`completed`/`cancelled`/`expired`), `created_at`, `expires_at`, `claimed_at`, `CHECK (expires_at >= created_at)` | `uq_pairing_sessions_token_hash` (UNIQUE), `idx_pairing_sessions_status_expires`, `idx_pairing_sessions_ext_device`, `idx_pairing_sessions_android_device` | Uso único, 120 s |
| `communication_sessions` | `id` (`link_id`) PK, `extension_device_id` FK→`devices`, `android_device_id` FK→`devices`, `status` (`active`/`revoked`), `created_at`, `revoked_at`, `CHECK (extension_device_id <> android_device_id)` | `idx_comm_sessions_ext_status`, `idx_comm_sessions_android_status` | Vínculo entre os dois |
| `fill_requests` | `(link_id, corr)` PK, `link_id` FK→`communication_sessions`, `requester_device_id` FK→`devices`, `target_device_id` FK→`devices`, `status` (`pending`/`answered`/`expired`), `created_at`, `answered_at`, `CHECK (requester_device_id <> target_device_id)` | `idx_fill_requests_status_created`, `idx_fill_requests_requester`, `idx_fill_requests_target` | Sem domínio nem credencial (threat-model §5) |
| `schema_migrations` | `version` PK, `name`, `applied_at` | — | Controle de versão do esquema |

Não há tabela de usuários: no MVP o “usuário” é o conjunto de dispositivos pareados (roadmap, Fase 4). Envelopes (`nonce`, `ciphertext`) nunca são gravados.

### Evolução futura para PostgreSQL

Conforme [architecture.md](architecture.md) §12, SQLite em modo WAL atende ao MVP de instância única. Caso o relay passe a operar com múltiplas réplicas ou tabela `users`, `DATABASE_URL` valida explicitamente o esquema (rejeitando URLs não-SQLite no protótipo) e a camada `backend/app/persistence/` centraliza as migrações e o ciclo de vida para futura troca de driver (`psycopg`/`asyncpg`) sem alterar as regras de negócio.

## 3. Extensão (`chrome.storage.local`)

| Chave | Conteúdo |
| --- | --- |
| `settings` | Endereço do relay, nome do computador, modo de desenvolvimento, tamanho do gerador |
| `pairing` | link_id, device_id, device_token, chaves X25519, dados do celular, último `seq` |
| `autoSites` | Origens com preenchimento automático ligado (com permissão concedida) |

Senhas nunca são gravadas na extensão. Uma senha recém-gerada que não pôde ser salva fica só na memória do service worker por até 5 minutos.
