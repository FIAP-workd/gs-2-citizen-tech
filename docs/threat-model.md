# Modelo de ameaças — Chave Segura

**Versão:** 1.0 (Fase 0)\
**Escopo:** MVP descrito em [architecture.md](architecture.md)\
**Relacionado:** [ADR 0001](adr/0001-bibliotecas-criptografia.md),
[message-contract.md](message-contract.md)

## 1. Ativos

| Ativo                          | Onde vive                                 | Impacto se exposto        |
| ------------------------------ | ----------------------------------------- | ------------------------- |
| Senhas e usuários de serviços  | Cofre Android (cifrado)                   | Crítico                   |
| Senha mestra                   | Apenas na memória durante o desbloqueio   | Crítico                   |
| Vault key (VK)                 | Memória do Android enquanto desbloqueado  | Crítico                   |
| Chaves privadas X25519         | Android (Keystore/arquivo protegido) e extensão (`chrome.storage.local`) | Alto |
| Tokens de pareamento e de dispositivo | Clientes; backend guarda só o hash | Médio                     |
| Metadados (dispositivos, horários) | Backend                               | Baixo a médio (privacidade) |

## 2. Atores de ameaça

| Ator                          | Capacidade presumida                                              |
| ----------------------------- | ----------------------------------------------------------------- |
| Site malicioso / phishing     | Controla o HTML da página, domínios parecidos, iframes            |
| Operador ou invasor do backend| Lê e altera tudo que passa pelo relay e pelo banco do servidor    |
| Atacante na rede              | Intercepta tráfego; não quebra TLS válido                         |
| Ladrão do celular             | Posse física do aparelho bloqueado ou de uma cópia do banco       |
| Pessoa próxima                | Vê a tela, tenta ler o QR Code ou usar o computador destravado    |
| Extensão ou malware local     | Fora de escopo quando tem privilégios de sistema (ver §6)         |

## 3. Limites de confiança

1.  **Android** — confiável quando desbloqueado; é a autoridade.
2.  **Backend** — **não confiável para segredos**. Só roteia.
3.  **Extensão** — confiável condicionalmente, após pareamento.
4.  **Página web** — sempre hostil.

## 4. Ameaças e mitigações

Legenda STRIDE: **S** falsificação, **T** adulteração, **R** repúdio,
**I** vazamento de informação, **D** negação de serviço, **E** elevação
de privilégio.

### 4.1 Cofre local (Android)

| #  | Ameaça                                              | STRIDE | Mitigação                                                                     | Fase | Teste                         |
| -- | --------------------------------------------------- | ------ | ----------------------------------------------------------------------------- | ---- | ----------------------------- |
| C1 | Cópia do banco e força bruta da senha mestra        | I      | Argon2id 64 MiB; VK embrulhada também por chave do Keystore não exportável    | 1    | Banco local copiado           |
| C2 | Senha mestra incorreta desbloqueia o cofre          | E      | Desbloqueio só após decifrar VK com tag GCM válida                            | 1    | Senha mestra incorreta        |
| C3 | Troca de ciphertext entre registros ou campos       | T      | AAD com `credential_id` e nome do campo                                       | 1    | Unitário de cifragem          |
| C4 | Reuso de nonce GCM                                  | I/T    | Nonce aleatório de 12 B por operação                                          | 1    | Unitário de cifragem          |
| C5 | Backup automático leva o banco para a nuvem         | I      | `android:allowBackup="false"`                                                 | 0    | Revisão do manifesto          |
| C6 | Cofre aberto em aparelho desacompanhado             | I      | Bloqueio por inatividade; senha só exibida após autenticação                  | 1    | Manual                        |
| C7 | Senha visível em capturas de tela / recentes        | I      | `FLAG_SECURE` nas telas com segredos                                          | 1    | Manual                        |
| C8 | Biometria usada como chave universal                | E      | Biometria só libera cópia da VK no Keystore; senha mestra continua necessária | 2    | Manual                        |
| C9 | Segredos em logs                                    | I      | Proibido logar senha, VK, tokens; revisão no CI                               | 1+   | Revisão de código             |

### 4.2 Pareamento

| #  | Ameaça                                               | STRIDE | Mitigação                                                                    | Fase | Teste                       |
| -- | ---------------------------------------------------- | ------ | ---------------------------------------------------------------------------- | ---- | --------------------------- |
| P1 | QR Code fotografado e usado depois                   | S      | Token de uso único, validade de 120 s                                        | 4    | QR expirado / reutilizado   |
| P2 | Backend troca a chave pública da extensão (MITM)     | S/T    | Chave pública vem no QR; SAS de 6 dígitos comparado nas duas telas           | 4    | Servidor malicioso          |
| P3 | Pareamento sem consentimento do dono do celular      | E      | Confirmação obrigatória no Android mostrando o nome do computador            | 4    | Manual                      |
| P4 | Força bruta de `pairing_id`/token                    | S      | Token de 256 bits; rate limiting; hash no servidor                           | 4    | Unitário do backend         |

### 4.3 Canal de comunicação

