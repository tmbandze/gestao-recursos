# Gestão de Recursos — Partilha de Livros

Sistema distribuído de gestão e partilha de livros entre estudantes.  
Trabalho Prático A — Sistemas Distribuídos

---

## Início Rápido

```bash
# 1. Compilar e iniciar o servidor
cd servidor
mvn clean package
java -jar target/servidor.jar

# 2. Iniciar o cliente (noutra janela)
cd cliente
mvn javafx:run
```

Para testar múltiplos clientes, abrir várias janelas de terminal e executar `mvn javafx:run` em cada uma.

---

## Funcionalidades

- Inserção, consulta, requisição e devolução de livros
- Múltiplos clientes em simultâneo (servidor com threads)
- Fila de espera automática para livros indisponíveis
- Notificações em tempo real quando um livro fica disponível
- Actualização automática da lista em todos os clientes
- Pesquisa por título, autor ou categoria
- Log centralizado de todas as operações com histórico consultável
- Relatório de estatísticas do sistema
- Painel de administração (utilizadores online, estado dos livros, log)

---

## Conceitos de SD Demonstrados

| Conceito | Implementação |
|----------|--------------|
| Arquitectura Cliente-Servidor | ServerSocket + Clientes JavaFX |
| Comunicação via Sockets TCP | Java `ServerSocket` / `Socket` |
| Concorrência | Thread por cliente com `ExecutorService` |
| Comunicação assíncrona | Notificações push do servidor |
| Transparência de localização | Cliente acede por HOST:PORTA |
| Transparência de falha | Reconexão automática |
| Propagação de eventos | Broadcast `ATUALIZAR` a todos os clientes |

---

## Documentação

Ver pasta `docs/` para documentação completa:

- [INDEX.md](docs/INDEX.md) — Índice e estrutura do projecto
- [CONTEXT.md](docs/CONTEXT.md) — Contexto e requisitos
- [ARQUITETURA.md](docs/ARQUITETURA.md) — Design técnico
- [SKILLS.md](docs/SKILLS.md) — Conceitos de SD e guias Java
- [PROTOCOLO.md](docs/PROTOCOLO.md) — Protocolo de comunicação
- [INSTALACAO.md](docs/INSTALACAO.md) — Como instalar e executar
- [MANUAL.md](docs/MANUAL.md) — Como usar a aplicação
- [APRESENTACAO.md](docs/APRESENTACAO.md) — Guia para a apresentação

---

## Grupo

| Membro | Responsabilidade |
|--------|-----------------|
| Pessoa 1 | Servidor, lógica de negócio, persistência |
| Pessoa 2 | Cliente, interface gráfica, notificações |

**Disciplina:** Sistemas Distribuídos  
**Linguagem:** Java 17+ + JavaFX 23 + Gson 2.10.1 + Maven  
**Versão:** 1.0 — Maio 2026
