# MANUAL.md — Manual do Utilizador

## 1. Iniciar Sessão

Ao abrir o cliente, aparece uma janela de login. Introduzir o nome de estudante e clicar em **Entrar**. O nome será usado para identificar as suas requisições e notificações.

---

## 2. Interface Principal

A interface é composta por:

- **Barra de pesquisa** (topo) — pesquisar livros por título, autor ou categoria
- **Filtro de estado** — ver Todos / Disponíveis / Requisitados
- **Tabela de livros** — lista actualizável em tempo real
- **Painel de detalhes** (lateral) — informações do livro seleccionado e fila de espera
- **Painel de notificações** (inferior) — alertas recebidos do servidor
- **Botões de acção** — Inserir, Requisitar, Devolver
- **Botão Relatório** — estatísticas de utilização

---

## 3. Operações

### Inserir Livro

1. Clicar em **Inserir Livro**
2. Preencher: Título, Autor, Categoria
3. Clicar em **Confirmar**
4. O livro aparece imediatamente na lista de todos os clientes

### Consultar Livros

- A lista mostra todos os livros com título, autor, categoria e estado
- Usar a barra de pesquisa para filtrar por qualquer campo
- Usar o filtro de estado para ver só disponíveis ou só requisitados
- Clicar num livro para ver os detalhes e a fila de espera no painel lateral

### Requisitar Livro

1. Seleccionar um livro na tabela
2. Clicar em **Requisitar**
3. Se o livro estiver disponível: confirmação de sucesso
4. Se o livro estiver requisitado: mensagem informando a posição na fila de espera
5. Quando o livro ficar disponível, **receberá uma notificação automática**

### Devolver Livro

1. Seleccionar o livro que requisitou (aparece com o seu nome)
2. Clicar em **Devolver**
3. Confirmação de sucesso
4. Se houver estudantes na fila, o próximo é notificado automaticamente

---

## 4. Notificações

O painel de notificações (inferior) mostra alertas em tempo real. Exemplos:
- "O livro 'Sistemas Distribuídos' foi reservado para si!"
- "Novo livro inserido: 'Java Concorrência'"

As notificações aparecem mesmo que não esteja a interagir com a aplicação — não precisa de refrescar manualmente.

---

## 5. Relatório de Utilização

Clicar em **Relatório** para ver:
- Livros mais requisitados
- Total de operações realizadas
- Histórico recente de operações (quem fez o quê e quando)
