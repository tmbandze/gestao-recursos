# Relatório — Sistema de Gestão de Recursos
## Trabalho Prático A — Sistemas Distribuídos

**Disciplina:** Sistemas Distribuídos  
**Ano lectivo:** 2025/2026  
**Trabalho:** Prático A — Gestão de Recursos  
**Stack:** Java 17 · Javalin 6 · Gson · PDFBox · Jakarta Mail · Maven  
**Repositório:** https://github.com/tmbandze/gestao-recursos

---

## 1. Introdução

O presente relatório descreve o desenvolvimento de um sistema distribuído de gestão e partilha de livros entre estudantes, implementado como trabalho prático da disciplina de Sistemas Distribuídos (Trabalho A — Gestão de Recursos).

O objectivo foi construir uma aplicação cliente-servidor que permita a múltiplos utilizadores gerir simultaneamente um catálogo de livros, com suporte a requisições, devoluções, filas de espera automáticas e notificações em tempo real. O sistema foi progressivamente alargado para cobrir um conjunto abrangente de conceitos do programa: comunicação assíncrona, propagação de eventos, concorrência, transparência de localização, sessões, persistência e comunicação entre processos.

### 1.1 Estratégia de Implementação

O grupo optou por uma arquitectura em evolução: o núcleo inicial usa `ServerSocket` TCP (porto 9090) com um protocolo de texto simples, depois foi migrado para um servidor HTTP com SSE (Javalin, porta 8080) e um cliente web (SPA em HTML/JS). Esta escolha permite demonstrar dois paradigmas de comunicação distintos — ambos presentes no sistema final.

---

## 2. Arquitectura do Sistema

### 2.1 Visão Geral

O sistema segue a arquitectura **cliente-servidor** com um servidor central único e múltiplos clientes web (browser). A comunicação usa dois canais:

- **HTTP REST** (porta 8080) — operações síncronas (login, requisição, devolução…)
- **SSE — Server-Sent Events** (porta 8080, endpoint `/api/sse`) — notificações assíncronas em tempo real
- **TCP** (porta 9090) — canal legado para o cliente JavaFX original

```
Browser (HTML + JS)  ──── HTTP REST ──────►
                     ◄──── SSE push ────────  SERVIDOR CENTRAL (Java 17)
                                              ├── GestorLivros
Cliente JavaFX       ──── TCP 9090 ─────────► ├── GestorUtilizadores
                                              ├── GestorHistorico
                                              ├── GestorChat
                                              ├── GestorEmail
                                              ├── GestorRecomendacoes
                                              ├── MonitorPrazos
                                              └── Logger
                                                    │
                                              data/ (JSON + ficheiros)
```

### 2.2 Componentes do Servidor

| Classe | Responsabilidade |
|--------|-----------------|
| `Servidor.java` | Javalin HTTP (8080); sessões; endpoints REST; SSE broadcast |
| `GestorLivros.java` | CRUD; exemplares múltiplos; requisição/devolução; filas de espera; multas; PDF; avaliações |
| `GestorUtilizadores.java` | Registo/login (SHA-256+salt); bloqueio; multas; recuperação de password |
| `GestorHistorico.java` | Registo de empréstimos; histórico pessoal; empréstimos activos |
| `GestorTCP.java` | ServerSocket TCP (9090); protocolo de texto; uma thread por cliente |
| `GestorChat.java` | Chat global e privado; persistência em JSON; máx. 500 mensagens |
| `GestorEmail.java` | SMTP assíncrono; templates HTML; config em JSON |
| `GestorRecomendacoes.java` | Score por livro (categoria + avaliação + collaborative + popularidade) |
| `MonitorPrazos.java` | `ScheduledExecutorService` de hora em hora; notificações SSE + email |
| `BaseDados.java` | Gson → `livros.json` |
| `BaseDadosUtilizadores.java` | Gson → `utilizadores.json` |
| `Logger.java` | Log de operações em `log.txt` com timestamp |
| `AnalisadorConteudo.java` | Análise de PDFs (palavras proibidas, tamanho, entropia) |

### 2.3 Modelos de Dados Partilhados (package `shared`)