| #  | Ameaça                                               | STRIDE | Mitigação                                                                       | Fase | Teste                       |
| -- | ---------------------------------------------------- | ------ | ------------------------------------------------------------------------------- | ---- | --------------------------- |
| M1 | Relay lê credenciais                                 | I      | E2E XChaCha20-Poly1305 com chaves de `crypto_kx`; domínio só no payload         | 4    | Backend tentando ler        |
| M2 | Relay altera cabeçalho ou payload                    | T      | AEAD com cabeçalho como AAD                                                     | 4    | Mensagem alterada           |
| M3 | Replay de uma resposta `approved`                    | S      | `msg_id` único, janela de `ts`, `seq` crescente, `request_id` usado uma vez     | 5    | Mensagem repetida           |
| M4 | Reflexão (mensagem devolvida ao próprio remetente)   | S      | Chaves direcionais `rx`/`tx`                                                    | 4    | Unitário                    |
| M5 | Dispositivo revogado continua pedindo credenciais    | E      | Revogação local imediata; backend encerra `link_id`                             | 4/5  | Dispositivo revogado        |
| M6 | Metadados revelam hábitos de uso                     | I      | Backend não recebe domínio; `corr` aleatório; logs mínimos e sem conteúdo       | 4    | Inspeção de banco e logs    |
| M7 | Inundação de mensagens / pedidos ao celular          | D      | Rate limiting por dispositivo; limite de 16 KiB por frame                       | 4    | Unitário do backend         |
| M8 | Leitura de mensagens antigas após vazar chave privada| I      | **Aceito no MVP** (sem forward secrecy); relay não guarda envelopes             | —    | —                           |

### 4.4 Extensão e preenchimento

| #  | Ameaça                                                  | STRIDE | Mitigação                                                                  | Fase | Teste                     |
| -- | ------------------------------------------------------- | ------ | -------------------------------------------------------------------------- | ---- | ------------------------- |
| E1 | Domínio parecido (`gmail.com.site-falso.com`)           | S      | Comparação exata do hostname normalizado                                   | 3    | Domínio semelhante        |
| E2 | Página HTTP ou origem opaca                             | S/I    | Exigir HTTPS (exceção explícita só em desenvolvimento)                     | 3    | Unitário de domínio       |
| E3 | Iframe malicioso dentro de site legítimo                | S      | Preencher apenas no frame de topo ou em origens autorizadas                | 3    | Unitário / site de demo   |
| E4 | Campos ocultos capturam a senha                         | I      | Preencher apenas campos visíveis identificados como login                  | 3    | Site de demo              |
| E5 | Página lê a senha após preenchimento                    | I      | Inerente ao autofill; preencher só após autorização e no domínio correto   | 3    | —                         |
| E6 | Content script obtém o cofre ou chaves                  | E      | Content script recebe só a credencial atual; chaves ficam no service worker| 3    | Revisão de código         |
| E7 | Permissões amplas expõem todos os sites                 | E      | `activeTab` + `scripting`; sem `<all_urls>`                                | 0/3  | Revisão do manifesto      |
| E8 | Resposta tardia preenche outra página                   | S      | Revalidar hostname e `expires_at` antes de preencher                       | 5    | Sessão expirada           |

### 4.5 Modo automático

| #  | Ameaça                                                     | STRIDE | Mitigação                                                                         | Fase | Teste                    |
| -- | ---------------------------------------------------------- | ------ | --------------------------------------------------------------------------------- | ---- | ------------------------ |
| A1 | Computador pareado libera todas as credenciais sem aviso   | E      | Confiança explícita por dispositivo; credenciais sensíveis sempre pedem confirmação | 5    | Manual                   |
| A2 | Sessão de confiança esquecida aberta                       | E      | Duração limitada e configurável; expira com o bloqueio do cofre                   | 5    | Sessão automática expirada |
| A3 | Falha de verificação libera credencial                     | E      | Falhar fechado em toda condição não verificada                                    | 5    | Android desconectado     |

## 5. Decisões derivadas deste modelo

1.  O backend **não recebe o domínio** das solicitações. A tabela
    `fill_requests` guarda apenas `corr`, dispositivos, estado e
    horários — mais restritivo que o modelo de dados original.
2.  `android:allowBackup="false"` desde a Fase 0.
3.  A extensão começa apenas com `activeTab`, `scripting` e `storage`.
4.  Envelopes não são enfileirados nem gravados no servidor.

## 6. Fora de escopo / riscos aceitos

-   Malware com privilégios de sistema no celular ou no computador
    (keylogger, root, depurador).
-   Aparelhos Android com root ou sem TEE: o Keystore pode ser apenas
    em software.
-   Ausência de *forward secrecy* no canal E2E (ver ADR 0001).
-   Chave privada da extensão sem proteção de hardware.
-   Recuperação de acesso: perder celular **e** senha mestra significa
    perder o cofre. Não existe porta dos fundos.
-   Ataques de canal lateral e análise de tráfego avançada.

## 7. Revisão

Revisar este documento ao fim de cada fase e sempre que o contrato de
mensagens ou o ADR de criptografia mudar. Os testes da coluna "Teste"
compõem a lista de testes negativos da Fase 6.
