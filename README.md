# Chave Segura --- Especificação do Projeto

## 1. Visão geral

**Chave Segura** é um gerenciador de senhas acessível, composto por um
aplicativo Android e uma extensão para Google Chrome. Seu objetivo é
facilitar a organização e o uso seguro de credenciais, especialmente
para pessoas que têm dificuldade em administrar várias senhas.

A proposta combina:

-   Cofre local de credenciais protegido por criptografia.
-   Aplicativo Android como centro de armazenamento e autorização.
-   Extensão Chrome para identificar sites e preencher formulários.
-   Pareamento entre celular e navegador por QR Code.
-   Dois modos de autorização: confirmação pelo celular e preenchimento
    automático em dispositivos confiáveis.

**Princípio arquitetural:** o celular é o cofre e a autoridade de
autorização; a extensão é o meio de interação com os sites. O servidor
intermediário não deve ter acesso às senhas descriptografadas nem às
chaves capazes de abrir o cofre.

## 2. Problema e proposta de valor

Muitas pessoas têm dificuldade para criar, memorizar, recuperar e
organizar senhas. Isso pode levar à reutilização da mesma senha em
vários serviços, ao registro de credenciais em locais desprotegidos ou à
dependência de terceiros para acessar contas pessoais.

O Chave Segura busca oferecer:

1.  Organização e acessibilidade: armazenar e localizar credenciais em
    um único lugar.
2.  Facilidade de acesso: permitir o preenchimento de credenciais no
    computador sem digitação manual.
3.  Segurança: reduzir exposição a golpes, preenchimentos em domínios
    incorretos e armazenamento inadequado.
4.  Autonomia: permitir que o próprio usuário controle suas contas e
    autorizações.

O diferencial não é apenas armazenar senhas, mas oferecer uma
experiência simples e segura para pessoas com diferentes níveis de
familiaridade tecnológica.

## 3. Escopo funcional

### 3.1 Aplicativo Android

Funcionalidades essenciais do MVP:

| Funcionalidade | Descrição |
--- | ---
Cadastro de credenciais | Nome do serviço, usuário, senha e endereço do site
Organização | Categorias como bancos, e-mail, redes sociais e serviços
Pesquisa| Busca por nome, categoria ou domínio
Visualização | Exibição da senha mediante autenticação
Gerador de senhas | Criação de senhas aleatórias e fortes
Diagnóstico | Identificação de senhas fracas e reutilizadas
Pareamento | Leitura de QR Code para conectar a extensão
Aprovação | Autorizar ou negar uma solicitação de preenchimento
Modo confiável | Permitir preenchimento automático em dispositivos previamente autorizados
  

Diretrizes de experiência:

-   Letras legíveis e botões grandes.
-   Linguagem simples e instruções claras.
-   Poucas etapas para tarefas comuns.
-   Feedback explícito quando uma ação é concluída ou recusada.
-   Confirmação adicional para operações sensíveis.

### 3.2 Extensão para Chrome

Responsabilidades essenciais:

1.  Identificar o domínio da página atual.
2.  Verificar se existe uma credencial compatível.
3.  Solicitar autorização ao aplicativo quando necessário.
4.  Preencher os campos de usuário e senha após autorização.

Fluxo básico:

-   O usuário abre um site.
-   A extensão identifica o domínio.
-   A extensão verifica se existe credencial cadastrada para aquele
    domínio.
-   No modo de confirmação, envia uma solicitação ao celular.
-   Após a autorização, recebe a credencial por um canal protegido.
-   A extensão valida domínio e sessão e preenche o formulário.

O MVP deve começar com formulários HTML convencionais de usuário e
senha. Não é necessário reconhecer todos os formulários existentes na
internet.

### 3.3 Modos de autorização

#### Modo 1 --- Confirmação pelo celular

1.  A extensão identifica a credencial correspondente.
2.  O celular recebe uma solicitação.
3.  O usuário confere o serviço e confirma ou recusa.
4.  O aplicativo libera a credencial específica para aquela sessão.
5.  A extensão preenche o formulário.

Este deve ser o modo padrão.

#### Modo 2 --- Preenchimento automático

1.  O computador já foi pareado e marcado como confiável.
2.  O usuário desbloqueou o cofre.
3.  A extensão reconhece um domínio permitido.
4.  O aplicativo verifica as políticas de confiança.
5.  A credencial é liberada sem uma nova confirmação manual, respeitando
    as restrições configuradas.

Regras recomendadas:

