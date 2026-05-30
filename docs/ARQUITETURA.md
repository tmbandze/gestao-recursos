# ARQUITETURA.md — Arquitectura do Sistema

## 1. Visão Geral

O sistema segue a arquitectura **cliente-servidor** com servidor HTTP centralizado e clientes web (browser). A camada de transporte usa **HTTP REST** para operações síncronas e **SSE (Server-Sent Events)** para notificações assíncronas em tempo real. Existe também um canal **TCP** legado (porta 9090) para o cliente JavaFX original.

```
┌─────────────────────────────────────────────────────┐
│              BROWSER  (cliente web)                 │
│         HTML5 · CSS3 · JavaScript (SPA)             │
│                                                     │
│  fetch() ──── HTTP REST ────────────────────────►  │
│  EventSource ── SSE ────────────────────────────►  │
└────────────────────────────────────────────────────►┤
                                                      │
              porta 8080                              │
┌─────────────────────────────────────────────────────┤
│                  SERVIDOR CENTRAL                   │
│                  Servidor.java                      │
│              (Javalin 6 + Jetty)                    │
│                                                     │
│  ┌──────────────┐  ┌──────────────┐                │
│  │ GestorLivros │  │GestorUtiliz. │                │
│  └──────┬───────┘  └──────┬───────┘                │
│         │                 │                         │
│  ┌──────▼────────────────▼──────────────────────┐  │
│  │              GestorHistorico                 │  │
│  │  GestorChat  GestorEmail  GestorRecom.       │  │
│  │  MonitorPrazos  Logger                       │  │
│  └──────────────────────────┬────────────────────┘  │
│                             │                       │
│  ┌──────────────────────────▼────────────────────┐  │
│  │    Camada de Persistência (JSON + ficheiros)  │  │
│  │  livros.json · utilizadores.json              │  │
│  │  emprestimos.json · chat.json                 │  │
│  │  data/pdfs/ · data/capas/ · log.txt           │  │
│  └───────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘
              porta 9090
         ┌────────────┐
         │ GestorTCP  │◄── Cliente JavaFX (legado)
         └────────────┘
```

---

## 2. Componentes do Servidor

### 2.1 Servidor.java — Ponto de Entrada

Responsável por:
- Instanciar todos os gestores e injectar dependências
- Configurar o servidor Javalin (HTTP + SSE, porta 8080)
- Registar todos os endpoints REST
- Verificar JWT em cada pedido autenticado (`autenticar`, `autenticarDownload`)
- Manter blacklist de `jti` para logout/refresh imediatos (`jwtBloqueados`)
- Gerir o mapa de clientes SSE (`nome → SseClient`)
- Emitir eventos SSE para um utilizador (`notificarUsuario`) ou todos (`notificarTodos`)
- Detectar o IP de rede local no arranque

```java
// Autenticação JWT
JwtUtil             jwtUtil       = new JwtUtil();
Set<String>         jwtBloqueados = ConcurrentHashMap.newKeySet(); // jti invalidados
Map<String, SseClient> sseClientes = new ConcurrentHashMap<>();    // nome → SSE
```

### 2.2 GestorLivros.java — Lógica de Livros

Todos os métodos que modificam estado são `synchronized`. Responsável por:
- CRUD de livros (`inserir`, `apagar`, `detalhes`, `listarTodos`)
- Controlo de múltiplos exemplares (`totalExemplares`, `estudantesActuais`)
- Requisição com prazo de 7 dias e cálculo de multa na devolução
- Fila de espera FIFO: promoção automática do próximo ao devolver
- Upload e persistência de PDFs (`data/pdfs/`)
- Extracção assíncrona de capas do PDF (`data/capas/`)
- Análise de conteúdo via `AnalisadorConteudo`
- Workflow de aprovação (pendente → aprovado/rejeitado)
- Avaliações e comentários (`avaliar`, `listarAvaliacoes`)

### 2.3 GestorUtilizadores.java — Gestão de Utilizadores

Responsável por:
- Registo com hash SHA-256 + salt aleatório (16 bytes)
- Login por email + password
- Recuperação de password (token de 8 caracteres, TTL 2h)
- Bloqueio/desbloqueio e sistema de avisos
- Multas: `adicionarMulta`, `perdoarMulta`, `listarMultas`
- Métodos de consulta: `getEmailPorNome`, `getNomePorEmail`, `getTokenRecuperacao`

