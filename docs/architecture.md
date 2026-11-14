# Arquitetura de Software --- Chave Segura

**Documento:** `docs/architecture.md`\
**Versão:** 1.0\
**Status:** Proposta para o MVP\
**Produto:** Chave Segura --- gerenciador de senhas para Android e
extensão Chrome

------------------------------------------------------------------------

## 1. Objetivo

Este documento descreve a arquitetura técnica do Chave Segura: seus
componentes, responsabilidades, limites de confiança, fluxos de
comunicação, decisões de segurança e organização interna.

O sistema é composto por:

1.  Aplicativo Android, responsável pelo cofre local e pelas
    autorizações.
2.  Extensão Chrome, responsável por identificar páginas de login e
    preencher credenciais autorizadas.
3.  Backend de comunicação, responsável por pareamento, sessões e
    encaminhamento de mensagens entre os dispositivos.

A arquitetura prioriza segurança, simplicidade de operação e
possibilidade de evolução incremental durante o Global Solution.

## 2. Princípios arquiteturais

| Princípio                  | Aplicação                                                                                                          |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| Cofre local-first          | As credenciais permanecem armazenadas no dispositivo Android durante o MVP.                                        |
| Zero knowledge do servidor | O backend não recebe senhas em texto aberto nem possui chaves para descriptografar o cofre.                        |
| Menor privilégio           | Cada componente recebe somente as permissões necessárias para executar suas funções.                               |
| Segurança por padrão       | A confirmação pelo celular é o modo de autorização inicial.                                                        |
| Confiança explícita        | Dispositivos e domínios precisam ser autorizados antes de permitir o preenchimento de credenciais.                 |
| Criptografia autenticada   | Os dados sensíveis são protegidos contra leitura e alteração não autorizadas.                                      |
| Segredos efêmeros          | Senhas e tokens permanecem em memória pelo menor tempo possível.                                                   |
| Evolução incremental       | O MVP começa com um cofre funcional antes de avançar para a integração completa entre Android, extensão e backend. |

### 2.1 Decisão central

O aplicativo Android é a autoridade sobre as credenciais. A extensão não
mantém uma cópia permanente do cofre. O backend apenas encaminha
mensagens protegidas e mantém metadados operacionais.

Consequências:

-   Se o backend ficar indisponível, o usuário ainda poderá consultar o
    cofre no Android.
-   O preenchimento via extensão dependerá de o aplicativo Android estar
    disponível e de existir uma sessão de comunicação autorizada.
-   O backend não poderá recuperar senhas perdidas.
-   A perda do dispositivo e da senha mestra pode significar perda
    irrecuperável das credenciais, caso não exista um mecanismo de
    recuperação previamente configurado.

## 3. Visão geral da arquitetura

``` mermaid
flowchart TB
    subgraph Android["Aplicativo Android"]
        UI["Interface Jetpack Compose"]
        AUTH["Autenticação e sessão local"]
        VAULT["Serviço do cofre"]
        CRYPTO["Camada criptográfica"]
        DB[("Room / SQLite")]
        KEYSTORE["Android Keystore"]

        UI --> AUTH
        AUTH --> VAULT
        VAULT --> CRYPTO
        VAULT --> DB
        CRYPTO --> KEYSTORE
    end

    subgraph Backend["Backend de comunicação"]
        API["FastAPI"]
        PAIR["Serviço de pareamento"]
        RELAY["Relay de mensagens"]
        SESSION["Gestão de sessões"]
        META[("Banco de metadados")]

        API --> PAIR
        API --> SESSION
        API --> RELAY
        PAIR --> META
        SESSION --> META
    end

    subgraph Extension["Extensão Chrome"]
        POPUP["Popup / interface"]
        BG["Service worker"]
        CONTENT["Content scripts"]
        EXTSEC["Validação de domínio e sessão"]

        POPUP --> BG
        BG --> EXTSEC
        BG --> CONTENT
    end

    Android <-->|"Mensagens E2E protegidas via TLS"| Backend
    Backend <-->|"Mensagens E2E protegidas via TLS"| Extension
```

O diagrama apresenta os principais componentes lógicos. A implementação
pode distribuir essas responsabilidades em módulos e processos
diferentes.

## 4. Componentes e responsabilidades

### 4.1 Aplicativo Android

**Tecnologias:** Kotlin, Jetpack Compose, Room, Android Keystore e
bibliotecas criptográficas consolidadas.