-   A confiança deve ser explícita e revogável.
-   A sessão de desbloqueio deve ter duração limitada.
-   O domínio deve corresponder à credencial cadastrada.
-   Credenciais de bancos, serviços financeiros e outros serviços
    sensíveis devem exigir confirmação adicional.
-   O modo automático não deve liberar todas as credenciais apenas
    porque o computador foi pareado.

## 4. Arquitetura técnica

### 4.1 Componentes

| Componente | Tecnologia sugerida | Responsabilidade |
| :--- | :--- | :--- |
| Aplicativo Android | Kotlin + Jetpack Compose | Interface, cofre, autenticação e aprovações |
| Persistência local | Room + SQLite | Armazenamento local de dados criptografados |
| Proteção de chaves | Android Keystore | Proteção de material criptográfico |
| Extensão Chrome | TypeScript + Manifest V3 | Reconhecimento de domínio, solicitações e preenchimento |
| Backend | Python + FastAPI | Pareamento, sessões e encaminhamento de mensagens |
| Comunicação | WebSocket sobre TLS | Troca de mensagens entre dispositivos |
| Banco do servidor | SQLite no protótipo; PostgreSQL se necessário | Metadados de dispositivos e sessões |
| Infraestrutura | Docker e GitHub Actions | Ambiente reproduzível e automação de testes |

### 4.2 Visão da arquitetura

``` text
┌──────────────────────────────────────┐
│           Aplicativo Android         │
│                                      │
│  Cofre local · Criptografia          │
│  Autenticação · Biometria            │
│  Aprovações · Pareamento             │
└──────────────────┬───────────────────┘
                   │
       Mensagens criptografadas
                   │
┌──────────────────▼───────────────────┐
│          Servidor de comunicação     │
│                                      │
│  Pareamento · Sessões                │
│  Relay de mensagens · Expiração      │
│                                      │
│  Não conhece as senhas               │
└──────────────────┬───────────────────┘
                   │
             Canal seguro
                   │
┌──────────────────▼───────────────────┐
│           Extensão Chrome            │
│                                      │
│  Reconhecimento de domínio           │
│  Solicitações · Preenchimento        │
└──────────────────────────────────────┘
```

O servidor funciona como intermediário de comunicação, não como
repositório central de credenciais. As mensagens entre os dispositivos
devem ser protegidas de ponta a ponta.

### 4.3 Pareamento por QR Code

O QR Code deve servir para iniciar o pareamento, não para transportar
senhas ou a chave mestra.

Fluxo:

1.  A extensão solicita uma sessão de pareamento.
2.  O servidor gera um identificador aleatório, de uso único e com
    validade curta.
3.  A extensão apresenta um QR Code com os dados necessários para
    localizar a sessão.
4.  O aplicativo escaneia o código e apresenta o nome do computador e os
    dados de confirmação.
5.  O usuário confirma o pareamento no celular.
6.  Os dispositivos estabelecem identidades criptográficas próprias.
7.  O servidor mantém apenas os metadados necessários da sessão.

O QR Code, isoladamente, não deve autorizar o acesso. A confirmação
precisa ocorrer no aplicativo.

### 4.4 Criptografia do cofre

Estratégia proposta:

1.  O usuário define uma senha mestra.
2.  O aplicativo deriva uma chave usando Argon2id e um salt aleatório.
3.  Essa chave protege a chave de criptografia do cofre.
4.  As credenciais são criptografadas individualmente com um algoritmo
    autenticado, como AES-256-GCM.
5.  O banco local armazena os dados criptografados e os metadados
    necessários.

Distinção importante:

-   **Hash:** usado para verificar um segredo sem recuperar seu valor
    original.
-   **Criptografia reversível:** necessária para armazenar e
    posteriormente recuperar uma senha de serviço.

A senha mestra não deve ser enviada ao servidor. O servidor não deve
possuir as chaves necessárias para descriptografar o cofre.

No Android, utilizar Android Keystore para proteger material
criptográfico e biometria como mecanismo de desbloqueio local. A
biometria não deve substituir a senha mestra nem funcionar como chave
universal de recuperação.

Na extensão, limitar permissões, evitar acesso indiscriminado a todos os
sites e manter segredos em memória pelo menor tempo possível.

## 5. Modelo de dados

A separação principal é entre os dados do cofre e os dados de
comunicação. O cofre pode permanecer inteiramente local no primeiro MVP.

### 5.1 Banco local do aplicativo

SQLite será suficiente para o MVP. Os campos sensíveis devem ser
criptografados antes de serem gravados.