### 2.4 GestorHistorico.java — Histórico de Empréstimos

Persiste todos os empréstimos em `emprestimos.json`. Métodos:
- `registarInicio(idLivro, titulo, estudante, dataInicio, prazo)`
- `registarFim(idLivro, estudante, dataFim)`
- `porEstudante(nome)` — histórico pessoal ordenado por data
- `emprestimosActivos()` — empréstimos sem `dataFim` (usado pelo MonitorPrazos)
- `listarTodos()` — todos os registos (para CSV)

### 2.5 GestorChat.java — Chat em Tempo Real

Persiste mensagens em `chat.json` (máx. 500). Métodos:
- `enviar(de, para, texto)` — global (`para=null`) ou privada
- `listarGlobal(n)` — últimas n mensagens globais
- `listarPrivada(u1, u2, n)` — conversa entre dois utilizadores
- `interlocutoresDoAdmin()` — lista de utilizadores com conversas com o admin

### 2.6 GestorEmail.java — Notificações por Email

Envia emails via SMTP de forma assíncrona (thread dedicada). Config em `email-config.json`. Templates HTML para:
- Boas-vindas no registo
- Lembrete 1 dia antes do prazo
- Urgente no próprio dia do prazo
- Atraso com multa estimada
- Multa aplicada na devolução
- Token de recuperação de password

### 2.7 GestorRecomendacoes.java — Motor de Recomendações

Combina 4 sinais para calcular um score por livro candidato:

| Sinal | Peso máx | Fonte de dados |
|-------|----------|----------------|
| Categoria favorita | 5.0 | Histórico do utilizador |
| Avaliação média | 4.0 | `Livro.mediaEstrelas()` |
| Collaborative filtering | 4.0 | Co-leitores com histórico comum |
| Popularidade global | 2.0 | Total de empréstimos por livro |

### 2.8 MonitorPrazos.java — Monitor Automático

`ScheduledExecutorService` que verifica prazos **de 30 em 30 segundos no arranque e depois de hora em hora**:
- 1 dia antes: lembrete (SSE + email)
- No dia do prazo: urgente (SSE + email)
- Após o prazo: alerta com multa estimada (SSE + email, repetido a cada hora)

### 2.9 GestorTCP.java — Canal TCP (porta 9090)

Canal legado para o cliente JavaFX. Gere uma thread por cliente, interpreta o protocolo de texto (`LISTAR`, `REQUISITAR|id`, etc.) e delega para `GestorLivros` e `GestorUtilizadores`.

---

## 3. API REST

### Autenticação

| Método | Endpoint | Descrição |
|--------|----------|-----------|
| POST | `/api/registar` | Criar conta → devolve JWT |
| POST | `/api/login` | Iniciar sessão → devolve JWT |
| POST | `/api/logout` | Terminar sessão (invalida `jti` na blacklist) |
| POST | `/api/auth/refresh` | Renovar JWT (invalida antigo, emite novo) |
| POST | `/api/recuperar-password` | Pedir token de recuperação |
| POST | `/api/reset-password` | Redefinir password com token |

### Livros

| Método | Endpoint | Descrição |
|--------|----------|-----------|
| GET | `/api/livros` | Catálogo público (sem pendentes) |
| GET | `/api/livros/pesquisa?q=` | Pesquisa por título/autor/categoria |
| GET | `/api/livros/{id}` | Detalhes + avaliações |
| POST | `/api/livros` | Adicionar livro (multipart: titulo, autor, categoria, pdf) |
| DELETE | `/api/livros/{id}` | Apagar livro (admin) |
| POST | `/api/livros/{id}/requisitar` | Requisitar / entrar na fila |
| POST | `/api/livros/{id}/devolver` | Devolver |
| POST | `/api/livros/{id}/avaliar` | Avaliar (1-5 estrelas) |
| GET | `/api/livros/{id}/pdf` | Download do PDF |
| GET | `/api/livros/{id}/capa` | Imagem de capa (JPEG) |

### Admin

