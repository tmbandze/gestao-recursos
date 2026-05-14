# Apresentação — 5 Slides — Tecnologias
## Sistema de Gestão de Recursos | Sistemas Distribuídos 2025/2026

> Cada secção `---` = 1 slide. Copiar para PowerPoint / Google Slides.

---

## SLIDE 1 — Capa

**Sistema de Gestão de Recursos**
*Partilha de Livros entre Estudantes*

Sistemas Distribuídos — Trabalho Prático A
Ano lectivo 2025/2026

*(fundo escuro, texto branco; logótipo da instituição)*

---

## SLIDE 2 — Stack Tecnológico

**Tecnologias utilizadas**

| Camada | Tecnologia | Versão |
|--------|-----------|--------|
| Linguagem | Java SE | 17+ |
| Interface Gráfica | JavaFX | 23 |
| Comunicação em rede | ServerSocket / Socket (TCP) | Java stdlib |
| Serialização / Persistência | Gson → `livros.json` | 2.10.1 |
| Build e dependências | Apache Maven (multi-módulo) | 3.8+ |

**Duas JVMs independentes** — servidor e cliente correm em processos separados, potencialmente em máquinas distintas.

*(adicionar logótipos das tecnologias)*

---

## SLIDE 3 — Comunicação TCP: Sockets e Protocolo

**"Como cliente e servidor trocam mensagens?"**

```
Cliente                         Servidor
  │                                │
  │── LOGIN|João ─────────────────►│
  │◄─ OK|Bem-vindo ────────────────│
  │                                │
  │── REQUISITAR|uuid-1234 ───────►│
  │◄─ OK|Livro requisitado ────────│
  │                                │
  │◄─ NOTIFICACAO|Livro disponível ─│  ← push assíncrono
  │◄─ ATUALIZAR ───────────────────│  ← broadcast a todos
```

**Pontos relevantes:**
- `ServerSocket` aguarda conexões na **porta 8080**
- Protocolo de **texto simples**: `COMANDO|campo1|campo2\n`
- Independente da linguagem do cliente (testável com `telnet`)
- Funciona como **middleware de abstracção** — o cliente não conhece a implementação do servidor, apenas o contrato do protocolo

---

## SLIDE 4 — Concorrência: ExecutorService + synchronized

**"Como o servidor atende vários clientes ao mesmo tempo?"**

```java
// 1. Thread pool — uma thread por cliente
ExecutorService executor = Executors.newCachedThreadPool();
while (true) {
    Socket socket = serverSocket.accept();
    executor.submit(new GestorClientes(socket, gestorLivros));
}

// 2. Recurso partilhado protegido com synchronized
public synchronized String requisitar(String id, GestorClientes cliente) {
    Livro livro = buscarPorId(id);
    if (livro.isDisponivel()) {
        livro.setEstudante(cliente.getNome());
        return "OK|Livro requisitado com sucesso";
    } else {
        livro.adicionarFila(cliente);
        return "OK|Adicionado à fila (posição " + livro.posicaoNaFila(cliente) + ")";
    }
}
```

**Pontos relevantes:**
- `ExecutorService.newCachedThreadPool()` — thread por cliente, sem bloquear os outros
- `synchronized` no `GestorLivros` — **secção crítica**: impossível dois clientes requisitarem o mesmo livro em simultâneo
- Todos os métodos de escrita são sincronizados; os de leitura pura não precisam

---

## SLIDE 5 — Notificações Assíncronas (Push)

**"O servidor avisa o cliente sem que ele tenha perguntado"**

```
                    Sem push (polling):          Com push (este sistema):
Cliente pergunta    "Há novidades?"          →   Servidor envia quando há
                    "Há novidades?"          →   novidade: NOTIFICACAO|...
                    "Há novidades?"   ← ineficiente
```

**Implementação — NotificacaoService (thread dedicada no cliente):**

```java
public void run() {
    String msg;
    while ((msg = entrada.readLine()) != null) {
        if (msg.startsWith("NOTIFICACAO|")) {
            Platform.runLater(() -> controlador.mostrarNotificacao(msg));
        } else if (msg.equals("ATUALIZAR")) {
            Platform.runLater(() -> controlador.atualizarLista());
        } else {
            cliente.receberResposta(msg);  // desbloqueia o enviar() síncrono
        }
    }
}
```

**Pontos relevantes:**
- Thread **única** a ler o socket — evita condição de corrida nos bytes
- `Platform.runLater()` — JavaFX exige que alterações à GUI sejam feitas na **JavaFX Application Thread**
- `BlockingQueue<String>` — sincronização entre a thread de notificações e o código que espera respostas
- Resultado: actualização **em tempo real** em todos os clientes ligados

---

# NOTAS PARA O ORADOR

**Pergunta esperada:** *"Porque usaram protocolo de texto e não serialização de objectos Java?"*
→ "Mais fácil de depurar — testamos com telnet. E é independente da versão das classes."

**Pergunta esperada:** *"Como garantem que dois clientes não requisitam o mesmo livro?"*
→ "O método `requisitar` é `synchronized`. A segunda thread fica bloqueada até a primeira terminar."

**Pergunta esperada:** *"O que é o Platform.runLater?"*
→ "JavaFX só permite tocar na interface gráfica na sua thread principal. O `runLater` agenda a actualização nessa thread a partir de outra thread."