| Classe | Campos relevantes |
|--------|------------------|
| `Livro` | id, titulo, autor, categoria, estado, totalExemplares, estudantesActuais, prazosEstudantes, filaEspera, avaliacoes, temPdf, pendente |
| `Utilizador` | id, nome, email, salt, passwordHash, bloqueado, avisos, multaTotal, multas |
| `Emprestimo` | id, idLivro, tituloLivro, estudante, dataInicio, prazo, dataFim |
| `Avaliacao` | utilizador, estrelas, comentario, data |
| `RegistoMulta` | tituloLivro, diasAtraso, valor, data |
| `MensagemChat` | id, de, para, texto, data |

---

## 3. Protocolo de Comunicação

### 3.1 HTTP REST + SSE (canal principal)

A comunicação primária usa HTTP/1.1 com JSON. Autenticação via header `X-Session-ID` (token UUID gerado no login). O servidor não mantém estado de sessão por HTTP — o mapeamento `sessionId → nome` é guardado num `ConcurrentHashMap` em memória.

```
POST /api/login
Body: {"email": "joao@mail.com", "password": "secret"}
Response: {"sessionId": "uuid", "nome": "João", "isAdmin": false}

POST /api/livros/{id}/requisitar
Headers: X-Session-ID: uuid
Response: {"ok": true, "mensagem": "Livro requisitado! Prazo: 05/06/2026"}
```

**SSE:** O browser estabelece uma ligação permanente ao endpoint `/api/sse`. O servidor envia eventos quando ocorrem mudanças:

| Evento SSE | Quando é emitido |
|-----------|------------------|
| `atualizacao` | Qualquer mudança no catálogo |
| `notificacao` | Alerta pessoal (multa, promoção na fila, livro aprovado…) |
| `utilizadores_update` | Login ou logout de qualquer utilizador |
| `multa_update` | Nova multa aplicada |
| `chat_mensagem` | Nova mensagem no chat global |
| `chat_priv` | Nova mensagem privada |
| `pendente_update` | Novo livro pendente de aprovação |

### 3.2 TCP (canal legado, porta 9090)

Protocolo de texto linha-a-linha: `COMANDO|campo1|campo2\n`

| Direcção | Comando | Exemplo |
|----------|---------|---------|
| C → S | `LOGIN\|nome` | `LOGIN\|João` |
| C → S | `LISTAR` | `LISTAR` |
| C → S | `REQUISITAR\|id` | `REQUISITAR\|uuid-1234` |
| C → S | `DEVOLVER\|id` | `DEVOLVER\|uuid-1234` |
| S → C | `OK\|msg` | `OK\|Livro requisitado` |
| S → C | `NOTIFICACAO\|texto` | `NOTIFICACAO\|O livro X está disponível!` |
| S → C | `ATUALIZAR` | broadcast a todos |

---

## 4. Conceitos de Sistemas Distribuídos Implementados

### 4.1 Arquitectura Cliente-Servidor

O servidor é o único ponto de autoridade sobre os dados. Os clientes (browser) não guardam estado local — toda a informação é obtida do servidor por pedido HTTP ou por notificação SSE push. Esta é a essência da arquitectura cliente-servidor: separação entre a lógica/dados (servidor) e a interface (cliente).

### 4.2 Comunicação via Sockets TCP

O `GestorTCP` usa `ServerSocket` / `Socket` Java para aceitar clientes JavaFX. Uma thread por cliente interpreta o protocolo de texto e delega para os gestores de negócio. O TCP garante entrega ordenada e sem perdas.

### 4.3 Concorrência com Thread Pool

Dois executores concorrentes estão presentes no sistema:

1. **`ExecutorService` (thread pool)** no `GestorTCP` — uma thread por cliente TCP
2. **`ScheduledExecutorService`** no `MonitorPrazos` — verifica prazos de hora em hora
3. **`ExecutorService` (single thread)** no `GestorEmail` — envio assíncrono de emails

O servidor Javalin usa internamente o Jetty com thread pool para HTTP.

### 4.4 Sincronização de Recursos Partilhados

Todos os gestores de estado são thread-safe com `synchronized` nos métodos que modificam dados:

```java
// GestorLivros.java — exemplo do método mais crítico
public synchronized Map<String, Object> devolver(String id, String nome) {
    Livro livro = buscar(id);
    // ... calcular multa, actualizar estado, promover fila ...
    baseDados.guardar(livros);
    servidor.notificarTodos("atualizacao", "devolvido", nome);
    return resp;
}
```

O `ConcurrentHashMap` é usado para sessões e clientes SSE (operações atómicas sem `synchronized` explícito).

### 4.5 Comunicação Assíncrona — SSE Push

