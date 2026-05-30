# CONTEXT.md — Contexto do Projecto

## 1. Contexto Académico

Este projecto é o trabalho prático da disciplina de **Sistemas Distribuídos** (Trabalho A — Gestão de Recursos). O sistema implementa uma biblioteca digital de partilha de livros entre estudantes, mas a profundidade técnica supera o enunciado mínimo, cobrindo múltiplos conceitos do programa de forma explícita e demonstrável.

O grupo optou pelo Trabalho A (livros) em vez do B (chat) com o objectivo de se diferenciar e demonstrar que os conceitos de SD se aplicam a sistemas de gestão de recursos — com a vantagem de um domínio de negócio mais rico. Adicionalmente, o sistema inclui um módulo de chat em tempo real, cobrindo os requisitos de ambos os trabalhos.

---

## 2. Requisitos Mínimos do Enunciado

| Operação | Implementação |
|----------|--------------|
| **Inserção** | Qualquer utilizador pode adicionar livros; com PDF fica pendente de aprovação |
| **Consulta** | Catálogo com pesquisa por título/autor/categoria e filtros de estado |
| **Requisição** | Disponível / fila de espera automática; prazo de 7 dias |
| **Devolução** | Com cálculo automático de multa se fora do prazo |

---

## 3. Funcionalidades Estendidas

### 3.1 Autenticação e Segurança

Registo e login por email + password. As passwords são guardadas como `SHA-256(salt + password)` com salt de 16 bytes aleatórios — nunca em texto claro. Recuperação por token de 8 caracteres alfanuméricos (TTL 2 horas), entregue por email se SMTP configurado.

A autenticação usa **JWT (JSON Web Tokens)** com assinatura HMAC-SHA256 (`JwtUtil.java`). Cada token tem validade de 24 horas e contém os claims `jti`, `sub` (nome), `email`, `admin`, `iat` e `exp`. O logout invalida o token imediatamente via blacklist de `jti`. O cliente renova automaticamente o token 30 minutos antes da expiração via `POST /api/auth/refresh`.

**Conceito de SD:** Autenticação stateless em sistemas distribuídos; segurança com tokens assinados; renovação automática de credenciais.

### 3.2 Múltiplos Clientes Simultâneos

O servidor aceita N browsers em simultâneo via HTTP/SSE e clientes JavaFX via TCP. Cada pedido HTTP é tratado numa thread independente. O `GestorTCP` cria uma thread por cliente TCP com `ExecutorService`.

**Conceito de SD:** Concorrência, gestão de recursos partilhados.

### 3.3 Notificações Push em Tempo Real (SSE)

Cada browser mantém uma conexão SSE permanente. O servidor notifica directamente o cliente relevante (ou faz broadcast) sem que o cliente precise de perguntar. Eventos: nova requisição, devolução, multa, promoção na fila, livro aprovado, chat, etc.

**Conceito de SD:** Comunicação assíncrona, propagação de eventos, event-driven architecture.

### 3.4 Fila de Espera Automática

Sistema FIFO por livro. Quando uma cópia é devolvida, o servidor promove automaticamente o próximo da fila: regista o empréstimo, define o prazo e envia notificação SSE + TCP.

**Conceito de SD:** Gestão de estado distribuído, consistência.

### 3.5 Múltiplos Exemplares

Um livro pode ter N cópias físicas. Cada utilizador tem o seu prazo individual. A fila de espera é gerida a nível do livro, não da cópia.

**Conceito de SD:** Recursos partilhados com controlo de acesso concorrente.

### 3.6 Monitor Automático de Prazos

`ScheduledExecutorService` que verifica todos os empréstimos activos de hora em hora. Emite alertas SSE e emails automáticos conforme o prazo:
- 1 dia antes: lembrete
- No dia: urgente
- Após o prazo: alerta com multa estimada (repete a cada hora)

**Conceito de SD:** Comunicação baseada em eventos, agendamento distribuído.

### 3.7 Upload de PDF com Análise de Conteúdo

Os utilizadores podem fazer upload de PDFs. O servidor analisa o conteúdo automaticamente (`AnalisadorConteudo`) e sinaliza ficheiros suspeitos. O livro fica pendente até aprovação do admin. A capa é extraída assincronamente da 1ª página (PDFBox).

**Conceito de SD:** Processamento assíncrono, workflow de aprovação.

### 3.8 Chat em Tempo Real

Chat global (todos os utilizadores) e privado (1-para-1) via SSE. O `GestorChat` persiste as mensagens em JSON (máx. 500). O admin tem acesso a todas as conversas privadas.

**Conceito de SD:** Comunicação em grupo, mensagens dirigidas.

### 3.9 Exportação de Relatórios CSV

O admin pode exportar 4 relatórios: empréstimos, multas, utilizadores, livros. Os CSVs incluem BOM UTF-8 para compatibilidade com Excel e são servidos via query param `?sid=` para download directo no browser.

**Conceito de SD:** Acesso privilegiado, controlo de autorização.

### 3.10 Notificações por Email (SMTP)

O `GestorEmail` envia emails assincronamente via SMTP (thread dedicada). Configuração guardada em JSON. 6 templates HTML: boas-vindas, lembrete, urgente, atraso, multa, recuperação de password.

**Conceito de SD:** Comunicação assíncrona, middleware de mensagens.

### 3.11 Motor de Recomendações

Algoritmo que combina 4 sinais para recomendar livros a cada utilizador:
1. Categoria favorita (historial de empréstimos)
2. Avaliação média (≥ 3 estrelas)
3. Collaborative filtering (co-leitores com gostos semelhantes)
4. Popularidade global

**Conceito de SD:** Processamento distribuído de dados, personalização.

### 3.12 Log Centralizado de Operações

Todas as operações são registadas com timestamp em `log.txt`. O admin pode consultar o log em tempo real na interface web.

**Conceito de SD:** Auditoria, rastreabilidade em sistemas distribuídos.

### 3.13 Transparência de Localização

O cliente (browser) acede ao servidor por IP:porta. O servidor detecta automaticamente o seu IP de rede no arranque e exibe-o no terminal para facilitar o acesso de outros computadores.

**Conceito de SD:** Transparência de localização (transparência de acesso + de localização).

---

## 4. Ligação ao Programa de SD

| Tema do Programa | Implementação no Sistema |
|-----------------|--------------------------|
| Comunicação por sockets | GestorTCP com ServerSocket Java (TCP 9090) |
| Protocolos de aplicação | Protocolo de texto TCP + HTTP REST + SSE |
| Concorrência e threads | synchronized, ExecutorService, ScheduledExecutorService |
| Sincronização | GestorLivros/GestorUtilizadores com métodos synchronized |
| Comunicação assíncrona | SSE push + Email SMTP async |
| Propagação de eventos | Broadcast SSE; notificarTodos() |
| Transparência de localização | Acesso por IP:porta; auto-detecção de rede |
| Sessões e estado | Tokens UUID; ConcurrentHashMap sessoes |
| Persistência | Estado em JSON (sobrevive a reinícios) |
| Middleware | API REST HTTP; protocolo TCP |
