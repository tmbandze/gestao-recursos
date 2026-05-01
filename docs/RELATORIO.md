# Relatório — Sistema de Gestão de Recursos
## Trabalho Prático A — Sistemas Distribuídos

**Disciplina:** Sistemas Distribuídos  
**Ano lectivo:** 2025/2026  
**Trabalho:** Prático A — Gestão de Recursos  
**Linguagem:** Java 17+ com JavaFX 23 e Gson 2.10.1  
**Ferramenta de build:** Apache Maven (multi-módulo)

---

## 1. Introdução

O presente relatório descreve o desenvolvimento de um sistema distribuído de gestão e partilha de livros entre estudantes. O sistema foi implementado como trabalho prático da disciplina de Sistemas Distribuídos, correspondendo ao enunciado A (Gestão de Recursos).

O objectivo central foi construir uma aplicação cliente-servidor que permita a múltiplos utilizadores gerir simultaneamente um catálogo de livros, com suporte a requisições, devoluções, fila de espera automática e notificações em tempo real. Adicionalmente, foram implementadas funcionalidades de administração, pesquisa, filtragem e histórico de operações, cobrindo um conjunto alargado de conceitos do programa da disciplina.

### 1.1 Motivação da Escolha

O grupo optou pelo trabalho A em detrimento do trabalho B (sistema de chat) com o objectivo de demonstrar que os conceitos de Sistemas Distribuídos se aplicam igualmente a sistemas de gestão de recursos — com a vantagem de um domínio de negócio mais rico e com maior valor prático.

---

## 2. Arquitectura do Sistema

### 2.1 Visão Geral

O sistema segue a arquitectura **cliente-servidor** clássica, composta por:

- **Um servidor central** que gere todos os dados e contém a lógica de negócio
- **N clientes independentes** com interface gráfica JavaFX que comunicam com o servidor via TCP

```
Cliente A (JavaFX) ──────────────────────────┐
                        TCP (porta 8080)      │
Cliente B (JavaFX) ──────────────────────────┼──► SERVIDOR CENTRAL
                                             │     ├── GestorLivros (sincronizado)
Cliente N (JavaFX) ──────────────────────────┘     ├── BaseDados (livros.json)
                                                   └── Logger (log.txt)
```

### 2.2 Estrutura de Módulos Maven

O projecto é organizado como um **multi-módulo Maven** com dois módulos independentes:

| Módulo | Responsabilidade | JAR |
|--------|-----------------|-----|
| `servidor/` | Lógica de negócio, persistência, protocolo | `servidor.jar` (fat jar) |
| `cliente/` | Interface gráfica, conexão TCP, notificações | executado via `mvn javafx:run` |

Ambos os módulos partilham as classes `shared.Protocolo` e `shared.Livro`, que definem o contrato de comunicação.

### 2.3 Componentes do Servidor

| Classe | Responsabilidade |
|--------|-----------------|
| `Servidor.java` | `ServerSocket` na porta 8080; `ExecutorService` (thread pool); lista de clientes activos |
| `GestorClientes.java` | Thread dedicada por cliente; interpreta protocolo; delega para `GestorLivros` |
| `GestorLivros.java` | Lógica de negócio com todos os métodos `synchronized`; gestão de filas de espera |
| `BaseDados.java` | Serialização/deserialização com Gson para `data/livros.json` |
| `Logger.java` | Registo de operações com timestamp em `data/log.txt` |

### 2.4 Componentes do Cliente

| Classe | Responsabilidade |
|--------|-----------------|
| `MainApp.java` | Ponto de entrada JavaFX; carrega `main.fxml` |
| `Cliente.java` | Conexão TCP; `BlockingQueue` para respostas síncronas; retry automático |
| `NotificacaoService.java` | Leitor único do socket; encaminha mensagens assíncronas e síncronas |
| `ControladorPrincipal.java` | Lógica da janela principal; liga botões a comandos do servidor |
| `AdminPanel.java` | Painel de administração (utilizadores online, livros, log) |

---

## 3. Protocolo de Comunicação

A comunicação usa **texto simples sobre TCP**. Cada mensagem é uma linha terminada em `\n`, com campos separados por `|`.

### 3.1 Formato Geral

```
COMANDO|campo1|campo2\n
```

### 3.2 Comandos Principais