O cliente mantém uma conexão SSE permanente (`EventSource`). O servidor notifica directamente os clientes relevantes sem que eles precisem de fazer polling:

```java
// Notificar utilizador específico
public void notificarUsuario(String nome, String dados) {
    SseClient c = sseClientes.get(nome);
    if (c != null) c.sendEvent("notificacao", dados);
}

// Broadcast para todos
public void notificarTodos(String evento, String dados, String excluir) {
    sseClientes.forEach((n, c) -> {
        if (!n.equals(excluir)) c.sendEvent(evento, dados);
    });
}
```

Este design implementa **comunicação baseada em eventos** (event-driven), eliminando o padrão ineficiente de polling.

### 4.6 Transparência de Localização

O cliente (browser) acede ao servidor por `IP:8080`. O utilizador não sabe onde os dados estão fisicamente armazenados. O servidor detecta e exibe o seu IP de rede no arranque:

```java
private String detectarIpLocal() {
    // Filtra interfaces VMware, VirtualBox, Bluetooth, loopback
    // Retorna o IP da primeira interface de rede real
}
```

Para usar a aplicação em rede local, basta que outros computadores acedam a `http://IP_DO_SERVIDOR:8080`.

### 4.7 Comunicação Assíncrona por Email

O `GestorEmail` usa um `ExecutorService` de thread única para enviar emails sem bloquear o thread HTTP. O envio é iniciado imediatamente mas executado em background:

```java
public void enviarAsync(String para, String assunto, String corpoHtml) {
    if (!isConfigurado()) return;
    executor.submit(() -> {
        try { enviarInterno(para, assunto, corpoHtml); }
        catch (Exception e) { /* log */ }
    });
}
```

### 4.8 Comunicação Baseada em Eventos (Event-Driven)

O `MonitorPrazos` é um componente autónomo que funciona como agendador de eventos:

```java
scheduler.scheduleAtFixedRate(this::verificar, 30, 3600, TimeUnit.SECONDS);
```

A cada hora verifica os empréstimos activos e emite eventos SSE + emails conforme o estado de cada prazo. Este é o padrão **publish/subscribe** aplicado ao domínio do problema.

### 4.9 Persistência de Estado

O sistema garante que o estado sobrevive a reinícios do servidor: todos os dados são imediatamente guardados em ficheiros JSON após cada modificação. Não existe cache em memória — a fonte de verdade é sempre o ficheiro.

### 4.10 Sessões sem Estado HTTP (Stateless Sessions)

O protocolo HTTP é stateless por natureza. O sistema implementa sessões com tokens UUID: cada login gera um novo UUID guardado no `ConcurrentHashMap` do servidor. O cliente envia o token no header `X-Session-ID` em cada pedido. Esta é a base dos sistemas de autenticação modernos.

---

## 5. Funcionalidades Implementadas

### 5.1 Funcionalidades Base (Enunciado Mínimo)

| Funcionalidade | Implementação |
|----------------|--------------|
| Inserção de livros | POST `/api/livros` com título, autor, categoria |
| Consulta de livros | GET `/api/livros`, pesquisa, filtros por estado |
| Requisição de livro | POST `/api/livros/{id}/requisitar` |
| Devolução de livro | POST `/api/livros/{id}/devolver` |
| Múltiplos clientes | HTTP + SSE (N browsers) + TCP (JavaFX legado) |

### 5.2 Funcionalidades Estendidas

| Funcionalidade | Descrição |
|----------------|-----------|
| **Autenticação** | Registo/login por email+password; hash SHA-256 com salt de 16 bytes |
| **Recuperação de password** | Token de 8 chars alfanuméricos, TTL 2h; entregue por email ou consola |
| **Múltiplos exemplares** | Um livro pode ter N cópias físicas; controlo independente por exemplar |
| **Prazo de devolução** | 7 dias; prazo individual por estudante/cópia |
| **Fila de espera FIFO** | Promoção automática quando uma cópia fica disponível |
| **Multas por atraso** | 0,50 €/dia; bloqueio de requisições até pagamento; perdão admin |
| **Upload de PDF** | Máximo 50 MB; capa extraída da 1ª página (PDFBox); análise de conteúdo |
| **Workflow de aprovação** | Livros com PDF ficam pendentes até aprovação do admin |
| **Moderação de conteúdo** | PDFs analisados (palavras proibidas, entropia, tamanho anómalo) |
| **Avaliações** | 1–5 estrelas + comentário; uma avaliação por utilizador por livro |
| **Notificações SSE** | Broadcast e push individual em tempo real (multas, filas, aprovações…) |
| **Monitor de prazos** | Verificação automática de hora em hora; alertas de 1 dia / hoje / atraso |
| **Chat em tempo real** | Sala global + mensagens privadas; histórico persistido; SSE |
| **Exportação CSV** | 4 relatórios: empréstimos, multas, utilizadores, livros; BOM UTF-8 |
| **Notificações por email** | SMTP async; 6 templates HTML (boas-vindas, prazo, multa, recuperação…) |
| **Recomendações** | Motor com 4 sinais: categoria favorita, avaliação, collaborative, popularidade |
| **Painel admin** | Utilizadores em tempo real; moderação; aprovações; CSV; email config |
| **Log de operações** | Todas as operações com timestamp em `log.txt` |