| Método | Endpoint | Descrição |
|--------|----------|-----------|
| GET | `/api/admin/utilizadores` | Lista completa com estados |
| POST | `/api/admin/bloquear` | Bloquear utilizador |
| POST | `/api/admin/desbloquear` | Desbloquear utilizador |
| POST | `/api/admin/avisar` | Adicionar aviso |
| POST | `/api/admin/perdoar-multa` | Perdoar multa |
| GET | `/api/admin/pendentes` | Livros aguardando aprovação |
| POST | `/api/admin/aprovar/{id}` | Aprovar livro |
| POST | `/api/admin/rejeitar/{id}` | Rejeitar livro |
| GET | `/api/admin/relatorio/{tipo}.csv` | Exportar CSV (emprestimos/multas/utilizadores/livros) |
| GET | `/api/admin/email/config` | Ler config SMTP |
| POST | `/api/admin/email/config` | Guardar config SMTP |
| POST | `/api/admin/email/testar` | Enviar email de teste |

### Chat e Recomendações

| Método | Endpoint | Descrição |
|--------|----------|-----------|
| GET | `/api/chat/mensagens?para=` | Mensagens globais ou privadas |
| POST | `/api/chat/enviar` | Enviar mensagem |
| GET | `/api/recomendacoes?max=` | Recomendações personalizadas |

### SSE

A ligação SSE é autenticada via query param `?token=<jwt>` (o browser não pode enviar headers em `EventSource`).

| Endpoint | Evento | Payload |
|----------|--------|---------|
| `GET /api/eventos?token=` | `conectado` | Nome do utilizador |
| | `atualizacao` | Tipo de operação |
| | `utilizadores_update` | `login` ou `logout` |
| | `notificacao` | Texto livre |
| | `multa_update` | `nova_multa:nome:valor` |
| | `chat_mensagem` | JSON `MensagemChat` (global) |
| | `chat_priv` | JSON `MensagemChat` (privada) |
| | `pendente_update` | Estado do livro pendente |
| | `recuperacao_update` | Email do utilizador |

---

## 4. Modelo de Dados

### livros.json

```json
{
  "id": "uuid",
  "titulo": "Sistemas Distribuídos",
  "autor": "Tanenbaum",
  "categoria": "Redes",
  "estado": "REQUISITADO",
  "totalExemplares": 2,
  "estudantesActuais": ["João"],
  "prazosEstudantes": {"João": "2026-06-05"},
  "filaEspera": ["Maria"],
  "temPdf": true,
  "pendente": false,
  "flagAdmin": false,
  "avaliacoes": [{"utilizador":"João","estrelas":5,"comentario":"Excelente!"}]
}
```

### utilizadores.json

```json
{
  "id": "uuid",
  "nome": "João",
  "email": "joao@email.com",
  "salt": "hex-16-bytes",
  "passwordHash": "sha256(salt+password)",
  "bloqueado": false,
  "avisos": 0,
  "multaTotal": 1.50,
  "multas": [{"tituloLivro":"X","diasAtraso":3,"valor":1.50,"data":"2026-05-20"}]
}
```

### emprestimos.json

```json
{
  "id": "uuid",
  "idLivro": "uuid",
  "tituloLivro": "Sistemas Distribuídos",
  "estudante": "João",
  "dataInicio": "2026-05-29",
  "prazo": "2026-06-05",
  "dataFim": null
}
```

---

## 5. Autenticação JWT

O sistema usa **JSON Web Tokens (JWT)** com algoritmo **HMAC-SHA256** para autenticar todos os pedidos. A implementação está em `JwtUtil.java`.

### Fluxo