#### Tabela `credentials`

  Campo               | Tipo      | Descrição
  --------------------| ----------| ----------------------------
  id                  | UUID      | Identificador
  title               | TEXT     |  Nome do serviço
  username_encrypted  | BLOB      | Usuário criptografado
  password_encrypted  | BLOB      | Senha criptografada
  website_domain      | TEXT      | Domínio autorizado
  category_id         | UUID     |  Categoria
  notes_encrypted    |  BLOB      | Observações criptografadas
  created_at         |  DATETIME  | Data de criação
  updated_at         |  DATETIME  | Última atualização

#### Tabela `categories`

  Campo |  Tipo |  Descrição
  ------- |------| -------------------
  id    |  UUID |  Identificador
  name  |  TEXT |  Nome da categoria
  icon  |  TEXT |  Ícone associado

#### Tabela `trusted_devices`

  Campo          |      Tipo    |   Descrição
  -------------------- | ----------| ---------------------------
  id              |     UUID   |   Identificador
  device_name      |    TEXT    |   Nome do dispositivo
  public_key       |    TEXT     |  Chave pública
  authorization_mode |  TEXT   |    `approval` ou `automatic`
  status            |   TEXT    |   Ativo ou revogado
  created_at      |     DATETIME |  Data do pareamento
  last_seen       |     DATETIME |  Última atividade

#### Tabela `security_events`

  Campo       |    Tipo    |   Descrição
  --------------- | ---------- | --------------------------------------
  id            |  UUID   |    Identificador
  event_type    |  TEXT  |     Tipo de evento
  device_id      | UUID    |   Dispositivo relacionado
  credential_id |  UUID   |    Credencial relacionada, se aplicável
  timestamp    |   DATETIME |  Data do evento
  result       |   TEXT    |   Sucesso ou recusa

Não registrar senhas, tokens de sessão ou conteúdo de mensagens no
histórico.

### 5.2 Banco do servidor

O servidor deve armazenar apenas dados operacionais e metadados.

  Tabela                     Responsabilidade
  -------------------------- -------------------------------------------------
  `users`                    Identificação da conta do usuário
  `devices`                  Dispositivos registrados e suas chaves públicas
  `pairing_sessions`         Sessões temporárias de pareamento
  `communication_sessions`   Sessões ativas entre dispositivos
  `fill_requests`            Solicitações de preenchimento e seus estados

A tabela `fill_requests` não armazenará a senha solicitada. Guardará
apenas informações como dispositivo solicitante, domínio, identificador
da credencial, horário e estado da solicitação.

### 5.3 Relacionamento conceitual

``` mermaid
erDiagram
    USER ||--o{ DEVICE : registers
    USER ||--|| VAULT : owns
    VAULT ||--o{ CREDENTIAL : contains
    CATEGORY ||--o{ CREDENTIAL : classifies
    DEVICE ||--o{ PAIRING_SESSION : participates
    DEVICE ||--o{ FILL_REQUEST : requests
    CREDENTIAL ||--o{ FILL_REQUEST : references
    DEVICE ||--o{ SECURITY_EVENT : generates

    USER {
        uuid id PK
        string email
        datetime created_at
    }
    VAULT {
        uuid id PK
        uuid user_id FK
        blob encrypted_vault_key
        string kdf_parameters
    }
    CREDENTIAL {
        uuid id PK
        uuid vault_id FK
        uuid category_id FK
        string title
        blob encrypted_username
        blob encrypted_password
        string website_domain
    }
    CATEGORY {
        uuid id PK
        string name
    }
    DEVICE {
        uuid id PK
        uuid user_id FK
        string device_name
        string public_key
        string status
    }
    PAIRING_SESSION {
        uuid id PK
        uuid device_id FK
        datetime expires_at
        string status
    }
    FILL_REQUEST {
        uuid id PK
        uuid device_id FK
        uuid credential_id FK
        string domain
        string status
    }
    SECURITY_EVENT {
        uuid id PK
        uuid device_id FK
        string event_type
        datetime created_at
    }
```

Este diagrama é conceitual. Na implementação, se o cofre permanecer
local, as tabelas `vault` e `credentials` pertencem ao banco Android,
não ao servidor.

## 6. Fluxo técnico do preenchimento automático

Cenário: o usuário acessa o Gmail.