---

## 6. Decisões de Design

### 6.1 Dois Canais de Comunicação

Manter o canal TCP legado ao lado do HTTP/SSE demonstra que o sistema suporta múltiplos protocolos e tipos de cliente. O TCP é adequado para o cliente JavaFX (conexão persistente, baixa latência); o HTTP/SSE é adequado para browsers (stateless, firewall-friendly).

### 6.2 SSE vs. WebSockets

Optou-se por SSE em vez de WebSockets porque:
- SSE é unidireccional (servidor → cliente), o que cobre todos os casos de uso (notificações)
- SSE é mais simples de implementar e depurar
- Reconexão automática nativa no browser
- As operações do cliente para o servidor já usam HTTP POST — não há necessidade de canal bidirecional

### 6.3 JSON vs. Base de Dados Relacional

O uso de Gson com ficheiros JSON justifica-se pelo âmbito académico: não requer instalação adicional, os ficheiros são legíveis e editáveis para demonstração, e o desempenho é suficiente para o volume de dados esperado.

### 6.4 Fat JAR (maven-shade-plugin)

O servidor é empacotado como um JAR único com todas as dependências. Isto simplifica o deploy: `java -jar servidor.jar` em qualquer máquina com Java 17, sem necessidade de classpath manual.

### 6.5 Hashing de Passwords

As passwords nunca são guardadas em texto claro. Cada utilizador tem um `salt` de 16 bytes aleatórios; a password é guardada como `SHA-256(salt + password)`. Isto evita ataques de dicionário e rainbow tables.

### 6.6 Sessões em Memória

As sessões (`sessionId → nome`) estão num `ConcurrentHashMap` em memória. Se o servidor reiniciar, as sessões são perdidas e os utilizadores precisam de fazer login novamente — comportamento esperado e correcto. Os dados de utilizadores e livros são persistidos em JSON e sobrevivem ao reinício.

---

## 7. Fluxos Principais

### 7.1 Requisição com Fila de Espera

```
1. Utilizador A → POST /api/livros/X/requisitar
   Servidor: livro disponível → regista empréstimo, define prazo (hoje + 7d)
   Servidor → SSE broadcast: "atualizacao"
   Resposta: {"ok": true, "mensagem": "Prazo: 05/06/2026"}

2. Utilizador B → POST /api/livros/X/requisitar (livro já requisitado)
   Servidor: sem cópias → adiciona B à fila de espera
   Resposta: {"ok": true, "mensagem": "Adicionado à fila (posição 1)"}

3. Utilizador A → POST /api/livros/X/devolver
   Servidor: regista devolução, verifica fila → próximo é B
   Servidor: requisita automaticamente para B (prazo = hoje + 7d)
   Servidor → SSE para B: "notificacao" → "📚 A tua cópia está disponível!"
   Servidor → SSE broadcast: "atualizacao"
```

### 7.2 Multa por Atraso

```
MonitorPrazos (de hora em hora):
  prazo = 2026-05-25, hoje = 2026-05-29 → atraso = 4 dias
  multa estimada = 4 × 0,50€ = 2,00€
  → SSE para utilizador: "⚠ ATRASO: O livro X deveria ter sido devolvido há 4 dias!"
  → Email para utilizador: template htmlAtraso() via SMTP async

Utilizador → POST /api/livros/X/devolver (com 4 dias de atraso):
  multa = 4 × 0,50€ = 2,00€ → adicionada ao utilizador
  → SSE para utilizador: "💸 Multa aplicada: 2,00€"
  → Email para utilizador: template htmlMultaAplicada()
  → SSE broadcast: "multa_update"
  Utilizador fica bloqueado de novas requisições até perdão admin
```