O aplicativo é responsável por:

-   Criar e desbloquear o cofre.
-   Cadastrar, editar, consultar e excluir credenciais.
-   Gerar senhas.
-   Identificar senhas fracas ou reutilizadas.
-   Registrar dispositivos pareados.
-   Receber solicitações de preenchimento.
-   Exibir o domínio e o serviço solicitado.
-   Autorizar ou negar operações.
-   Aplicar as políticas do modo automático.
-   Revogar dispositivos confiáveis.

#### Módulos internos sugeridos

  Módulo            Responsabilidade
  ----------------- --------------------------------------------------
  `ui`              Telas, navegação e componentes visuais
  `domain`          Regras de negócio e casos de uso
  `data`            Repositórios e persistência
  `security`        Derivação de chaves, criptografia e autenticação
  `vault`           Operações sobre credenciais
  `pairing`         Fluxo de pareamento e cadastro de dispositivos
  `communication`   Comunicação com o backend
  `authorization`   Políticas de aprovação e modo automático

### 4.2 Extensão Chrome

**Tecnologias:** TypeScript, Chrome Extension Manifest V3.

A extensão é responsável por:

-   Identificar a origem da página atual.
-   Reconhecer formulários convencionais de login.
-   Solicitar credenciais para um domínio específico.
-   Exibir o estado da solicitação.
-   Receber uma credencial autorizada.
-   Preencher os campos corretos.
-   Impedir preenchimento em domínios incompatíveis.
-   Limpar referências temporárias a segredos após o uso.

#### Módulos internos sugeridos

  Módulo            Responsabilidade
  ----------------- --------------------------------------------
  `background`      Service worker e coordenação das operações
  `content`         Detecção de formulários e preenchimento
  `popup`           Interface da extensão
  `domain`          Normalização e validação de domínios
  `communication`   Comunicação com o backend
  `security`        Validação de sessão, origem e resposta
  `pairing`         Início e conclusão do pareamento

Os content scripts devem receber somente a credencial necessária para a
página atual. Não devem ter acesso ao conjunto completo de credenciais.

### 4.3 Backend

**Tecnologias:** Python, FastAPI, WebSocket e SQLite no protótipo.
PostgreSQL poderá ser adotado se a necessidade de operação justificar.

O backend é responsável por:

-   Criar sessões temporárias de pareamento.
-   Validar o consumo de códigos de pareamento.
-   Registrar dispositivos e chaves públicas.
-   Encaminhar mensagens entre dispositivos conectados.
-   Gerenciar sessões de comunicação.
-   Expirar sessões e tokens.
-   Aplicar limites de requisições.
-   Registrar eventos operacionais sem incluir segredos.

O backend não deve:

-   Armazenar senhas de serviços.
-   Receber a senha mestra.
-   Armazenar chaves privadas dos dispositivos.
-   Descriptografar mensagens de autorização ou credenciais.
-   Registrar conteúdo sensível em logs.

#### Módulos internos sugeridos

  Módulo          Responsabilidade
  --------------- -------------------------------------------------
  `api`           Rotas HTTP e WebSocket
  `pairing`       Criação, confirmação e expiração de pareamentos
  `devices`       Registro, estado e revogação de dispositivos
  `relay`         Encaminhamento de mensagens
  `sessions`      Controle de sessões ativas
  `persistence`   Acesso ao banco de metadados
  `security`      Autenticação, validação e rate limiting

## 5. Armazenamento e modelo de dados

### 5.1 Cofre local

O banco local Android armazena metadados e valores criptografados.

Entidades principais:

-   `Credential`
-   `Category`
-   `TrustedDevice`
-   `SecurityEvent`
-   `VaultMetadata`

Os campos sensíveis de `Credential`, como nome de usuário, senha e
observações, devem ser criptografados antes da persistência. O domínio
pode ser mantido em formato pesquisável apenas se a análise de risco
aceitar essa exposição; uma alternativa é armazenar também o domínio de
forma criptografada e manter um índice derivado não reversível, com
cuidado contra ataques de dicionário.

### 5.2 Banco do backend

O banco do backend contém somente metadados necessários à comunicação:

-   `User`
-   `Device`
-   `PairingSession`
-   `CommunicationSession`
-   `FillRequestMetadata`

Não deve existir uma tabela de senhas no servidor.

### 5.3 Separação de dados