``` mermaid
sequenceDiagram
    actor U as Usuário
    participant E as Extensão Chrome
    participant S as Servidor Relay
    participant A as App Android
    participant V as Cofre Local

    U->>E: Abre página de login
    E->>E: Identifica domínio
    E->>S: Solicita credencial para domínio
    S->>A: Encaminha solicitação criptografada
    A->>V: Consulta credencial correspondente
    V-->>A: Retorna credencial após desbloqueio

    alt Modo confirmação
        A->>U: Solicita autorização
        U->>A: Confirma
        A->>S: Envia resposta criptografada
        S->>E: Encaminha resposta
    else Modo automático
        A->>A: Verifica política de confiança
        A->>S: Autoriza dentro da política
        S->>E: Encaminha resposta
    end

    E->>E: Valida domínio e sessão
    E->>E: Preenche os campos
```

Regras importantes:

-   A confiança deve ser explícita e revogável.
-   A sessão de desbloqueio deve ter duração limitada.
-   O domínio deve corresponder à credencial cadastrada.
-   Credenciais sensíveis devem exigir confirmação adicional.
-   A extensão não deve preencher credenciais em domínios incompatíveis.
-   O domínio deve ser validado para impedir falsificações como
    `gmail.com.site-falso.com`.
-   A extensão deve limitar o tempo em que a senha permanece em memória.

## 7. Plano de implementação

O desenvolvimento deve ser dividido em seis etapas, nesta ordem.

### Etapa 1 --- Fundação do aplicativo

-   Criar o projeto Android.
-   Construir a tela de desbloqueio.
-   Implementar criação e desbloqueio do cofre.
-   Implementar cadastro, edição e consulta de credenciais.
-   Persistir dados criptografados.

**Critério de conclusão:** cadastrar uma senha, fechar o aplicativo,
abri-lo novamente e recuperar a credencial somente após autenticação.

### Etapa 2 --- Segurança e geração de senhas

-   Implementar Argon2id.
-   Implementar criptografia autenticada.
-   Integrar Android Keystore.
-   Criar gerador de senhas.
-   Implementar diagnóstico de senhas repetidas e fracas.

**Critério de conclusão:** inspecionar o banco e demonstrar que as
credenciais não estão armazenadas em texto aberto.

### Etapa 3 --- Extensão Chrome

-   Criar extensão Manifest V3.
-   Detectar domínio da página.
-   Identificar formulários de login.
-   Criar interface para selecionar credenciais.
-   Implementar preenchimento manual autorizado.

**Critério de conclusão:** preencher um formulário de teste com uma
credencial cadastrada.

### Etapa 4 --- Pareamento e comunicação

-   Criar API de pareamento.
-   Gerar QR Codes temporários.
-   Implementar cadastro e revogação de dispositivos.
-   Criar comunicação via WebSocket.
-   Proteger mensagens entre os dispositivos.

**Critério de conclusão:** conectar o celular ao Chrome e trocar
mensagens sem transmitir senhas em texto aberto pelo servidor.

### Etapa 5 --- Autorização e autofill

-   Implementar solicitações no Android.
-   Criar fluxo de aprovação.
-   Implementar sessão temporária de confiança.
-   Criar regras para domínios sensíveis.
-   Implementar preenchimento automático e revogação.

**Critério de conclusão:** demonstrar os dois modos de autorização e
bloquear o preenchimento em um domínio incompatível.

### Etapa 6 --- Testes e apresentação

-   Testar cenários de falha.
-   Testar tentativas de pareamento indevido.
-   Testar expiração de sessões.
-   Validar a experiência com usuários.
-   Preparar demonstração e documentação técnica.

**Critério de conclusão:** apresentar uma jornada completa de uso, com
evidências de segurança e usabilidade.

## 8. Estrutura do repositório

``` text
chave-segura/
│
├── android/
│   ├── app/
│   │   └── src/main/
│   │       ├── java/
│   │       │   └── com/chavesegura/
│   │       │       ├── data/
│   │       │       ├── domain/
│   │       │       ├── security/
│   │       │       ├── pairing/
│   │       │       └── ui/
│   │       └── AndroidManifest.xml
│   │
├── extension/
│   ├── src/
│   │   ├── background/
│   │   ├── content/
│   │   ├── popup/
│   │   ├── security/
│   │   └── communication/
│   └── manifest.json
│
├── backend/
│   ├── app/
│   │   ├── api/
│   │   ├── services/
│   │   ├── pairing/
│   │   ├── communication/
│   │   └── models/
│   └── tests/
│
├── docs/
│   ├── architecture.md
│   ├── threat-model.md
│   ├── data-model.md
│   └── test-plan.md
│
├── README.md
└── docker-compose.yml
```

## 9. Testes de segurança

