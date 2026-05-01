# SKILLS.md — Competências Técnicas e Conceitos de SD

## 1. Sistemas Distribuídos — Conceitos Aplicados

### 1.1 Definição de Sistema Distribuído

Um SD é um conjunto de computadores independentes que se apresenta aos utilizadores como um sistema único e coerente.

**No nosso projecto:** O servidor e os N clientes são máquinas independentes, mas o utilizador vê uma aplicação única — não sabe quantos clientes estão conectados, nem onde os dados estão guardados.

### 1.2 Transparência

| Tipo | Definição | Como implementámos |
|------|-----------|-------------------|
| Acesso | Oculta diferenças na representação de dados | O cliente usa sempre o mesmo protocolo de comandos |
| Localização | Oculta onde o recurso está fisicamente | Cliente conecta a HOST:PORTA, não sabe o caminho do ficheiro |
| Falha | Oculta a recuperação de falhas | Reconexão automática do cliente ao servidor |
| Concorrência | Oculta que múltiplos utilizadores acedem | `synchronized` no GestorLivros garante consistência |

### 1.3 Arquitectura Cliente-Servidor

- **Servidor:** aceita pedidos, processa, responde. Tem autoridade sobre os dados.
- **Cliente:** inicia pedidos, apresenta resultados ao utilizador. Não tem dados locais.
- **Vantagem:** centralização facilita consistência dos dados.
- **Desvantagem:** servidor é ponto único de falha (trade-off académico aceite).

### 1.4 Comunicação via Sockets TCP

O Socket é a interface padronizada para comunicação em rede. TCP garante:
- **Entrega garantida:** os pacotes chegam ao destino
- **Ordem:** os pacotes chegam na ordem enviada
- **Controlo de erros:** detecção e retransmissão automática

```
Cliente                    Servidor
  │                            │
  │──── connect(HOST, PORTA) ──►│
  │                            │
  │──── "LISTAR\n" ───────────►│
  │                            │  (processa)
  │◄─── "LIVROS|id1,t1,...\n" ─│
  │                            │
  │──── "REQUISITAR|id1\n" ───►│
  │                            │  (sincronizado)
  │◄─── "OK|Requisitado\n" ────│
```

### 1.5 Comunicação Assíncrona (Notificações Push)

No modelo síncrono clássico: cliente pergunta → servidor responde. O cliente precisa de perguntar repetidamente ("polling") para saber se algo mudou.

No nosso sistema usamos **comunicação assíncrona**: o servidor envia mensagens ao cliente sem que este tenha pedido. O cliente tem uma thread separada (`NotificacaoService`) sempre à escuta.

**Conceito do programa:** Estilo arquitectónico baseado em eventos — processos comunicam-se através da propagação de eventos.

### 1.6 Middleware

O nosso protocolo de texto (`INSERIR|...`, `LISTAR`, `OK|...`) funciona como middleware simplificado — é a camada que abstrai a comunicação e oferece uma interface comum entre cliente e servidor, independentemente dos detalhes da rede.

---

## 2. Java — Guia de Implementação

### 2.1 ServerSocket — Aceitar Múltiplos Clientes

```java
import java.net.*;
import java.io.*;
import java.util.concurrent.*;

public class Servidor {
    private static final int PORTA = 8080;
    private ExecutorService executor = Executors.newCachedThreadPool();
    
    public void iniciar() throws IOException {
        ServerSocket serverSocket = new ServerSocket(PORTA);
        System.out.println("Servidor iniciado na porta " + PORTA);
        
        while (true) {
            Socket clienteSocket = serverSocket.accept(); // bloqueia até nova conexão
            System.out.println("Novo cliente: " + clienteSocket.getInetAddress());
            
            GestorClientes handler = new GestorClientes(clienteSocket, gestorLivros);
            executor.submit(handler); // lança em thread do pool
        }
    }
}
```

### 2.2 Thread por Cliente

```java
public class GestorClientes implements Runnable {
    private Socket socket;
    private BufferedReader entrada;
    private PrintWriter saida;
    
    public GestorClientes(Socket socket, GestorLivros gestorLivros) throws IOException {
        this.socket = socket;
        this.entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.saida = new PrintWriter(socket.getOutputStream(), true); // auto-flush
    }
    
    @Override
    public void run() {
        try {
            String linha;
            while ((linha = entrada.readLine()) != null) {
                String resposta = processarComando(linha);
                saida.println(resposta);
            }
        } catch (IOException e) {
            System.out.println("Cliente desconectou: " + socket.getInetAddress());
        } finally {
            try { socket.close(); } catch (IOException e) { }
        }
    }
    
    // Enviar notificação do servidor para este cliente específico
    public void enviarNotificacao(String mensagem) {
        saida.println("NOTIFICACAO|" + mensagem);
    }
}
```

### 2.3 Sincronização — Evitar Condições de Corrida

