# SLIDES — Sistema de Gestão de Recursos
## Guia slide a slide para PowerPoint / Google Slides

> **Dica de uso:** Cada secção `---` é um slide. O título em `##` é o título do slide.
> Copiar o conteúdo para PowerPoint ou Google Slides e adicionar o diagrama/imagem indicado.

---

## SLIDE 1 — Capa

**Sistema de Gestão de Recursos**
*Partilha de Livros entre Estudantes*

Sistemas Distribuídos — Trabalho Prático A
Ano lectivo 2025/2026

*(fundo escuro, texto branco; logótipo da instituição no canto)*

---

## SLIDE 2 — O Problema

**Problema:**
Estudantes precisam de partilhar livros de forma organizada, sem conflitos, em tempo real.

**Solução:**
Sistema distribuído com servidor central e N clientes — qualquer aluno acede de qualquer máquina.

*(ícone de livros + ícone de rede)*

---

## SLIDE 3 — Arquitectura do Sistema

*(inserir diagrama ASCII ou imagem — ver ARQUITETURA.md secção 1)*

```
Cliente A  ──────┐
                 │  TCP porta 8080
Cliente B  ──────┼──────► SERVIDOR
                 │         ├── GestorLivros
Cliente N  ──────┘         ├── BaseDados (JSON)
                           └── Logger (log.txt)
```

**Arquitectura cliente-servidor:**
- Servidor: autoridade única sobre os dados
- Clientes: interfaces gráficas independentes (JavaFX)

---

## SLIDE 4 — Stack Tecnológico

| Componente | Tecnologia |
|-----------|-----------|
| Linguagem | Java 17+ |
| Interface gráfica | JavaFX 23 |
| Comunicação | Sockets TCP (ServerSocket / Socket) |
| Persistência | Gson 2.10.1 → `livros.json` |
| Build | Apache Maven (multi-módulo) |
| Protocolo | Texto simples sobre TCP (`COMANDO\|campo`) |

---

## SLIDE 5 — Funcionalidades (Demo a seguir)

✓ Inserção, consulta, requisição e devolução de livros  
✓ Múltiplos clientes simultâneos  
✓ **Fila de espera automática** para livros indisponíveis  
✓ **Notificações em tempo real** (push do servidor)  
✓ Actualização automática em todos os clientes  
✓ Pesquisa e filtros  
✓ Histórico de operações  
✓ **Painel de administração** (utilizadores online, livros, log)

---

## SLIDE 6 — DEMO AO VIVO

*(slide em branco com texto grande: "DEMONSTRAÇÃO")*

**Ordem da demo:**
1. Dois clientes ligados — mesmo catálogo
2. Inserir livro → aparece nos dois clientes (broadcast)
3. Fila de espera → notificação automática
4. Pesquisa e filtros
5. Painel Admin

---

## SLIDE 7 — Conceito 1: Concorrência

**"O servidor cria uma thread para cada cliente"**

```java
// ExecutorService com thread pool dinâmico
ExecutorService executor = Executors.newCachedThreadPool();

while (true) {
    Socket socket = serverSocket.accept();
    GestorClientes handler = new GestorClientes(socket, gestorLivros);
    executor.submit(handler);  // lança em thread dedicada
}
```

**Resultado:** 10 clientes ligados = 10 threads independentes.
Nenhum cliente bloqueia os outros.

---

## SLIDE 8 — Conceito 2: Gestão de Recursos Partilhados

**"synchronized evita dois clientes requisitarem o mesmo livro"**

```java
public synchronized String requisitar(String id, GestorClientes cliente) {
    Livro livro = buscarPorId(id);
    if (livro.isDisponivel()) {
        livro.setEstudante(cliente.getNome());
        return "OK|Livro requisitado com sucesso";
    } else {
        livro.adicionarFila(cliente);
        return "OK|Adicionado à fila (posição " + livro.posicao(cliente) + ")";
    }
}
```

**Resultado:** Impossível dois clientes requisitarem o mesmo livro simultaneamente.

---

## SLIDE 9 — Conceito 3: Comunicação Assíncrona

**"O servidor notifica o cliente sem que ele tenha pedido"**

```
Sem assíncrono (polling):           Com assíncrono (push):
  Cliente pergunta cada 1s      →     Servidor envia quando há novidade
  "Há novidades?"               →     "NOTIFICACAO|O livro X é teu!"
  "Há novidades?"
  "Há novidades?"  ← ineficiente
```

**Implementação:** `NotificacaoService` — thread dedicada sempre à escuta no cliente.

*(conceito do programa: estilo baseado em eventos)*