### 7.3 Aprovação de Livro com PDF

```
1. Utilizador → POST /api/livros (multipart: titulo + pdf)
   AnalisadorConteudo analisa o PDF:
     - se suspeito: livro.flagAdmin = true
     - de qualquer forma: livro.pendente = true
   → SSE para admin: "pendente_update: novo_pendente"
   → TCP para admin: "⏳ Novo livro pendente de aprovação..."

2. Admin → POST /api/admin/aprovar/{id}
   livro.pendente = false
   → SSE broadcast: "atualizacao: novo_livro"
   → SSE para uploader: "✅ O teu livro foi aprovado!"

   OU Admin → POST /api/admin/rejeitar/{id}
   Livro apagado (JSON + PDF + capa)
   → SSE para uploader: "❌ O teu livro foi rejeitado"
```

---

## 8. Estrutura de Ficheiros

```
gestao-recursos/
│
├── servidor/                              # Módulo Maven
│   ├── pom.xml                            # Dependências + shade plugin
│   ├── target/servidor.jar                # Fat JAR executável
│   └── src/main/
│       ├── java/
│       │   ├── servidor/
│       │   │   ├── Servidor.java          # Entry point + Javalin + endpoints
│       │   │   ├── GestorLivros.java      # Lógica de livros (synchronized)
│       │   │   ├── GestorUtilizadores.java# Autenticação + multas
│       │   │   ├── GestorHistorico.java   # Histórico de empréstimos
│       │   │   ├── GestorTCP.java         # ServerSocket TCP (9090)
│       │   │   ├── GestorChat.java        # Chat SSE em tempo real
│       │   │   ├── GestorEmail.java       # SMTP assíncrono (Jakarta Mail)
│       │   │   ├── GestorRecomendacoes.java# Motor de recomendações
│       │   │   ├── MonitorPrazos.java     # Verificação agendada de prazos
│       │   │   ├── AnalisadorConteudo.java# Análise de PDFs
│       │   │   ├── BaseDados.java         # Persistência livros.json
│       │   │   ├── BaseDadosUtilizadores.java # Persistência utilizadores.json
│       │   │   └── Logger.java            # Log com timestamp
│       │   └── shared/
│       │       ├── Livro.java             # Modelo de livro
│       │       ├── Utilizador.java        # Modelo de utilizador
│       │       ├── Emprestimo.java        # Modelo de empréstimo
│       │       ├── Avaliacao.java         # Modelo de avaliação
│       │       ├── RegistoMulta.java      # Modelo de multa
│       │       ├── MensagemChat.java      # Modelo de mensagem de chat
│       │       ├── EstadoLivro.java       # Enum: DISPONIVEL / REQUISITADO
│       │       └── Protocolo.java         # Constantes do protocolo TCP
│       └── resources/
│           └── public/
│               ├── index.html             # SPA — página principal
│               └── app.js                 # Toda a lógica do cliente (~1400 linhas)
│
├── cliente/                               # Módulo Maven — cliente JavaFX (legado)
│   └── src/main/java/cliente/
│       ├── MainApp.java
│       ├── Cliente.java                   # Conexão TCP + BlockingQueue
│       └── NotificacaoService.java        # Leitor único do socket
│
├── docs/
│   ├── RELATORIO.md                       # Este documento
│   ├── ARQUITETURA.md                     # Arquitectura detalhada
│   ├── INSTALACAO.md                      # Como instalar e executar
│   ├── MANUAL.md                          # Manual do utilizador
│   ├── CONTEXT.md                         # Contexto académico
│   ├── APRESENTACAO.md                    # Guia para apresentação
│   └── INDEX.md                           # Índice geral
│
└── README.md                              # Visão geral do projecto
```

---

## 9. Limitações Conhecidas

| Limitação | Justificação / Mitigação |
|-----------|--------------------------|
| Sessões em memória | Reinício do servidor invalida sessões ativas — comportamento esperado |
| Servidor como ponto único de falha | Âmbito académico; replicação exigiria consenso distribuído (Raft/Paxos) |
| Sem TLS/HTTPS | Rede local académica; pode ser adicionado via proxy reverso (Nginx) |
| Persistência em JSON | Adequada para o volume académico; em produção usar PostgreSQL/MongoDB |
| Análise de conteúdo simplificada | `AnalisadorConteudo` usa heurísticas; não substitui antivírus real |
| Filas de espera em memória (índice) | O estado das filas é reconstruído ao carregar `livros.json` |