```java
public class GestorLivros {
    private List<Livro> livros = new ArrayList<>();
    
    // synchronized garante que só uma thread executa este método de cada vez
    public synchronized String requisitar(String id, GestorClientes solicitante) {
        Livro livro = buscarPorId(id);
        if (livro == null) return "ERRO|Livro não encontrado";
        
        if (livro.isDisponivel()) {
            livro.setEstuданteActual(solicitante.getNome());
            livro.setEstado(EstadoLivro.REQUISITADO);
            guardar();
            notificarTodos("ATUALIZAR");
            return "OK|Livro requisitado com sucesso";
        } else {
            livro.adicionarFila(solicitante);
            int posicao = livro.posicaoNaFila(solicitante);
            return "OK|Adicionado à fila de espera (posição " + posicao + ")";
        }
    }
    
    public synchronized String devolver(String id) {
        Livro livro = buscarPorId(id);
        if (livro == null) return "ERRO|Livro não encontrado";
        
        livro.setEstuданteActual(null);
        
        // notificar o próximo da fila
        GestorClientes proximo = livro.proximoNaFila();
        if (proximo != null) {
            livro.setEstuданteActual(proximo.getNome());
            proximo.enviarNotificacao("O livro '" + livro.getTitulo() + "' está disponível para si!");
        } else {
            livro.setEstado(EstadoLivro.DISPONIVEL);
        }
        
        guardar();
        notificarTodos("ATUALIZAR");
        return "OK|Livro devolvido com sucesso";
    }
}
```

### 2.4 Cliente — Conexão e Comunicação

```java
public class Cliente {
    private Socket socket;
    private PrintWriter saida;
    private BufferedReader entrada;
    
    public void conectar(String host, int porta) throws IOException {
        socket = new Socket(host, porta);
        saida = new PrintWriter(socket.getOutputStream(), true);
        entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()));
    }
    
    public String enviar(String comando) throws IOException {
        saida.println(comando);
        return entrada.readLine(); // aguarda resposta
    }
}
```

### 2.5 JavaFX — Actualizar Interface da Thread de Rede

A interface JavaFX só pode ser actualizada pela JavaFX Application Thread. Quando a thread de rede recebe uma mensagem, usa `Platform.runLater()`:

```java
public class NotificacaoService implements Runnable {
    private ControladorPrincipal controlador;
    private BufferedReader entrada;
    
    @Override
    public void run() {
        try {
            String mensagem;
            while ((mensagem = entrada.readLine()) != null) {
                final String msg = mensagem;
                
                if (msg.startsWith("NOTIFICACAO|")) {
                    String texto = msg.split("\\|")[1];
                    Platform.runLater(() -> controlador.mostrarNotificacao(texto));
                    
                } else if (msg.equals("ATUALIZAR")) {
                    Platform.runLater(() -> controlador.recarregarLivros());
                }
            }
        } catch (IOException e) {
            Platform.runLater(() -> controlador.mostrarErroConexao());
        }
    }
}
```

### 2.6 Gson — Persistência em JSON

```java
import com.google.gson.*;
import java.io.*;

public class BaseDados {
    private static final String FICHEIRO = "data/livros.json";
    private Gson gson = new GsonBuilder().setPrettyPrinting().create();
    
    public List<Livro> carregar() {
        try (Reader reader = new FileReader(FICHEIRO)) {
            DadosJson dados = gson.fromJson(reader, DadosJson.class);
            return dados.getLivros();
        } catch (FileNotFoundException e) {
            return new ArrayList<>(); // ficheiro não existe ainda
        } catch (IOException e) {
            throw new RuntimeException("Erro ao carregar base de dados", e);
        }
    }
    
    public void guardar(List<Livro> livros) {
        try (Writer writer = new FileWriter(FICHEIRO)) {
            DadosJson dados = new DadosJson(livros);
            gson.toJson(dados, writer);
        } catch (IOException e) {
            throw new RuntimeException("Erro ao guardar base de dados", e);
        }
    }
}
```

---

## 3. Erros Comuns e Soluções

| Erro | Causa | Solução |
|------|-------|---------|
| `Connection refused` | Servidor não está a correr | Iniciar o servidor antes do cliente |
| `Address already in use` | Porta 8080 ocupada | Mudar a porta ou fechar o processo anterior |
| `NullPointerException` no JavaFX | Actualizar UI fora da FX Thread | Usar `Platform.runLater()` |
| Dois clientes requisitam o mesmo livro | Falta de sincronização | Adicionar `synchronized` ao método |
| Livro não guardado após operação | `guardar()` não foi chamado | Chamar `baseDados.guardar()` em todos os métodos de escrita |
| `readLine()` retorna null | Cliente desconectou | Tratar a saída do loop quando `readLine()` == null |

---

## 4. Checklist de Competências

### Backend (Pessoa 1)
- [ ] `ServerSocket` a aceitar conexões
- [ ] Thread por cliente com `ExecutorService`
- [ ] Protocolo de comandos implementado
- [ ] `synchronized` nos métodos críticos
- [ ] Fila de espera funcional
- [ ] Notificações push ao cliente
- [ ] Persistência em JSON com Gson
- [ ] Log de operações com timestamp

### Frontend (Pessoa 2)
- [ ] `Socket` a conectar ao servidor
- [ ] Enviar e receber comandos
- [ ] `NotificacaoService` em thread separada
- [ ] `Platform.runLater()` para actualizar UI
- [ ] `TableView` de livros funcional
- [ ] Botões de inserir, requisitar, devolver
- [ ] Pesquisa e filtros
- [ ] Painel de notificações
- [ ] Relatório de utilização

### Ambos
- [ ] Protocolo acordado e documentado
- [ ] Testado com dois clientes em simultâneo
- [ ] Demonstração da fila de espera e notificação
- [ ] Relatório escrito
- [ ] Slides da apresentação