---

## SLIDE 10 — Conceito 4: Transparência de Localização

**"O cliente não sabe onde os dados estão"**

```properties
# config.properties — muda o servidor sem tocar no código
servidor.host=192.168.1.10
servidor.porta=8080
```

O cliente usa sempre os mesmos comandos (`LISTAR`, `REQUISITAR`, ...) independentemente de o servidor estar em `localhost` ou numa máquina remota.

*(conceito do programa: transparência de localização)*

---

## SLIDE 11 — Conceito 5: Middleware

**"O protocolo de texto é o nosso middleware"**

```
INSERIR|Tanenbaum|Andrew|Sistemas Distribuídos
LISTAR
REQUISITAR|uuid-1234
NOTIFICACAO|O livro X está disponível para si!
```

Abstrai a comunicação em rede. O cliente não conhece a implementação do servidor — só o contrato do protocolo.

*(conceito do programa: middleware como camada de abstracção)*

---

## SLIDE 12 — Fluxo de Notificação (ponto alto)

*(diagrama de sequência — fazer no PowerPoint)*

```
João              Servidor              Maria
 │                   │                   │
 │── REQUISITAR ────►│                   │
 │◄─ OK (livro teu) ─│                   │
 │                   │◄── REQUISITAR ────│
 │                   │─── OK (fila 1) ──►│
 │                   │                   │
 │── DEVOLVER ──────►│                   │
 │◄─ OK ─────────────│                   │
 │                   │── NOTIFICACAO ───►│  ← push assíncrono!
 │                   │──── ATUALIZAR ───►│  ← broadcast
 │◄──────────────────│── ATUALIZAR       │
```

---

## SLIDE 13 — Decisões de Design

| Decisão | Alternativa | Porque escolhemos |
|---------|------------|------------------|
| Protocolo de texto | Serialização Java | Mais fácil de depurar e explicar |
| JSON (Gson) | Base de dados relacional | Sem instalação extra; ficheiro legível |
| Thread por cliente | Reactor pattern | Mais simples; adequado à escala académica |
| Leitor único do socket | Dois leitores concorrentes | Evita race condition na leitura de bytes |

---

## SLIDE 14 — Limitações e Trabalho Futuro

**Limitações conhecidas (fora do âmbito):**
- Sem autenticação por palavra-passe
- Sem encriptação (TLS)
- Servidor é ponto único de falha
- Filas de espera não persistidas (perdem-se ao reiniciar)

**Extensões possíveis:**
- Replicação do servidor (fault tolerance)
- Autenticação com base de dados
- API REST em vez de protocolo TCP próprio

---

## SLIDE 15 — Conclusão

**O sistema demonstra:**

| Conceito SD | Implementação |
|-------------|--------------|
| Arquitectura C/S | ServerSocket + N clientes JavaFX |
| Concorrência | ExecutorService + thread por cliente |
| Sincronização | `synchronized` em GestorLivros |
| Comunicação assíncrona | NotificacaoService + push |
| Transparência de localização | config.properties |
| Middleware | Protocolo de texto TCP |
| Propagação de eventos | Broadcast ATUALIZAR |

**Todos os conceitos do programa são demonstráveis ao vivo.**

---

## SLIDE 16 — Obrigado / Perguntas

**Sistema de Gestão de Recursos**

Código disponível para consulta.

*Perguntas?*

---

# NOTAS PARA A APRESENTAÇÃO

## Perguntas esperadas e respostas

**"Como garantem que dois clientes não requisitam o mesmo livro?"**
→ "O método `requisitar` é `synchronized`. A Thread B fica bloqueada enquanto a Thread A executa. Só uma pode requisitar de cada vez."

**"O que é a NotificacaoService?"**
→ "É uma thread que corre em paralelo com a interface gráfica, sempre à escuta no socket. Quando o servidor envia uma notificação, ela captura e actualiza a interface via `Platform.runLater()` — que é obrigatório em JavaFX para actualizar a UI fora da thread principal."

**"Por que usaram protocolo de texto e não serialização de objectos?"**
→ "Mais fácil de depurar — conseguimos testar com telnet. E independente da versão das classes Java."

**"O que acontece se o servidor cair?"**
→ "O cliente detecta o erro de IO, mostra uma mensagem de erro na interface e pára de aceitar operações. A reconexão automática com retry já está preparada na classe Cliente."

**"Por que não usaram uma base de dados?"**
→ "Para o âmbito académico, JSON é suficiente e mais simples de demonstrar — abrimos o ficheiro na apresentação e vê-se directamente os dados."
