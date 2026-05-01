# CONTEXT.md — Contexto do Projecto

## 1. Contexto Académico

Este projecto é o trabalho prático da disciplina de **Sistemas Distribuídos**, trabalho A — Gestão de Recursos. O sistema implementa a partilha de livros entre estudantes como caso de uso, mas a profundidade técnica vai além do enunciado mínimo, cobrindo múltiplos conceitos do programa de forma explícita e demonstrável.

O grupo optou pelo trabalho A (livros) em vez do B (chat) com o objectivo de se diferenciar — a maioria dos grupos fará o chat — e demonstrar que os conceitos de SD se aplicam igualmente a sistemas de gestão de recursos, com a vantagem de um domínio de negócio mais rico.

---

## 2. Enunciado Base (Requisitos Mínimos)

O enunciado exige quatro operações sobre livros:

| Operação | Descrição |
|----------|-----------|
| Inserção | Adicionar um novo livro ao sistema |
| Consulta | Ver lista de livros e detalhes |
| Requisição | Pedir empréstimo de um livro disponível |
| Devolução | Devolver um livro requisitado |

---

## 3. Requisitos Estendidos (Features Diferenciadores)

Para além do enunciado mínimo, o sistema implementa:

### 3.1 Múltiplos Clientes Simultâneos
Vários estudantes podem usar o sistema ao mesmo tempo. O servidor cria uma thread dedicada para cada cliente conectado, garantindo que uma operação lenta de um cliente não bloqueia os outros.

**Conceito de SD:** Concorrência, gestão de recursos partilhados.

### 3.2 Notificações Assíncronas
Quando um estudante tenta requisitar um livro que está indisponível, pode entrar na fila de espera. Quando o livro é devolvido, o servidor notifica automaticamente o próximo da fila sem que o cliente precise de perguntar repetidamente.

**Conceito de SD:** Comunicação assíncrona, propagação de eventos, estilo arquitectónico baseado em eventos.

### 3.3 Fila de Espera Automática
Sistema de fila FIFO para livros indisponíveis. O servidor gere a ordem de espera e garante equidade entre os estudantes.

**Conceito de SD:** Gestão de estado distribuído, consistência.

### 3.4 Log Centralizado de Operações
Todas as operações (quem inseriu, requisitou, devolveu, e quando) são registadas num log com timestamp. O log serve como auditoria e histórico do sistema.

**Conceito de SD:** Registo de estado distribuído, rastreabilidade.

### 3.5 Actualização Automática da Lista
A lista de livros no cliente actualiza-se automaticamente quando outro cliente faz uma operação (sem precisar de refrescar manualmente).

**Conceito de SD:** Propagação de eventos, consistência de vistas.

### 3.6 Transparência de Localização
O cliente não sabe nem precisa de saber onde os dados estão guardados fisicamente. Acede sempre pelo mesmo endereço e porta do servidor.

**Conceito de SD:** Transparência de localização (definida no programa como um dos tipos de transparência em SD).

### 3.7 Pesquisa e Filtros
Pesquisa por título, autor ou categoria. Filtro por disponibilidade (todos / disponíveis / requisitados).

**Valor:** Usabilidade e profissionalismo do sistema.

### 3.8 Relatório de Utilização
Estatísticas: livros mais requisitados, estudantes mais activos, histórico de operações.

**Valor:** Demonstração de que os dados do log são úteis além do armazenamento.

---

## 4. Domínio de Negócio

### Entidades

**Livro**
- `id` — identificador único (UUID)
- `titulo` — título do livro
- `autor` — nome do autor
- `categoria` — ex: Programação, Redes, Matemática
- `estado` — DISPONIVEL | REQUISITADO
- `estudanteActual` — quem tem o livro actualmente (null se disponível)
- `filaEspera` — lista de estudantes a aguardar

**Estudante**
- `nome` — identificador no sistema (introduzido no login)
- `socket` — conexão TCP activa (mantida pelo servidor)

**Operação (Log)**
- `timestamp` — data e hora da operação
- `tipo` — INSERIR | REQUISITAR | DEVOLVER | CONECTAR | DESCONECTAR
- `estudante` — quem realizou a operação
- `livro` — livro envolvido (quando aplicável)

### Regras de Negócio

1. Um livro só pode ser requisitado por um estudante de cada vez
2. Se um livro está requisitado, o estudante entra automaticamente na fila de espera
3. Ao devolver, o servidor notifica o primeiro da fila e transfere a requisição automaticamente
4. Um estudante não pode requisitar o mesmo livro duas vezes (se já está na fila)
5. Qualquer estudante pode inserir livros no sistema
6. O mesmo estudante não pode requisitar um livro que já tem

---

## 5. Decisões de Design

### Por que Java?
- Experiência prévia do grupo com Java e Spring Boot
- `ServerSocket` e `Socket` são APIs maduras e bem documentadas
- JavaFX oferece GUI nativa sem dependências externas complexas
- Forte suporte a threads com `ExecutorService` e sincronização

### Por que ficheiro JSON em vez de base de dados?
- O projecto é académico e local — não justifica configurar MySQL/PostgreSQL
- JSON é legível (pode ser mostrado na apresentação)
- Biblioteca Gson (Google) é leve e simples de usar
- Persistência é suficiente para o âmbito do projecto

### Por que TCP em vez de UDP?
- A comunicação de requisições exige confirmação de entrega
- TCP garante ordem e integridade das mensagens
- Mais simples de implementar correctamente para este caso de uso

### Por que protocolo de texto em vez de serialização de objectos?
- Mais fácil de debugar (pode-se ver as mensagens com netcat/telnet)
- Mais fácil de explicar na apresentação
- Independente de versão de classes Java
- Protocolo claro e documentado separadamente

---

## 6. Limitações Conhecidas

| Limitação | Razão | Impacto |
|-----------|-------|---------|
| Sem autenticação real | Âmbito académico | Baixo |
| Sem encriptação TLS | Rede local/acadêmica | Baixo |
| Persistência em ficheiro (não DB) | Simplicidade | Baixo |
| Sem suporte a múltiplos servidores | Âmbito académico | Baixo |
| Sem sincronização entre réplicas | Âmbito académico | Baixo |

---

## 7. O que Este Sistema Demonstra de SD

Este quadro é para usar directamente na apresentação:

| Conceito do Programa | Onde está implementado |
|---------------------|----------------------|
| Definição de SD | O sistema apresenta-se como único mas tem servidor + N clientes |
| Middleware | Camada de protocolo entre cliente e lógica de negócio |
| Transparência de acesso | Cliente usa os mesmos comandos independentemente da localização |
| Transparência de localização | Cliente não sabe onde os dados estão fisicamente |
| Transparência de falha | Reconexão automática do cliente |
| Arquitectura cliente-servidor | Servidor central, clientes independentes |
| Comunicação via Sockets | Base da comunicação em rede |
| Estilo baseado em eventos | Notificações push do servidor para clientes |
| Concorrência (threads) | Uma thread por cliente no servidor |
| Sistemas de ficheiros distribuídos | Analogia com NFS — acesso transparente a dados remotos |