```
POST /api/login  →  { "token": "eyJhbGciOiJIUzI1NiJ9...", "nome": "João", "isAdmin": false }

POST /api/livros/{id}/requisitar
     Headers: Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

### Estrutura do JWT

```json
Header:  { "alg": "HS256" }
Payload: {
  "jti":   "uuid-único-por-token",   // usado para blacklist no logout
  "sub":   "João",                   // nome do utilizador
  "email": "joao@mail.com",
  "admin": false,
  "iat":   1748617200,               // emitido em (Unix timestamp)
  "exp":   1748703600                // expira em 24 horas
}
```

### Chave Secreta

Gerada automaticamente na primeira execução com `Jwts.SIG.HS256.key().build()` e persistida em `data/jwt-secret.key` (Base64). Sobrevive a reinícios do servidor — todos os tokens emitidos anteriormente continuam válidos após reinício.

### Blacklist de `jti`

O `jti` (JWT ID) é um UUID único por token. O servidor mantém um `Set<String> jwtBloqueados` em memória. Em logout ou refresh, o `jti` do token actual é adicionado à blacklist — mesmo que o token ainda não tenha expirado, é imediatamente inválido.

### Renovação Automática

O cliente (`app.js`) usa `parseJwt()` para descodificar o payload e agenda um `setTimeout` para chamar `POST /api/auth/refresh` 30 minutos antes da expiração. O token antigo é invalidado e um novo emitido.

### Downloads CSV

O browser não pode enviar headers custom em downloads directos (`<a href>`). Para estes casos, o JWT é aceite como query param: `?token=<jwt>`.

### `JwtUtil.java` — Métodos Principais

```java
String  gerarToken(String nome, String email, boolean isAdmin)  // JWT HMAC-SHA256, 24h
Claims  verificar(String token)   // Claims ou null se inválido/expirado
long    msAteExpirar(Claims c)    // milissegundos até expiração (para agendar refresh)
```

---

## 6. Concorrência

Todos os gestores usam `synchronized` nos métodos que modificam estado. O `GestorLivros` é o recurso mais crítico pois pode ter múltiplas threads a executar em simultâneo:

| Método | Motivo do `synchronized` |
|--------|--------------------------|
| `requisitar()` | Evita dois clientes requisitarem a última cópia |
| `devolver()` | Modifica lista de detentores + calcula multa + promove fila |
| `inserir()` | Adiciona à lista partilhada |
| `apagar()` | Remove da lista partilhada |
| `avaliar()` | Modifica lista de avaliações |

---

## 7. SSE — Notificações em Tempo Real

O browser mantém uma conexão SSE permanente (`/api/sse`). O servidor guarda o `SseClient` em `sseClientes` associado ao nome do utilizador. Quando ocorre um evento, o servidor notifica directamente o(s) cliente(s) relevante(s):

```java
// Notificar um utilizador específico
public void notificarUsuario(String nome, String dados) {
    SseClient c = sseClientes.get(nome);
    if (c != null) c.sendEvent("notificacao", dados);
}

// Broadcast para todos os conectados
public void notificarTodos(String evento, String dados, String excluir) {
    sseClientes.forEach((nome, c) -> {
        if (!nome.equals(excluir)) c.sendEvent(evento, dados);
    });
}
```

---

## 8. Persistência

Cada gestor tem a sua própria classe `BaseDados*` ou guarda directamente em JSON via Gson. A gravação é sempre síncrona e ocorre imediatamente após cada modificação (sem cache em memória).

| Ficheiro | Gestor responsável |
|----------|--------------------|
| `data/livros.json` | `BaseDados` + `GestorLivros` |
| `data/utilizadores.json` | `BaseDadosUtilizadores` + `GestorUtilizadores` |
| `data/emprestimos.json` | `GestorHistorico` |
| `data/chat.json` | `GestorChat` |
| `data/email-config.json` | `GestorEmail` |
| `data/jwt-secret.key` | `JwtUtil` (chave HMAC-SHA256 em Base64) |
| `data/log.txt` | `Logger` |
| `data/pdfs/{id}.pdf` | `GestorLivros` |
| `data/capas/{id}.jpg` | `GestorLivros` (extracção assíncrona) |

---

## 9. Dependências

| Biblioteca | Versão | Uso |
|------------|--------|-----|
| Java SE | 17 | Linguagem, threads, crypto, sockets |
| Javalin | 6.1.6 | Servidor HTTP + SSE + routing |
| Gson | 2.10.1 | Serialização JSON (persistência) |
| Jackson Databind | 2.17.0 | Serialização JSON (respostas HTTP) |
| SLF4J Simple | 2.0.13 | Logging interno do Jetty/Javalin |
| Apache PDFBox | 3.0.3 | Extracção de capa (1ª página do PDF) |
| Jakarta Mail (Angus) | 2.0.3 | Envio de emails via SMTP |
| JJWT (io.jsonwebtoken) | 0.12.6 | Geração e verificação de JWT (HMAC-SHA256) |
| Apache Maven | 3.8+ | Build + gestão de dependências |

O servidor é empacotado como um **fat JAR** via `maven-shade-plugin`, incluindo todas as dependências.
