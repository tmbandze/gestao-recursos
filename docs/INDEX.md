# Sistema de Gestão de Recursos — Índice Geral

**Projecto:** Partilha de Livros entre Estudantes — Biblioteca Digital  
**Disciplina:** Sistemas Distribuídos  
**Stack:** Java 17 · Javalin 6 · Gson · PDFBox · Jakarta Mail · JJWT · Maven  
**Versão:** 2.0 | Maio 2026

---

## Navegação Rápida

| Documento | Conteúdo | Para quem |
|-----------|---------|-----------|
| [RELATORIO.md](RELATORIO.md) | Relatório académico completo (arquitectura, conceitos SD, fluxos, decisões) | Entrega académica |
| [ARQUITETURA.md](ARQUITETURA.md) | Design técnico detalhado, API REST, modelos de dados, SSE | Desenvolvedores |
| [CONTEXT.md](CONTEXT.md) | Contexto académico, requisitos mínimos e estendidos | Todos |
| [INSTALACAO.md](INSTALACAO.md) | Como compilar, executar, configurar e resolver problemas | Utilizadores/Docente |
| [MANUAL.md](MANUAL.md) | Como usar a aplicação passo a passo | Utilizadores |
| [APRESENTACAO.md](APRESENTACAO.md) | Guia para a apresentação à docente | Grupo |

---

## Estrutura do Projecto

```
gestao-recursos/
│
├── servidor/                              # Módulo Maven — servidor web
│   ├── pom.xml
│   ├── target/servidor.jar                # Fat JAR executável
│   └── src/main/
│       ├── java/
│       │   ├── servidor/
│       │   │   ├── Servidor.java          # Entry point + Javalin HTTP + SSE
│       │   │   ├── GestorLivros.java      # Livros: CRUD, exemplares, PDF, avaliações
│       │   │   ├── GestorUtilizadores.java# Autenticação SHA-256+salt, multas
│       │   │   ├── GestorHistorico.java   # Histórico de empréstimos (JSON)
│       │   │   ├── GestorTCP.java         # ServerSocket TCP porta 9090 (legado)
│       │   │   ├── GestorChat.java        # Chat global+privado via SSE
│       │   │   ├── GestorEmail.java       # SMTP assíncrono + templates HTML
│       │   │   ├── GestorRecomendacoes.java# Motor de recomendações (4 sinais)
│       │   │   ├── MonitorPrazos.java     # Verificação de prazos agendada
│       │   │   ├── AnalisadorConteudo.java# Análise de PDFs enviados
│       │   │   ├── BaseDados.java         # Persistência livros.json (Gson)
│       │   │   ├── BaseDadosUtilizadores.java
│       │   │   └── Logger.java            # Log de operações com timestamp
│       │   └── shared/
│       │       ├── Livro.java             # Modelo com múltiplos exemplares
│       │       ├── Utilizador.java        # Modelo com salt+hash+multas
│       │       ├── Emprestimo.java        # Registo de empréstimo
│       │       ├── Avaliacao.java         # Avaliação de livro
│       │       ├── RegistoMulta.java      # Registo de multa
│       │       ├── MensagemChat.java      # Mensagem de chat
│       │       ├── EstadoLivro.java       # Enum DISPONIVEL/REQUISITADO
│       │       └── Protocolo.java         # Constantes TCP
│       └── resources/public/
│           ├── index.html                 # SPA (Single Page Application)
│           └── app.js                     # Toda a lógica do cliente (~1400 linhas)
│
├── cliente/                               # Módulo Maven — cliente JavaFX (legado)
│   └── src/main/java/cliente/
│       ├── MainApp.java
│       ├── Cliente.java
│       └── NotificacaoService.java
│
├── docs/                                  # Documentação
│   ├── INDEX.md                           # Este ficheiro
│   ├── RELATORIO.md                       # Relatório académico
│   ├── ARQUITETURA.md                     # Arquitectura técnica
│   ├── CONTEXT.md                         # Contexto e requisitos
│   ├── INSTALACAO.md                      # Instalação e execução
│   ├── MANUAL.md                          # Manual do utilizador
│   ├── APRESENTACAO.md                    # Guia de apresentação
│   └── SLIDES.md / SLIDES_5.md           # Conteúdo de slides
│
└── README.md                              # Visão geral e início rápido
```

---

## Funcionalidades Implementadas

| # | Funcionalidade | Estado |
|---|---------------|--------|
| 1 | Inserção, consulta, requisição e devolução de livros | ✅ |
| 2 | Múltiplos clientes simultâneos (HTTP + SSE + TCP) | ✅ |
| 3 | Autenticação por email + password (SHA-256 + salt) | ✅ |
| 4 | Múltiplos exemplares por livro | ✅ |
| 5 | Prazo de devolução (7 dias) | ✅ |
| 6 | Fila de espera FIFO com promoção automática | ✅ |
| 7 | Multas por atraso (0,50 €/dia) | ✅ |
| 8 | Upload de PDF com análise de conteúdo | ✅ |
| 9 | Extracção automática de capa do PDF | ✅ |
| 10 | Workflow de aprovação de livros (pendente → admin) | ✅ |
| 11 | Avaliações e comentários (1–5 estrelas) | ✅ |
| 12 | Notificações em tempo real via SSE | ✅ |
| 13 | Monitor automático de prazos (de hora em hora) | ✅ |
| 14 | Recuperação de password por token | ✅ |
| 15 | Chat em tempo real (global + privado) | ✅ |
| 16 | Exportação de relatórios CSV (4 tipos) | ✅ |
| 17 | Notificações por email (SMTP + 6 templates) | ✅ |
| 18 | Recomendações personalizadas (4 sinais) | ✅ |
| 19 | Painel admin (utilizadores, moderação, config) | ✅ |
| 20 | Log de operações com timestamp | ✅ |

---

## Conceitos de SD Implementados

| Conceito | Implementação no sistema |
|----------|--------------------------|
| Arquitectura cliente-servidor | Javalin (servidor) + browser (cliente) |
| Sockets TCP | GestorTCP porta 9090 com protocolo de texto |
| Comunicação HTTP REST | 30+ endpoints JSON |
| Comunicação assíncrona (push) | SSE — Server-Sent Events |
| Concorrência | `synchronized`, `ConcurrentHashMap`, thread pools |
| Propagação de eventos | Broadcast SSE; GestorTCP `notificarTodos` |
| Comunicação baseada em eventos | `MonitorPrazos` com `ScheduledExecutorService` |
| Autenticação segura | JWT HMAC-SHA256; blacklist de `jti`; renovação automática (24h) |
| Transparência de localização | IP auto-detectado; cliente acede por IP:porta |
| Persistência de estado | JSON files; estado sobrevive a reinícios |
| Middleware | Protocolo de texto TCP; API REST HTTP |
