# Gestão de Recursos — Biblioteca Digital

Sistema distribuído de gestão e partilha de livros entre estudantes.  
**Trabalho Prático A — Sistemas Distribuídos | 2025/2026**

---

## Início Rápido

```bash
cd servidor
mvn clean package
java -jar target/servidor.jar
```

Abrir o browser em **http://localhost:8080**

Conta admin por defeito: `admin@biblioteca.local` / `admin123`

---

## Funcionalidades

| Módulo | Funcionalidades |
|--------|----------------|
| **Autenticação** | Registo, login, logout, recuperação de password por token |
| **Catálogo** | Pesquisa, filtros, múltiplos exemplares, capa automática do PDF |
| **Empréstimos** | Requisição (prazo 7 dias), devolução, fila de espera FIFO |
| **Multas** | 0,50 €/dia de atraso; bloqueio de novas requisições; perdão admin |
| **Avaliações** | 1–5 estrelas + comentário por livro |
| **Notificações** | Push em tempo real via SSE (atraso, multa, livro disponível…) |
| **Chat** | Sala global + mensagens privadas em tempo real (SSE) |
| **Admin** | Gestão de utilizadores, aprovação/rejeição de livros, moderação |
| **Email** | Boas-vindas, lembretes de prazo, alerta de atraso, recuperação |
| **CSV** | Exportação de relatórios: empréstimos, multas, utilizadores, livros |
| **Recomendações** | Motor pessoal: categoria favorita, avaliações, collaborative filtering |
| **Monitor** | Verificação automática de prazos a cada hora |

---

## Conceitos de SD Demonstrados

| Conceito | Implementação |
|----------|--------------|
| Arquitectura Cliente-Servidor | Javalin HTTP + browser como cliente |
| Sockets TCP | Canal assíncrono (porta 9090) para cliente JavaFX legado |
| Comunicação assíncrona | SSE (Server-Sent Events) + email SMTP async |
| Concorrência | `synchronized` em todos os gestores + `ExecutorService` |
| Propagação de eventos | Broadcast SSE a todos os clientes ligados |
| Comunicação baseada em eventos | `MonitorPrazos` agendado com `ScheduledExecutorService` |
| Transparência de localização | Browser acede ao servidor por IP:porta |
| Sessões sem estado | Token UUID por sessão (`X-Session-ID` header) |
| Persistência distribuída | Estado em ficheiros JSON (sobrevive a reinícios) |

---

## Arquitectura

```
Browser (HTML + JS)
      │
      │  HTTP REST  /api/...
      │  SSE        /api/sse
      ▼
┌─────────────────────────────────────────────┐
│                 Servidor.java               │
│         Javalin HTTP — porta 8080           │
│                                             │
│  GestorLivros      GestorHistorico          │
│  GestorUtilizadores  GestorChat             │
│  GestorEmail       GestorRecomendacoes      │
│  MonitorPrazos     Logger                   │
│                                             │
│  GestorTCP ──── porta 9090 (cliente JavaFX) │
└──────────────┬──────────────────────────────┘
               │
         data/ (JSON)
         ├── livros.json
         ├── utilizadores.json
         ├── emprestimos.json
         ├── chat.json
         ├── log.txt
         ├── pdfs/
         └── capas/
```

---

## Documentação

| Documento | Conteúdo |
|-----------|---------|
| [docs/RELATORIO.md](docs/RELATORIO.md) | Relatório académico completo |
| [docs/ARQUITETURA.md](docs/ARQUITETURA.md) | Design técnico detalhado |
| [docs/INSTALACAO.md](docs/INSTALACAO.md) | Como instalar e executar |
| [docs/MANUAL.md](docs/MANUAL.md) | Manual do utilizador |
| [docs/CONTEXT.md](docs/CONTEXT.md) | Contexto e requisitos académicos |
| [docs/APRESENTACAO.md](docs/APRESENTACAO.md) | Guia para a apresentação |

---

## Stack Tecnológico

| Componente | Tecnologia | Versão |
|-----------|-----------|--------|
| Linguagem | Java | 17 |
| Servidor HTTP + SSE | Javalin | 6.1.6 |
| Serialização JSON | Gson | 2.10.1 |
| Geração de capas PDF | Apache PDFBox | 3.0.3 |
| Email SMTP | Jakarta Mail (Angus) | 2.0.3 |
| Build | Apache Maven | 3.8+ |
| Cliente | HTML5 + CSS3 + JavaScript | — |

---

**Disciplina:** Sistemas Distribuídos  
**Versão:** 2.0 — Maio 2026
