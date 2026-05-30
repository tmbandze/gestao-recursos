# APRESENTACAO.md — Guia para a Apresentação

## Estratégia

O sistema implementa o Trabalho A (livros) com features que cobrem também o Trabalho B (chat). A diferenciação é mostrar que implementaram **múltiplos paradigmas de SD** num sistema coeso: TCP, HTTP REST, SSE, email SMTP, threads agendadas, collaborative filtering.

**Mensagem central:** *"Implementámos os conceitos de SD do programa de três formas distintas — TCP síncrono, HTTP REST stateless e SSE assíncrono — e ligámos cada feature ao conceito correspondente."*

---

## Estrutura Sugerida (20–25 minutos)

### 1. Introdução (2 min)

- "Optámos pelo Trabalho A mas com features adicionais que cobrem também o B"
- Mostrar o diagrama de arquitectura (do ARQUITETURA.md ou SLIDES.md)
- Mencionar o stack: Java 17, Javalin, SSE, Jakarta Mail

### 2. Demonstração ao Vivo (12–15 min)

**Abrir dois browsers separados (janelas de anónimo + normal) e fazer login com dois utilizadores diferentes.**

---

#### Passo 1 — Registo e Autenticação (1 min)
- Registar um novo utilizador no browser B
- Mostrar que aparece imediatamente no painel admin do browser A
- **Dizer:** *"Comunicação em tempo real via SSE — o servidor notifica o admin sem polling"*

---

#### Passo 2 — Catálogo e Pesquisa (1 min)
- Pesquisar livros por categoria
- Usar os filtros Disponíveis / Requisitados
- Mostrar recomendações personalizadas (faixa horizontal)
- **Dizer:** *"As recomendações combinam 4 sinais: categoria favorita, avaliação, leitores semelhantes e popularidade"*

---

#### Passo 3 — Requisição e Notificação em Tempo Real (3 min)

1. No browser A, requisitar um livro → mostrar confirmação com prazo
2. No browser B (admin), ver que o livro passou a "Requisitado" **automaticamente**
3. **Dizer:** *"Propagação de eventos via SSE broadcast — todos os clientes actualizam sem refrescar"*
4. No browser B, tentar requisitar o mesmo livro → "Adicionado à fila (posição 1)"
5. No browser A, devolver o livro
6. No browser B, mostrar a notificação automática: "📚 A tua cópia está disponível!"
7. **Dizer:** *"Comunicação assíncrona — o servidor notificou o cliente sem ele ter pedido nada"*

---

#### Passo 4 — Upload de PDF e Aprovação (2 min)
- Fazer upload de um livro com PDF
- Mostrar que fica pendente e o admin recebe notificação
- Aprovar o livro → aparece no catálogo para todos
- **Dizer:** *"Workflow distribuído com SSE: cada passo notifica os participantes relevantes"*

---

#### Passo 5 — Multas por Atraso (1 min)
- Mostrar um utilizador com multa pendente
- Mostrar que fica bloqueado de novas requisições
- Admin perdoa a multa → utilizador desbloqueado
- **Dizer:** *"Estado partilhado sincronizado: o bloqueio é verificado a cada requisição com synchronized"*

---

#### Passo 6 — Chat em Tempo Real (1 min)
- Abrir o chat global nos dois browsers
- Enviar mensagem → aparece instantaneamente no outro
- Trocar mensagem privada admin ↔ estudante
- **Dizer:** *"Chat via SSE — mesmo mecanismo das notificações mas para mensagens de texto"*

---

#### Passo 7 — Exportar CSV e Email (1 min)
- Exportar relatório de empréstimos
- Mostrar o ficheiro CSV com todos os dados
- Mostrar a configuração SMTP
- **Dizer:** *"Os downloads CSV usam ?sid= como autenticação porque o browser não pode enviar headers customizados em downloads directos"*

---

#### Passo 8 — TCP (canal legado) (1 min, opcional)
- Mostrar o GestorTCP no código ou mencionar que existe
- **Dizer:** *"O sistema mantém compatibilidade com o cliente JavaFX original via ServerSocket TCP na porta 9090 — dois protocolos, um servidor"*

---

### 3. Ligação ao Programa (5 min)

Para cada conceito, dizer a frase e mostrar o ponto relevante no código ou na demo:

| O que dizer | Conceito | Onde mostrar |
|-------------|----------|--------------|
| "O servidor cria uma thread por cliente TCP" | Concorrência / thread pool | `GestorTCP.java` — `executor.submit()` |
| "Todos os métodos que modificam dados são synchronized" | Sincronização | `GestorLivros.java` — cabeçalho dos métodos |
| "O cliente não sabe onde os dados estão" | Transparência de localização | URL no browser — só vê IP:porta |
| "O servidor notifica sem o cliente perguntar" | Comunicação assíncrona | `notificarUsuario()` no `Servidor.java` |
| "O monitor de prazos corre de hora em hora automaticamente" | Comunicação baseada em eventos | `MonitorPrazos.java` — `scheduleAtFixedRate` |
| "A sessão é um JWT assinado — o servidor não guarda estado" | Autenticação stateless | `JwtUtil.java` — `gerarToken`, `verificar`; header `Authorization: Bearer` |
| "Os dados sobrevivem a um reinício do servidor" | Persistência | `data/livros.json` aberto no editor |

---

### 4. Perguntas Frequentes

**"Porque usaram Javalin em vez de ServerSocket puro?"**
> "O Javalin facilita o routing HTTP mas o núcleo usa Jetty com Sockets por baixo. O ServerSocket TCP ainda está presente na porta 9090 para o cliente JavaFX. Usámos dois protocolos intencionalmente para demonstrar ambos."

**"Como funciona o SSE?"**
> "É uma conexão HTTP persistente onde o servidor escreve eventos no formato `data: ...\n\n`. O browser tem a API `EventSource` nativa que reconecta automaticamente. É unidireccional (servidor → cliente) — as operações do cliente para o servidor continuam a usar HTTP POST normal."

**"E se o servidor reiniciar?"**
> "Os dados (livros, utilizadores, empréstimos, chat) são guardados em JSON imediatamente após cada operação — nunca perdem-se. As sessões activas perdem-se porque estão em memória, mas os utilizadores fazem login novamente e os dados estão todos lá."

**"Como garantem que dois utilizadores não requisitam a mesma cópia ao mesmo tempo?"**
> "O método `requisitar()` em `GestorLivros` é `synchronized`. Apenas uma thread executa de cada vez — o segundo utilizador espera. Se a cópia já estiver tomada quando chegar a sua vez, vai para a fila de espera."

---

## Pontos a Não Esquecer

- ✅ Demonstrar os **dois** browsers em simultâneo (prova os múltiplos clientes)
- ✅ Mostrar a actualização **sem refrescar** a página (prova o SSE)
- ✅ Mostrar o código de **pelo menos um** método `synchronized`
- ✅ Ligar cada feature ao conceito de SD correspondente
- ✅ Mencionar as **20 funcionalidades** se perguntarem o que foi implementado
- ✅ Ter o `servidor.jar` a correr antes da apresentação começa