O sistema precisa demonstrar que falha de maneira segura.

  Teste                   |            Resultado esperado
  --- | ---
  Senha mestra incorreta       |      Cofre não é desbloqueado
  QR Code expirado              |     Pareamento recusado
  QR Code reutilizado            |    Pareamento recusado
  Dispositivo revogado            |   Solicitações recusadas
  Domínio semelhante ao original   |  Preenchimento bloqueado
  Servidor interceptando mensagens |  Não consegue ler credenciais
  Sessão automática expirada        | Nova autorização necessária
  Aplicativo desconectado       |     Modo de confirmação indisponível
  Banco local copiado           |     Credenciais permanecem criptografadas

Também é necessário testar recuperação de acesso. Se a pessoa perder o
celular e esquecer a senha mestra, o projeto deve explicar claramente
quais opções existem e quais dados podem ser recuperados. Não se deve
criar uma porta dos fundos que comprometa o cofre.

## 10. Demonstração para o Global Solution

A apresentação pode seguir uma narrativa simples:

1.  **Problema:** uma pessoa possui diversas contas, esquece as senhas e
    acaba reutilizando ou anotando credenciais de maneira insegura.
2.  **Solução:** ela cadastra suas contas no aplicativo e passa a
    administrar suas credenciais em um único cofre protegido.
3.  **Integração:** ela conecta o computador ao celular por QR Code e
    passa a acessar os serviços com autorização segura.
4.  **Impacto:** mais autonomia digital, menos dependência de terceiros
    e melhor proteção das contas pessoais.

A demonstração deve mostrar:

-   Cadastro de uma credencial no Android.
-   Armazenamento criptografado.
-   Pareamento com a extensão por QR Code.
-   Preenchimento com confirmação pelo celular.
-   Preenchimento automático em dispositivo confiável.
-   Bloqueio de uma tentativa de preenchimento em domínio incompatível.

## 11. Priorização do MVP

### Essencial

-   Aplicativo Android.
-   Cofre local criptografado.
-   Cadastro, edição, consulta e exclusão de credenciais.
-   Gerador de senhas.
-   Extensão Chrome.
-   Identificação de domínio.
-   Pareamento por QR Code.
-   Confirmação pelo celular.
-   Modo automático limitado a dispositivos e domínios confiáveis.
-   Revogação de dispositivos.
-   Testes básicos de segurança.

### Fora do primeiro MVP

-   Recuperação avançada de conta.
-   Compartilhamento de senhas com familiares.
-   Monitoramento de vazamentos em bases externas.
-   Integração com biometria em diferentes dispositivos.
-   Análise avançada de tentativas de phishing.
-   Suporte a todos os navegadores e todos os tipos de formulários.

## 12. Recomendações de execução

A ordem recomendada é:

1.  Criar o aplicativo Android.
2.  Implementar criptografia e armazenamento.
3.  Fazer cadastro e recuperação de credenciais.
4.  Criar a extensão e seu preenchimento em um site de teste.
5.  Implementar pareamento.
6.  Conectar os fluxos de autorização.

Não começar pelo QR Code nem pela extensão. Primeiro, o cofre local
precisa funcionar de maneira segura e testável.

O principal diferencial técnico a demonstrar é a combinação de:

-   Criptografia no lado do cliente.
-   Comunicação entre dispositivos sem exposição das credenciais.
-   Controle explícito de autorização.
-   Detecção de incompatibilidade de domínios.
-   Interface acessível e fácil de usar.

## 13. Referências técnicas

-   OWASP --- [Password Storage Cheat
    Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
-   OWASP --- [Cryptographic Storage Cheat
    Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cryptographic_Storage_Cheat_Sheet.html)
-   NIST --- [Digital Identity Guidelines, SP
    800-63B](https://pages.nist.gov/800-63-4/sp800-63b.html)
-   Chromium --- [Extension
    Permissions](https://developer.chrome.com/docs/extensions/develop/concepts/declare-permissions/)
-   Android Developers --- [Android Keystore
    System](https://developer.android.com/privacy-and-security/keystore)
-   Android Developers --- [Biometric
    authentication](https://developer.android.com/identity/sign-in/biometric-auth)

------------------------------------------------------------------------

**Resumo:** o Chave Segura deve começar como um cofre local
criptografado no Android, evoluir para uma extensão Chrome capaz de
preencher credenciais em domínios autorizados e, por fim, integrar os
dois componentes por pareamento seguro. O servidor deve encaminhar
mensagens, mas nunca conhecer as senhas.