| Dado                      | Local de armazenamento                             | Visível ao backend?                                      |
| ------------------------- | -------------------------------------------------- | -------------------------------------------------------- |
| Senha de serviço          | Cofre Android criptografado                        | Não                                                      |
| Nome de usuário           | Cofre Android criptografado                        | Não                                                      |
| Senha mestra              | Não é armazenada em texto aberto                   | Não                                                      |
| Chave privada do Android  | Protegida no dispositivo                           | Não                                                      |
| Chave privada da extensão | Armazenamento protegido disponível à extensão      | Não                                                      |
| Chaves públicas           | Android, extensão e metadados do servidor          | Sim                                                      |
| Nome do dispositivo       | Android e metadados do servidor                    | Sim                                                      |
| Estado do pareamento      | Android e backend                                  | Sim                                                      |
| Domínio solicitado        | Extensão e metadados mínimos da solicitação        | Pode ser visível ao backend, conforme o desenho do relay |
| Conteúdo da credencial    | Somente Android e extensão durante o preenchimento | Não                                                      |

A minimização dos metadados é importante: mesmo sem conhecer senhas, o
servidor pode revelar padrões de uso se registrar excessivamente os
domínios e horários de acesso.

## 6. Arquitetura criptográfica

Esta seção define requisitos e responsabilidades, não uma implementação
criptográfica própria. A implementação deve utilizar bibliotecas
reconhecidas, versões mantidas e parâmetros revisados.

### 6.1 Proteção do cofre

Fluxo conceitual:

1.  O usuário cria uma senha mestra.
2.  O aplicativo gera um salt aleatório.
3.  Argon2id deriva uma chave a partir da senha mestra e do salt.
4.  A chave derivada protege uma chave aleatória do cofre.
5.  A chave do cofre criptografa cada credencial com um algoritmo
    autenticado, como AES-256-GCM.
6.  O banco armazena os dados cifrados, nonces, tags e parâmetros
    necessários.

Requisitos:

-   Nonce único por operação com a mesma chave, conforme as exigências
    do algoritmo.
-   Salt aleatório e individual por cofre.
-   Parâmetros de Argon2id versionados para permitir migração.
-   Verificação de integridade na descriptografia.
-   Nenhuma senha ou chave em logs.
-   Limpeza de buffers sensíveis quando tecnicamente possível.

### 6.2 Proteção de chaves no Android

O Android Keystore deve proteger o material criptográfico local, sempre
que possível com suporte de hardware.

A biometria pode ser usada para autorizar o uso de uma chave protegida
pelo Keystore. Ela não deve ser tratada como substituta automática da
senha mestra ou como mecanismo de recuperação.

### 6.3 Comunicação entre dispositivos

A comunicação deve utilizar:

-   TLS para proteger o transporte até o backend.
-   Criptografia de ponta a ponta entre Android e extensão.
-   Identidade criptográfica individual por dispositivo.
-   Proteção contra replay, com identificadores únicos, timestamps ou
    nonces e expiração.
-   Validação de integridade e autenticidade das mensagens.

Não desenvolver algoritmo criptográfico próprio. A escolha de protocolo
e biblioteca para o canal de ponta a ponta deve ser registrada e
revisada antes da implementação. Uma opção é utilizar um protocolo de
canal seguro estabelecido, como uma implementação mantida do Noise
Protocol Framework, em vez de combinar primitivas manualmente.

### 6.4 Limites de confiança

``` mermaid
flowchart LR
    subgraph TrustedMobile["Dispositivo Android — área confiável local"]
        VAULT["Cofre e chaves"]
        APPROVAL["Política de autorização"]
    end

    subgraph Relay["Backend — não confiável para segredos"]
        SERVER["Relay e metadados"]
    end

    subgraph TrustedBrowser["Computador — área confiável condicionada"]
        EXT["Extensão autorizada"]
        PAGE["Página web de destino"]
    end

    VAULT --> APPROVAL
    APPROVAL <-->|"Mensagens protegidas E2E"| SERVER
    SERVER <-->|"Mensagens protegidas E2E"| EXT
    EXT -->|"Credencial apenas após validação"| PAGE
```

A página web é uma fronteira externa. A extensão deve assumir que
páginas podem ser maliciosas e limitar rigorosamente onde e quando os
dados são inseridos.

## 7. Fluxos principais

### 7.1 Criação e desbloqueio do cofre

