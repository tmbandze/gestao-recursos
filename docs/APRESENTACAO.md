# APRESENTACAO.md — Guia para a Apresentação

## Estratégia

A maioria dos grupos fará o trabalho B (Chat). A vossa diferenciação é mostrar que implementaram os **mesmos conceitos de SD** num sistema mais complexo e com maior valor prático — e conseguem ligar cada feature técnica ao programa da disciplina.

---

## Estrutura Sugerida (15-20 minutos)

### 1. Introdução (2 min)
- "Optámos pelo trabalho A mas com features adicionais que cobrem os conceitos de SD do programa"
- Mostrar o diagrama da arquitectura

### 2. Demonstração ao Vivo (8-10 min)

**Demonstrar nesta ordem — é a ordem que impressiona mais:**

**Passo 1 — Múltiplos clientes**
- Abrir dois clientes em janelas separadas
- Fazer login com nomes diferentes (ex: João e Maria)
- Mostrar que ambos vêem a mesma lista

**Passo 2 — Inserção e actualização em tempo real**
- No Cliente A, inserir um livro
- Mostrar que o livro aparece instantaneamente no Cliente B **sem ele ter feito nada**
- Dizer: *"Esta é a propagação de eventos — o servidor faz broadcast para todos os clientes"*

**Passo 3 — Fila de espera e notificação (ponto alto)**
- No Cliente A, requisitar um livro
- No Cliente B, tentar requisitar o mesmo livro → mostrar mensagem "posição 1 na fila"
- No Cliente A, devolver o livro
- Mostrar que o Cliente B recebe a notificação **automaticamente**
- Dizer: *"Isto é comunicação assíncrona — o servidor notificou o cliente sem ele ter pedido"*

**Passo 4 — Pesquisa e filtros**
- Pesquisar por categoria ou autor
- Filtrar por disponíveis

**Passo 5 — Log e relatório**
- Mostrar o log com todas as operações e timestamps
- Mostrar o relatório de utilização

### 3. Ligação ao Programa (5 min)

Para cada conceito, dizer a frase e mostrar o código ou o comportamento:

| O que dizer | Conceito | Onde mostrar |
|-------------|----------|--------------|
| "O cliente não sabe onde os dados estão — acede sempre ao mesmo endereço" | Transparência de localização | Ficheiro config.properties |
| "O servidor cria uma thread para cada cliente — podem operar em simultâneo" | Concorrência | GestorClientes.java com ExecutorService |
| "O método requisitar é synchronized — evita dois clientes requisitarem ao mesmo tempo" | Gestão de recursos partilhados | GestorLivros.java |
| "O cliente tem uma thread separada só para receber notificações" | Comunicação assíncrona | NotificacaoService.java |
| "Se o servidor reiniciar, o cliente reconecta automaticamente" | Transparência de falha | Cliente.java — método reconectar() |
| "O nosso protocolo de texto é o middleware que liga cliente e servidor" | Middleware | PROTOCOLO.md |

### 4. Perguntas Esperadas e Respostas

**"Por que escolheram o trabalho A e não o B?"**
→ "Queríamos mostrar que os conceitos de SD se aplicam a sistemas de gestão, não só a chats. Implementámos comunicação assíncrona, concorrência e propagação de eventos — os mesmos conceitos — num domínio mais rico."

**"Como garantem que dois clientes não requisitam o mesmo livro ao mesmo tempo?"**
→ "O método `requisitar` é `synchronized` em Java. Quando a Thread A está a executar, a Thread B fica bloqueada até A terminar. Assim só um pode requisitar de cada vez."

**"O que acontece se o servidor falhar?"**
→ "O cliente detecta a perda de conexão e tenta reconectar automaticamente até N tentativas. É a implementação de transparência de falha."

**"Como funciona a notificação em tempo real?"**
→ "O cliente tem duas threads: uma para enviar comandos e receber respostas, e outra (`NotificacaoService`) sempre à escuta. Quando o servidor envia `NOTIFICACAO|texto`, essa thread captura e actualiza a interface via `Platform.runLater()`."

---

## O que NÃO fazer na apresentação

- Não ler os slides — falar para a docente e demonstrar ao vivo
- Não apologizar por features que não implementaram — focar no que funciona
- Não explicar código linha por linha — mostrar o comportamento e mencionar o conceito
- Não deixar a demo para o fim — começar com a demo, depois explicar

---

## Checklist Antes da Apresentação

- [ ] Servidor a correr no computador antes de entrar na sala
- [ ] Dois clientes prontos para abrir
- [ ] Livros de exemplo já inseridos (ou inserir ao vivo — mais impressionante)
- [ ] Diagrama de arquitectura impresso ou nos slides
- [ ] Tabela de conceitos de SD nos slides
- [ ] Código-fonte aberto no IDE para mostrar se perguntarem