| Direcção | Comando | Exemplo |
|----------|---------|---------|
| C → S | `LOGIN\|nome` | `LOGIN\|João` |
| C → S | `INSERIR\|titulo\|autor\|cat` | `INSERIR\|Tanenbaum\|Andrew\|SD` |
| C → S | `REQUISITAR\|id` | `REQUISITAR\|uuid-1234` |
| C → S | `DEVOLVER\|id` | `DEVOLVER\|uuid-1234` |
| C → S | `LISTAR` | `LISTAR` |
| C → S | `PESQUISAR\|termo` | `PESQUISAR\|redes` |
| S → C | `OK\|msg` | `OK\|Livro requisitado com sucesso` |
| S → C | `LIVROS\|...` | `LIVROS\|id,titulo,autor,cat,estado;...` |
| S → C | `NOTIFICACAO\|texto` | `NOTIFICACAO\|O livro X está disponível para si!` |
| S → C | `ATUALIZAR` | `ATUALIZAR` *(broadcast a todos os clientes)* |

### 3.3 Fluxo de Notificação (Feature Principal)

```
1. Cliente A:  REQUISITAR|uuid-1     →  OK|Livro requisitado
2. Cliente B:  REQUISITAR|uuid-1     →  OK|Adicionado à fila (posição 1)
3. Cliente A:  DEVOLVER|uuid-1       →  OK|Devolvido
   Servidor    →  Cliente B:  NOTIFICACAO|O livro X foi reservado para si!
   Servidor    →  Todos:      ATUALIZAR
```

---

## 4. Conceitos de Sistemas Distribuídos Implementados

### 4.1 Arquitectura Cliente-Servidor

O servidor é o único ponto de autoridade sobre os dados. Os clientes não guardam estado local — toda a informação é obtida do servidor por pedido explícito ou por notificação push.

### 4.2 Comunicação via Sockets TCP

A comunicação é feita com `ServerSocket` / `Socket` da API padrão do Java. O protocolo TCP garante entrega ordenada e sem perdas, adequado para operações que exigem confirmação (requisição, devolução).

### 4.3 Concorrência com Thread Pool

O servidor usa `Executors.newCachedThreadPool()` para criar uma thread por cliente. Cada `GestorClientes` corre de forma independente, permitindo que múltiplos clientes operem em simultâneo sem que um bloqueie o outro.

### 4.4 Sincronização de Recursos Partilhados

O `GestorLivros` é o recurso crítico partilhado por todas as threads. Todos os métodos que modificam estado (`requisitar`, `devolver`, `inserir`) são declarados `synchronized`, garantindo que apenas uma thread executa de cada vez e evitando condições de corrida.

```java
public synchronized String requisitar(String id, GestorClientes solicitante) {
    Livro livro = buscarPorId(id);
    if (livro.isDisponivel()) {
        livro.setEstuданteActual(solicitante.getNome());
        baseDados.guardar();
        servidor.notificarTodos(Protocolo.ATUALIZAR, solicitante);
        return Protocolo.OK + "|Livro requisitado com sucesso";
    } else {
        livro.adicionarFila(solicitante);
        return Protocolo.OK + "|Adicionado à fila de espera (posição "
               + livro.posicaoNaFila(solicitante) + ")";
    }
}
```

### 4.5 Comunicação Assíncrona (Notificações Push)

O cliente tem uma thread dedicada — `NotificacaoService` — que é o **único leitor** do `InputStream` do socket. Esta thread mantém-se à escuta permanente e encaminha as mensagens:

- Mensagens `NOTIFICACAO|...` → `Platform.runLater()` para actualizar a GUI
- Mensagem `ATUALIZAR` → recarrega a lista de livros em todos os clientes
- Qualquer outra mensagem → coloca na `BlockingQueue` para desbloquear o `enviar()` síncrono

Este design evita o padrão de *polling* (o cliente perguntar repetidamente "houve mudanças?") e implementa **comunicação baseada em eventos**.

### 4.6 Transparência de Localização

O cliente não sabe onde os dados estão fisicamente armazenados. As configurações de host e porta são lidas de `config.properties`:

```properties
servidor.host=localhost
servidor.porta=8080
```

Para mudar o servidor de máquina, basta alterar este ficheiro — o código do cliente não precisa de ser modificado.

### 4.7 Middleware — Protocolo de Texto

O protocolo de comandos (`INSERIR|...`, `LISTAR`, `OK|...`) funciona como middleware simplificado: abstrai a comunicação em rede e oferece uma interface uniforme entre cliente e servidor.