1.  O usuário define a senha mestra.
2.  O aplicativo gera salt e chave aleatória do cofre.
3.  A chave do cofre é protegida pela chave derivada da senha mestra.
4.  O aplicativo grava os metadados criptográficos.
5.  Nas próximas aberturas, o usuário informa a senha mestra ou utiliza
    o mecanismo de desbloqueio local configurado.
6.  O aplicativo verifica a capacidade de recuperar a chave do cofre
    antes de liberar o acesso.

### 7.2 Pareamento inicial

1.  A extensão solicita ao backend uma sessão temporária.
2.  O backend cria um identificador aleatório de uso único, com
    expiração curta.
3.  A extensão apresenta o QR Code.
4.  O Android lê o código e apresenta o dispositivo que está solicitando
    pareamento.
5.  O usuário confirma a operação no Android.
6.  Os dispositivos verificam as identidades e estabelecem o canal
    protegido.
7.  O backend marca a sessão como consumida.
8.  O Android permite revogar o dispositivo posteriormente.

O QR Code não contém credenciais, senha mestra ou chave privada. A
confirmação no Android é obrigatória.

### 7.3 Solicitação com confirmação

1.  A extensão identifica a origem da página.
2.  A extensão envia uma solicitação identificada ao backend.
3.  O backend encaminha a mensagem protegida ao Android.
4.  O Android valida o dispositivo e a sessão.
5.  O cofre procura uma credencial correspondente.
6.  O aplicativo apresenta o serviço e o domínio ao usuário.
7.  O usuário aprova ou recusa.
8.  Em caso de aprovação, a credencial é enviada pelo canal protegido.
9.  A extensão verifica novamente a origem e a sessão.
10. A extensão preenche os campos.

### 7.4 Solicitação automática

1.  A extensão identifica a origem da página.
2.  O Android recebe a solicitação.
3.  O aplicativo verifica se o dispositivo está confiável.
4.  O aplicativo verifica se o cofre está desbloqueado e se a sessão de
    confiança está válida.
5.  O aplicativo verifica a política específica da credencial e do
    domínio.
6.  Se todas as condições forem satisfeitas, autoriza o envio.
7.  A extensão valida a resposta e preenche os campos.

No MVP, o modo automático depende de o Android estar disponível e
conectado. Uma futura versão poderá estudar mecanismos de autorização
temporária, mas eles não devem ser adicionados sem análise de risco.

### 7.5 Revogação de dispositivo

1.  O usuário abre a lista de dispositivos confiáveis no Android.
2.  Seleciona o dispositivo.
3.  Confirma a revogação.
4.  O aplicativo invalida a confiança local e informa o backend.
5.  O backend encerra as sessões associadas.
6.  Solicitações futuras daquele dispositivo são recusadas.

A revogação deve funcionar mesmo que o dispositivo esteja
temporariamente desconectado: a política local precisa marcar o
dispositivo como revogado e propagar a alteração quando houver conexão.

## 8. Regras de domínio e preenchimento

A segurança do autofill depende de validação rigorosa de origem.

Regras:

-   Comparar o hostname normalizado, não apenas procurar uma substring
    no endereço.
-   Não considerar `site-legitimo.com.site-falso.com` equivalente a
    `site-legitimo.com`.
-   Usar HTTPS como requisito para preenchimento automático, salvo
    exceções de desenvolvimento explicitamente configuradas.
-   Não preencher campos em iframes de origens não autorizadas.
-   Não preencher páginas com origem opaca ou não verificável.
-   Não preencher automaticamente em páginas com sinais de
    inconsistência.
-   Solicitar confirmação adicional para credenciais sensíveis.
-   Evitar inserir a senha em campos ocultos ou inesperados.
-   Limitar o preenchimento aos campos de login identificados.

A associação de uma credencial a um domínio deve ser explícita.
Redirecionamentos legítimos, como provedores de autenticação, precisam
ser cadastrados e tratados de forma controlada, não por correspondência
ampla de domínios.

## 9. Segurança operacional e privacidade

### 9.1 Logs

Permitido registrar:

-   Identificador de evento.
-   Tipo de operação.
-   Resultado.
-   Horário.
-   Identificador pseudônimo do dispositivo.

Proibido registrar:

-   Senhas.
-   Senha mestra.
-   Chaves privadas.
-   Tokens de autorização em claro.
-   Conteúdo de mensagens criptografadas após descriptografia.
-   Conteúdo de campos de formulário.

