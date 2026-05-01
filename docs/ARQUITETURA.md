# ARQUITETURA.md — Arquitectura do Sistema

## 1. Visão Geral

O sistema segue a arquitectura **cliente-servidor** com suporte a múltiplos clientes simultâneos. O servidor é o único ponto de gestão dos dados e da lógica de negócio. Os clientes são interfaces gráficas que comunicam com o servidor via protocolo TCP sobre Sockets.

```
┌─────────────────┐         TCP          ┌──────────────────────────────────┐
│  Cliente A      │ ◄──────────────────► │                                  │
│  (JavaFX GUI)   │                      │         SERVIDOR CENTRAL          │
└─────────────────┘                      │                                  │
                                         │  ┌────────────┐  ┌────────────┐  │
┌─────────────────┐         TCP          │  │ Thread A   │  │ Thread B   │  │
│  Cliente B      │ ◄──────────────────► │  │ (Cliente A)│  │ (Cliente B)│  │
│  (JavaFX GUI)   │                      │  └────────────┘  └────────────┘  │
└─────────────────┘                      │         │                │        │
                                         │         ▼                ▼        │
┌─────────────────┐         TCP          │  ┌─────────────────────────────┐  │
│  Cliente N      │ ◄──────────────────► │  │     GestorLivros            │  │
│  (JavaFX GUI)   │                      │  │  (sincronizado, partilhado) │  │
└─────────────────┘                      │  └──────────────┬──────────────┘  │
                                         │                 │                  │
                                         │         ┌───────▼──────┐          │
                                         │         │  BaseDados   │          │
                                         │         │  livros.json │          │
                                         │         └──────────────┘          │
                                         └──────────────────────────────────┘
```

---

## 2. Componentes do Servidor

### 2.1 Servidor.java — Ponto de Entrada

Responsável por:
- Iniciar o `ServerSocket` na porta configurada (padrão: 8080)
- Aguardar conexões de clientes em loop infinito
- Para cada nova conexão, criar uma `GestorClientes` thread e lançá-la
- Manter o registo de todos os clientes activos (para broadcast de notificações)

```java
// Fluxo principal
ServerSocket serverSocket = new ServerSocket(PORTA);
while (true) {
    Socket clienteSocket = serverSocket.accept();
    GestorClientes handler = new GestorClientes(clienteSocket, gestorLivros, logger);
    executor.submit(handler);  // ExecutorService com thread pool
}
```

### 2.2 GestorClientes.java — Thread por Cliente

Cada cliente conectado tem a sua própria instância desta classe, a correr numa thread separada.

Responsável por:
- Ler comandos do cliente via `BufferedReader`
- Interpretar o protocolo (parse do comando)
- Delegar para `GestorLivros` a lógica de negócio
- Enviar resposta de volta ao cliente via `PrintWriter`
- Registar operações no `Logger`
- Limpar recursos ao desconectar

```java
// Fluxo por cliente
public void run() {
    String linha;
    while ((linha = entrada.readLine()) != null) {
        String resposta = processarComando(linha);
        saida.println(resposta);
    }
    // cliente desconectou
    servidor.removerCliente(this);
}
```

### 2.3 GestorLivros.java — Lógica de Negócio

Classe singleton partilhada por todas as threads. **Todos os métodos são sincronizados** para evitar condições de corrida quando múltiplos clientes acedem simultaneamente.

Responsável por:
- CRUD de livros
- Controlo de disponibilidade
- Gestão da fila de espera (FIFO por livro)
- Notificação do próximo da fila quando um livro é devolvido
- Listagem e pesquisa

```java
public synchronized String requisitar(String idLivro, GestorClientes cliente) {
    Livro livro = baseDados.buscar(idLivro);
    if (livro.isDisponivel()) {
        livro.requisitar(cliente.getNome());
        baseDados.guardar();
        notificarTodos("ATUALIZAR"); // broadcast para todos os clientes
        return "OK|Livro requisitado com sucesso";
    } else {
        livro.adicionarFila(cliente);
        return "OK|Adicionado à fila de espera (posição " + livro.posicaoNaFila(cliente) + ")";
    }
}
```

### 2.4 BaseDados.java — Persistência

Responsável por:
- Ler e escrever o ficheiro `livros.json`
- Serializar/deserializar objectos `Livro` com Gson
- Garantir que os dados são guardados após cada operação

### 2.5 Logger.java — Log de Operações

Responsável por:
- Escrever uma linha por operação em `log.txt`
- Formato: `[timestamp] TIPO | estudante | livro`
- Thread-safe (métodos sincronizados)

---

## 3. Componentes do Cliente

### 3.1 MainApp.java — Ponto de Entrada JavaFX

Inicializa a aplicação JavaFX, carrega o FXML e lança a janela principal.

### 3.2 Cliente.java — Conexão TCP

Responsável por:
- Estabelecer conexão TCP ao servidor (com retry até 5 tentativas)
- Ler `config.properties` para obter host e porta
- Enviar comandos via `PrintWriter`
- Expor `BlockingQueue<String> respostas` para receber respostas síncronas
- `enviar(String comando)`: envia e bloqueia até 10 segundos à espera da resposta
- `receberResposta(String msg)`: chamado pela `NotificacaoService` para desbloquear o `poll()`
- `drenaFila()`: limpa respostas residuais antes de ciclos de actualização do painel admin

### 3.3 NotificacaoService.java — Leitor Único do Socket

Thread dedicada que é o **único leitor** do `InputStream` do socket. Faz o encaminhamento das mensagens recebidas:

```
NOTIFICACAO|texto  →  Platform.runLater(controlador.mostrarNotificacao)
ATUALIZAR          →  Platform.runLater(controlador.notificarAtualizacao)
qualquer outra     →  cliente.receberResposta(msg)  — desbloqueia o enviar()
```

