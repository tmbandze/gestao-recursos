# PROTOCOLO.md — Protocolo de Comunicação

## Visão Geral

A comunicação entre cliente e servidor usa texto simples sobre TCP. Cada mensagem é uma linha terminada em `\n`. Os campos são separados pelo caractere `|`.

**Formato geral:**
```
COMANDO|campo1|campo2|...\n
```

---

## Comandos do Cliente → Servidor

| Comando | Formato | Descrição |
|---------|---------|-----------|
| `LOGIN` | `LOGIN|nome_estudante` | Registar o estudante no sistema |
| `LISTAR` | `LISTAR` | Listar todos os livros |
| `LISTAR_DISPONIVEIS` | `LISTAR_DISPONIVEIS` | Listar só livros disponíveis |
| `PESQUISAR` | `PESQUISAR|termo` | Pesquisar por título, autor ou categoria |
| `INSERIR` | `INSERIR|titulo|autor|categoria` | Inserir novo livro |
| `REQUISITAR` | `REQUISITAR|id_livro` | Requisitar um livro |
| `DEVOLVER` | `DEVOLVER|id_livro` | Devolver um livro |
| `DETALHES` | `DETALHES|id_livro` | Ver detalhes e fila de espera |
| `HISTORICO` | `HISTORICO` | Ver log das últimas operações |
| `RELATORIO` | `RELATORIO` | Estatísticas de utilização |
| `SAIR` | `SAIR` | Desconectar do servidor |

---

## Respostas do Servidor → Cliente

### Respostas Síncronas (resposta directa a um comando)

| Resposta | Formato | Quando |
|----------|---------|--------|
| `OK` | `OK|mensagem` | Operação concluída com sucesso |
| `ERRO` | `ERRO|motivo` | Operação falhou |
| `LIVROS` | `LIVROS|id,titulo,autor,categoria,estado;id,...` | Resposta a LISTAR / PESQUISAR |
| `DETALHES` | `DETALHES|id|titulo|autor|categoria|estado|estudante|fila` | Resposta a DETALHES |
| `LOG` | `LOG|linha1\nLinha2\n...` | Resposta a HISTORICO |
| `STATS` | `STATS|livro_mais_req:titulo|total_ops:N|...` | Resposta a RELATORIO |

### Mensagens Assíncronas (iniciadas pelo servidor, sem pedido)

| Mensagem | Formato | Quando |
|----------|---------|--------|
| `NOTIFICACAO` | `NOTIFICACAO|texto` | Livro da fila de espera ficou disponível |
| `ATUALIZAR` | `ATUALIZAR` | Lista de livros mudou (broadcast a todos) |

---

## Exemplos Completos

### Login
```
Cliente → Servidor:  LOGIN|João
Servidor → Cliente:  OK|Bem-vindo, João
```

### Listar livros
```
Cliente → Servidor:  LISTAR
Servidor → Cliente:  LIVROS|uuid-1,Tanenbaum,Andrew Tanenbaum,SD,DISPONIVEL;uuid-2,CNAE,Forouzan,Redes,REQUISITADO
```

### Inserir livro
```
Cliente → Servidor:  INSERIR|Sistemas Distribuídos|Tanenbaum|Sistemas Distribuídos
Servidor → Cliente:  OK|Livro inserido com id uuid-1234
```

### Requisitar livro disponível
```
Cliente → Servidor:  REQUISITAR|uuid-1234
Servidor → Cliente:  OK|Livro requisitado com sucesso
               (e broadcast ATUALIZAR para todos os clientes)
```

### Requisitar livro indisponível (entra na fila)
```
Cliente → Servidor:  REQUISITAR|uuid-1234
Servidor → Cliente:  OK|Adicionado à fila de espera (posição 1)
```

### Devolver livro (com próximo na fila)
```
Cliente → Servidor:  DEVOLVER|uuid-1234
Servidor → Cliente:  OK|Livro devolvido com sucesso
               (servidor notifica o próximo da fila:)
Servidor → ClienteB: NOTIFICACAO|O livro 'Sistemas Distribuídos' foi reservado para si!
               (e broadcast ATUALIZAR para todos)
```

### Pesquisar
```
Cliente → Servidor:  PESQUISAR|redes
Servidor → Cliente:  LIVROS|uuid-2,CNAE,Forouzan,Redes,REQUISITADO
```

### Erro — livro não encontrado
```
Cliente → Servidor:  REQUISITAR|id-invalido
Servidor → Cliente:  ERRO|Livro não encontrado
```

---

## Formato do Campo LIVROS

Os livros na resposta `LIVROS` são separados por `;`. Cada livro tem os campos separados por `,`:

```
id,titulo,autor,categoria,estado
```

Estados possíveis: `DISPONIVEL` | `REQUISITADO`

**Exemplo de parse no cliente:**
```java
String[] partes = resposta.split("\\|", 2);  // ["LIVROS", "uuid1,t1,a1,c1,D;uuid2,..."]
String[] livros = partes[1].split(";");
for (String livroStr : livros) {
    String[] campos = livroStr.split(",");
    Livro livro = new Livro(campos[0], campos[1], campos[2], campos[3], campos[4]);
}
```

---

## Notas de Implementação

1. **Todas as mensagens terminam em `\n`** — usar `println()` no servidor e `readLine()` no cliente.
2. **O separador `|` não pode aparecer nos dados** — validar ao inserir títulos/autores.
3. **A thread `NotificacaoService` do cliente** deve identificar mensagens assíncronas (`NOTIFICACAO|` e `ATUALIZAR`) antes de tratar como resposta síncrona.
4. **O campo `fila` em DETALHES** é uma lista de nomes separados por vírgula, ou `vazia` se não há ninguém na fila.