### 9.2 Sessões e tokens

-   Tokens devem ser aleatórios, curtos e limitados em escopo.
-   Tokens de pareamento devem ser de uso único.
-   Sessões devem expirar.
-   A revogação deve invalidar sessões ativas.
-   Requisições devem ter proteção contra replay.
-   O modo automático deve ter tempo de confiança configurável e
    limitado.

### 9.3 Permissões da extensão

Solicitar somente permissões necessárias. Evitar permissões amplas de
leitura e alteração de dados em todos os sites quando houver alternativa
baseada em autorização por site ou interação do usuário.

A extensão deve separar o service worker, os content scripts e a
interface. O content script não deve receber acesso ao cofre inteiro nem
a chaves privadas.

### 9.4 Recuperação de acesso

A recuperação é uma decisão de produto e segurança que deve ser tratada
antes de uma versão de produção.

Para o MVP:

-   Explicar que a senha mestra não pode ser recuperada pelo servidor.
-   Orientar o usuário a configurar um método de recuperação seguro, se
    implementado.
-   Não permitir que um contato de confiança visualize automaticamente
    as credenciais.
-   Testar perda do dispositivo e perda da senha mestra.
-   Documentar claramente os limites de recuperação.

Não implementar uma chave mestra universal, senha de suporte ou
mecanismo secreto de acesso administrativo.

## 10. Disponibilidade e comportamento em falhas

| Situação                | Comportamento esperado                                                                           |
| ----------------------- | ------------------------------------------------------------------------------------------------ |
| Backend indisponível    | O cofre local continua funcionando; o preenchimento automático remoto fica indisponível.         |
| Android desconectado    | O modo de confirmação fica indisponível; o modo automático também permanece indisponível no MVP. |
| Cofre bloqueado         | Nenhuma credencial é liberada.                                                                   |
| Sessão expirada         | Uma nova autenticação ou autorização é necessária.                                               |
| Dispositivo revogado    | As solicitações são recusadas.                                                                   |
| QR Code expirado        | O pareamento é recusado.                                                                         |
| Domínio incompatível    | O preenchimento é bloqueado.                                                                     |
| Erro de descriptografia | A operação é interrompida; nenhum dado parcialmente recuperado é enviado.                        |
| Resposta duplicada      | A solicitação é processada uma única vez.                                                        |

A regra geral é falhar de forma fechada: quando não for possível
verificar identidade, origem, autorização ou integridade, não liberar a
credencial.

## 11. Estratégia de testes

### 11.1 Testes unitários

-   Derivação de chave.
-   Criptografia e descriptografia.
-   Validação de domínio.
-   Expiração de sessão.
-   Política de autorização.
-   Geração de senhas.
-   Revogação de dispositivos.

### 11.2 Testes de integração

-   Android com banco local.
-   Extensão com formulários de teste.
-   Pareamento Android--backend--extensão.
-   Comunicação protegida.
-   Aprovação e recusa de solicitações.
-   Modo automático com políticas.
-   Revogação durante uma sessão.

### 11.3 Testes negativos de segurança

| Cenário                         | Resultado esperado                                                    |
| ------------------------------- | --------------------------------------------------------------------- |
| Senha mestra incorreta          | O cofre permanece bloqueado.                                          |
| QR Code expirado                | O pareamento é recusado.                                              |
| QR Code reutilizado             | O pareamento é recusado.                                              |
| Dispositivo revogado            | A solicitação é recusada.                                             |
| Domínio semelhante ao original  | O preenchimento é bloqueado.                                          |
| Mensagem alterada               | A verificação de integridade falha e a operação é recusada.           |
| Mensagem repetida               | A tentativa de replay é recusada.                                     |
| Sessão automática expirada      | Uma nova autorização é necessária.                                    |
| Backend tentando ler o conteúdo | O conteúdo permanece protegido pela criptografia ponta a ponta (E2E). |
| Banco de dados local copiado    | As credenciais permanecem criptografadas.                             |

## 12. Implantação e ambientes

### Desenvolvimento local

-   Android Studio para o aplicativo.
-   Chrome em modo de desenvolvedor para a extensão.
-   FastAPI executado localmente.
-   SQLite para metadados.
-   HTTPS local ou túnel seguro para testar comunicação entre
    dispositivos físicos.