```java
public void run() {
    String mensagem;
    while (ativo && (mensagem = entrada.readLine()) != null) {
        final String msg = mensagem;
        if (msg.startsWith(Protocolo.NOTIFICACAO + "|")) {
            String texto = msg.substring(Protocolo.NOTIFICACAO.length() + 1);
            Platform.runLater(() -> controlador.mostrarNotificacao(texto));
        } else if (msg.equals(Protocolo.ATUALIZAR)) {
            Platform.runLater(() -> controlador.notificarAtualizacao());
        } else {
            cliente.receberResposta(msg);  // resposta síncrona
        }
    }
}
```

### 3.4 ControladorPrincipal.java — Lógica da Interface Principal

Responsável por:
- Pedir login e iniciar a conexão ao servidor
- Ligar eventos da GUI (botões) a chamadas ao servidor
- Actualizar a `TableView` de livros e o painel de detalhes
- Mostrar notificações no painel de texto
- Ao receber `ATUALIZAR`: recarregar livros **e** notificar o `AdminPanel` (se aberto)
- Mostrar/esconder o botão Admin consoante o utilizador

### 3.5 AdminPanel.java — Painel de Administração

Janela separada (não-modal) disponível apenas para o utilizador `admin`. Contém três abas:

| Aba | Conteúdo | Fonte |
|-----|----------|-------|
| Utilizadores Online | `ListView` com nome e IP | `ADMIN_USUARIOS` |
| Livros do Sistema | `TableView` com estado, requisitante e fila | `ADMIN_SISTEMA` |
| Log de Operações | `TextArea` monospace com últimas entradas | `HISTORICO` |

Actualiza-se automaticamente quando o servidor emite `ATUALIZAR` (via `notificarAtualizacao()` em cadeia).

---

## 4. Modelo de Dados

### Livro (livros.json)

```json
{
  "livros": [
    {
      "id": "uuid-1234",
      "titulo": "Sistemas Distribuídos — Tanenbaum",
      "autor": "Andrew Tanenbaum",
      "categoria": "Sistemas Distribuídos",
      "estado": "REQUISITADO",
      "estudanteActual": "João",
      "filaEspera": ["Maria", "Pedro"],
      "dataInsercao": "2026-05-01T10:00:00"
    }
  ]
}
```

### Log (log.txt)

```
[2026-05-01 10:00:00] CONECTAR    | João   | -
[2026-05-01 10:01:15] INSERIR     | João   | Sistemas Distribuídos — Tanenbaum
[2026-05-01 10:02:30] REQUISITAR  | Maria  | Sistemas Distribuídos — Tanenbaum
[2026-05-01 10:05:00] DEVOLVER    | Maria  | Sistemas Distribuídos — Tanenbaum
[2026-05-01 10:05:01] NOTIFICAR   | Sistema| João — livro disponível
```

---

## 5. Fluxo de Notificação (Feature Principal)

Este é o fluxo mais importante para demonstrar conceitos de SD:

```
1. Cliente A requisita Livro X → está indisponível
   Servidor: adiciona A à fila de espera do Livro X
   Servidor → Cliente A: "OK|Adicionado à fila (posição 1)"

2. Cliente B devolve Livro X
   Servidor: marca Livro X como disponível
   Servidor: verifica fila → próximo é Cliente A
   Servidor: requisita automaticamente para Cliente A
   Servidor → Cliente A: "NOTIFICACAO|O livro 'X' foi reservado para si!"
   Servidor → Todos: "ATUALIZAR" (broadcast)

3. Todos os clientes recebem ATUALIZAR e refrescam a lista
```

---

## 6. Tratamento de Concorrência

O `GestorLivros` é o recurso partilhado crítico. A sincronização é feita com `synchronized` nos métodos que alteram estado:

| Método | Sincronizado | Motivo |
|--------|-------------|--------|
| `requisitar()` | Sim | Evita dois clientes requisitarem o mesmo livro |
| `devolver()` | Sim | Modifica estado e fila de espera |
| `inserir()` | Sim | Modifica lista de livros |
| `listar()` | Não | Só leitura — seguro sem lock |
| `pesquisar()` | Não | Só leitura — seguro sem lock |

---

## 7. Reconexão Automática (Transparência de Falha)

```java
private void conectar() {
    int tentativas = 0;
    while (tentativas < MAX_TENTATIVAS) {
        try {
            socket = new Socket(HOST, PORTA);
            // conexão bem sucedida
            return;
        } catch (IOException e) {
            tentativas++;
            Thread.sleep(2000); // espera 2 segundos antes de tentar novamente
        }
    }
    // mostrar erro ao utilizador após N tentativas
}
```

---

## 8. Estrutura de Build (Maven Multi-Módulo)

O projecto é composto por dois módulos Maven independentes, cada um com o seu `pom.xml`:

| Módulo | Comando de build | JAR produzido | Main class |
|--------|-----------------|---------------|------------|
| `servidor/` | `mvn package` | `servidor.jar` (fat jar via shade plugin) | `servidor.Servidor` |
| `cliente/` | `mvn javafx:run` | — (executado directamente) | `cliente.MainApp` |

## 9. Dependências

| Biblioteca | Versão | Módulo | Uso |
|------------|--------|--------|-----|
| Java SE | 17+ | ambos | Linguagem base, ServerSocket, Threads |
| JavaFX | 23 | cliente | Interface gráfica (controls, fxml) |
| Gson | 2.10.1 | servidor | Serialização JSON para `livros.json` |

Todas as dependências são geridas via **Maven** (`pom.xml` de cada módulo).