---

## 5. Decisões de Design

### 5.1 Leitor Único do Socket (NotificacaoService)

Uma das decisões mais importantes foi fazer da `NotificacaoService` o **único leitor** do `InputStream`. Se existissem dois leitores, haveria condição de corrida sobre quais bytes cada um leria. A solução usa uma `BlockingQueue<String>` no `Cliente`: a `NotificacaoService` deposita respostas síncronas na fila, e o método `enviar()` bloqueia até receber a sua resposta.

### 5.2 Protocolo de Texto vs. Serialização de Objectos

Optou-se por protocolo de texto em vez de serialização binária de objectos Java porque:
- É mais fácil de depurar (pode-se testar com `telnet` ou `netcat`)
- É independente da versão das classes Java
- É mais fácil de explicar e documentar

### 5.3 Persistência em JSON vs. Base de Dados Relacional

O uso de Gson com ficheiros JSON justifica-se pelo âmbito académico do projecto: não requer instalação de servidor de base de dados, o ficheiro é legível e pode ser mostrado na apresentação.

### 5.4 Maven Multi-Módulo

A separação em dois módulos Maven independentes (`servidor/` e `cliente/`) reflecte a separação física dos processos: o servidor e o cliente correm em JVMs distintas (potencialmente em máquinas distintas), pelo que faz sentido que tenham builds independentes.

---

## 6. Funcionalidades Implementadas

| Funcionalidade | Estado |
|----------------|--------|
| Login com nome de estudante | ✓ Implementado |
| Listagem de todos os livros | ✓ Implementado |
| Inserção de livros | ✓ Implementado |
| Requisição de livro disponível | ✓ Implementado |
| Devolução de livro | ✓ Implementado |
| Fila de espera automática (FIFO) | ✓ Implementado |
| Notificação push ao próximo da fila | ✓ Implementado |
| Actualização automática em todos os clientes | ✓ Implementado |
| Pesquisa por título, autor ou categoria | ✓ Implementado |
| Filtro por estado (todos / disponíveis / requisitados) | ✓ Implementado |
| Detalhes do livro com fila de espera | ✓ Implementado |
| Relatório de estatísticas | ✓ Implementado |
| Histórico de operações (últimas 50) | ✓ Implementado |
| Painel de administração (utilizadores, livros, log) | ✓ Implementado |
| Configuração de host/porta via ficheiro | ✓ Implementado |
| Persistência em JSON | ✓ Implementado |
| Log de operações com timestamp | ✓ Implementado |
| Múltiplos clientes simultâneos | ✓ Implementado |

---

## 7. Limitações Conhecidas

| Limitação | Justificação |
|-----------|-------------|
| Sem autenticação por palavra-passe | Fora do âmbito do enunciado |
| Sem encriptação TLS | Rede local/académica — sem dados sensíveis |
| Servidor como ponto único de falha | Trade-off aceite para âmbito académico |
| Filas de espera em memória (não persistidas) | Reiniciar o servidor limpa as filas |
| Sem suporte a múltiplos servidores | Âmbito académico |

---

## 8. Conclusão

O sistema implementado cobre todos os requisitos do enunciado base e acrescenta um conjunto de funcionalidades que demonstram explicitamente os conceitos do programa de Sistemas Distribuídos: concorrência com thread pool, sincronização de recursos partilhados, comunicação assíncrona baseada em eventos, transparência de localização e middleware de protocolo.

A decisão de implementar o trabalho A (gestão de recursos) em vez do B (chat) permitiu explorar um domínio de negócio mais rico — com filas de espera, notificações selectivas e painel de administração — mantendo a mesma base técnica de Sockets TCP, threads e JavaFX.

---

## Anexo A — Como Compilar e Executar

```bash
# Terminal 1 — Servidor
cd servidor
mvn clean package
java -jar target/servidor.jar

# Terminal 2 — Cliente (repetir para múltiplos clientes)
cd cliente
mvn javafx:run
```

## Anexo B — Dependências

| Biblioteca | Versão | Uso |
|------------|--------|-----|
| Java SE | 17+ | Linguagem base, ServerSocket, threads |
| JavaFX | 23 | Interface gráfica |
| Gson | 2.10.1 | Serialização JSON |
| Apache Maven | 3.8+ | Gestão de build e dependências |