### Demonstração

-   Aplicativo instalado em um dispositivo Android de teste.
-   Extensão carregada no Chrome.
-   Backend executado em ambiente controlado.
-   Site de demonstração com formulários de login fictícios.
-   Credenciais exclusivamente fictícias.

Não utilizar contas bancárias reais nem senhas pessoais na apresentação.

### Evolução futura

Depois do MVP, avaliar:

-   PostgreSQL para operação com múltiplos usuários.
-   Hospedagem do relay em ambiente gerenciado.
-   Processo de atualização e assinatura da extensão.
-   Auditoria independente de segurança.
-   Recuperação segura de cofre.
-   Compartilhamento controlado de credenciais.
-   Suporte a outros navegadores.

## 13. Decisões arquiteturais resumidas

| Decisão                         | Escolha do MVP                                                   | Motivo                                                                                         |
| ------------------------------- | ---------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| Fonte principal das credenciais | Android local                                                    | Reduz a exposição dos dados e a dependência do servidor.                                       |
| Backend                         | Relay de mensagens                                               | Evita centralizar o armazenamento de senhas.                                                   |
| Aplicativo                      | Kotlin                                                           | Permite integração nativa com os recursos de segurança do Android.                             |
| Extensão                        | TypeScript + Manifest V3                                         | Garante compatibilidade com o Chrome.                                                          |
| Persistência móvel              | Room / SQLite                                                    | Simplifica a persistência e permite operação local.                                            |
| Banco de dados do backend       | SQLite inicialmente                                              | Reduz a complexidade do protótipo.                                                             |
| Pareamento                      | QR Code de uso único com confirmação                             | Facilita a conexão sem transportar credenciais ou outros segredos.                             |
| Autorização padrão              | Confirmação pelo celular                                         | Reduz o risco de preenchimentos indevidos.                                                     |
| Modo automático                 | Dispositivos confiáveis e políticas por credencial               | Equilibra segurança e usabilidade.                                                             |
| Criptografia do cofre           | Argon2id + chave aleatória + AES-256-GCM                         | Protege os dados armazenados em repouso.                                                       |
| Comunicação                     | TLS + protocolo de criptografia ponta a ponta (E2E) estabelecido | Protege a comunicação contra leitura e alteração por terceiros, inclusive pelo servidor relay. |

## 14. Limitações conhecidas do MVP

-   A extensão Chrome será destinada ao navegador de desktop; o Chrome
    padrão do Android não oferece suporte às extensões tradicionais de
    desktop.
-   O preenchimento automático dependerá de formulários compatíveis e
    poderá não funcionar em todos os sites.
-   O modo de confirmação exige que o aplicativo Android esteja
    disponível e conectado.
-   O modo automático também dependerá do Android no desenho inicial.
-   O backend poderá observar metadados de conexão, mesmo sem conseguir
    ler o conteúdo criptografado.
-   A recuperação de credenciais será limitada até que um mecanismo
    seguro seja projetado e testado.
-   O protótipo não deve ser considerado pronto para armazenar
    credenciais reais de produção sem revisão de segurança.

## 15. Critérios de aceite do MVP

O MVP será considerado demonstrável quando:

1.  O usuário criar e desbloquear um cofre Android.
2.  O usuário cadastrar e recuperar uma credencial armazenada
    criptografada.
3.  A extensão identificar um domínio de teste.
4.  O usuário parear o navegador com o celular por QR Code.
5.  O usuário aprovar uma solicitação no celular e concluir um
    preenchimento.
6.  O usuário habilitar o modo automático para um dispositivo e domínio
    de teste confiáveis.
7.  O sistema bloquear uma tentativa de preenchimento em domínio
    incompatível.
8.  O usuário revogar o dispositivo e impedir novas solicitações.
9.  Os testes demonstrarem que o backend não consegue ler as
    credenciais.
10. A apresentação utilizar somente contas e senhas fictícias.

------------------------------------------------------------------------

## Referências técnicas

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
-   Noise Protocol Framework ---
    [Specification](https://noiseprotocol.org/noise.html)

> **Nota de implementação:** este documento define uma arquitetura
> proposta para um protótipo acadêmico. Antes de armazenar credenciais
> reais ou disponibilizar o produto publicamente, é necessária revisão
> especializada do desenho criptográfico, dos fluxos de pareamento, do
> autofill e dos mecanismos de recuperação.