---

## 10. Conclusão

O sistema implementado supera os requisitos mínimos do enunciado e demonstra, de forma explícita e verificável, os principais conceitos do programa de Sistemas Distribuídos:

- **Sockets TCP**: canal GestorTCP (porta 9090) com protocolo de texto
- **HTTP REST**: API Javalin com 30+ endpoints
- **Comunicação assíncrona**: SSE push para notificações em tempo real
- **Concorrência**: `synchronized`, `ConcurrentHashMap`, `ExecutorService`, `ScheduledExecutorService`
- **Propagação de eventos**: broadcast SSE a todos os clientes ligados
- **Sessões**: tokens UUID com `ConcurrentHashMap`
- **Transparência de localização**: acesso por IP:porta com auto-detecção
- **Persistência**: estado distribuído em ficheiros JSON (sobrevive a reinícios)
- **Comunicação baseada em eventos**: `MonitorPrazos` como publisher agendado

A evolução arquitectónica do sistema — de TCP/JavaFX para HTTP/SSE/Browser — demonstra também a capacidade de adaptar uma arquitectura distribuída a novos requisitos sem perder compatibilidade com clientes existentes.

---

## Anexo A — Dependências

| Biblioteca | Versão | Uso |
|------------|--------|-----|
| Java SE | 17 | Linguagem, threads, sockets, criptografia |
| Javalin | 6.1.6 | Servidor HTTP + SSE + routing + multipart |
| Gson | 2.10.1 | Serialização JSON (persistência) |
| Jackson Databind | 2.17.0 | Serialização JSON (respostas HTTP) |
| SLF4J Simple | 2.0.13 | Logging interno Jetty/Javalin |
| Apache PDFBox | 3.0.3 | Extracção de capa (1ª página PDF → JPEG) |
| Jakarta Mail (Angus) | 2.0.3 | Envio de emails via SMTP |
| Apache Maven | 3.8+ | Build + dependências + fat JAR |

## Anexo B — Endpoints Completos

### Públicos (sem autenticação)
- `GET /` — Aplicação web (index.html)
- `GET /app.js` — JavaScript do cliente
- `POST /api/login` — Login
- `POST /api/registar` — Registo
- `POST /api/recuperar-password` — Pedir recuperação
- `POST /api/reset-password` — Redefinir password
- `GET /api/livros` — Catálogo
- `GET /api/livros/pesquisa?q=` — Pesquisa
- `GET /api/livros/{id}` — Detalhes
- `GET /api/livros/{id}/capa` — Capa JPEG

### Autenticados (header `X-Session-ID`)
- `POST /api/logout`
- `GET /api/sse` — Conexão SSE
- `POST /api/livros` — Adicionar livro
- `POST /api/livros/{id}/requisitar`
- `POST /api/livros/{id}/devolver`
- `POST /api/livros/{id}/avaliar`
- `DELETE /api/livros/{id}/avaliacao`
- `GET /api/livros/{id}/pdf`
- `GET /api/historico`
- `GET /api/multas`
- `GET /api/relatorio`
- `GET /api/chat/mensagens?para=`
- `POST /api/chat/enviar`
- `GET /api/recomendacoes?max=`

### Admin (header `X-Session-ID`, nome = "admin")
- `GET /api/admin/utilizadores`
- `POST /api/admin/bloquear`
- `POST /api/admin/desbloquear`
- `POST /api/admin/avisar`
- `POST /api/admin/perdoar-multa`
- `GET /api/admin/multas`
- `GET /api/admin/log`
- `GET /api/admin/pendentes`
- `POST /api/admin/aprovar/{id}`
- `POST /api/admin/rejeitar/{id}`
- `POST /api/admin/exemplar/{idPendente}/{idExistente}`
- `GET /api/admin/suspeitos`
- `GET /api/admin/recuperacoes`
- `GET /api/admin/relatorio/{tipo}.csv?sid=`
- `GET /api/chat/interlocutores`
- `GET /api/admin/email/config`
- `POST /api/admin/email/config`
- `POST /api/admin/email/testar`
- `DELETE /api/livros/{id}`
