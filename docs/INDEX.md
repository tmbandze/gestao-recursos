# Sistema de Gestão de Recursos — Índice Geral

**Projecto:** Partilha de Livros entre Estudantes  
**Disciplina:** Sistemas Distribuídos  
**Grupo:** 2 pessoas  
**Linguagem:** Java (ServerSocket + JavaFX)  
**Versão:** 1.0 | Maio 2026

---

## Navegação Rápida

| Documento | O que encontras | Para quem |
|-----------|----------------|-----------|
| [CONTEXT.md](CONTEXT.md) | Contexto académico, objectivos, requisitos | Todos |
| [ARQUITETURA.md](ARQUITETURA.md) | Design do sistema, protocolo, diagramas | Desenvolvedores |
| [SKILLS.md](SKILLS.md) | Conceitos de SD aplicados, guias técnicos | Desenvolvedores |
| [PROTOCOLO.md](PROTOCOLO.md) | Comandos cliente-servidor, exemplos | Desenvolvedores |
| [INSTALACAO.md](INSTALACAO.md) | Como compilar e executar o sistema | Utilizadores |
| [MANUAL.md](MANUAL.md) | Como usar a aplicação passo a passo | Utilizadores |
| [APRESENTACAO.md](APRESENTACAO.md) | Guia para a apresentação à docente | Grupo |
| [RELATORIO.md](RELATORIO.md) | Relatório escrito completo (pronto para Word/PDF) | Grupo |
| [SLIDES.md](SLIDES.md) | Conteúdo slide a slide para PowerPoint/Google Slides | Grupo |

---

## Estrutura do Projecto

O projecto é um **multi-módulo Maven** com dois módulos independentes (`servidor` e `cliente`). Cada módulo tem o seu próprio `pom.xml` e produz um JAR executável separado.

```
gestao-recursos/
├── servidor/                          # Módulo Maven — servidor TCP
│   ├── pom.xml                        # Build: servidor.jar (maven-shade-plugin)
│   └── src/main/java/
│       ├── servidor/
│       │   ├── Servidor.java          # Ponto de entrada, ServerSocket na porta 8080
│       │   ├── GestorClientes.java    # Thread por cliente conectado
│       │   ├── GestorLivros.java      # Lógica de negócio (requisições, filas)
│       │   ├── BaseDados.java         # Persistência em livros.json (Gson)
│       │   └── Logger.java            # Log de operações com timestamp
│       └── shared/
│           ├── Protocolo.java         # Constantes do protocolo TCP
│           └── Livro.java             # Modelo de dados partilhado
│
├── cliente/                           # Módulo Maven — cliente JavaFX
│   ├── pom.xml                        # Build: javafx-maven-plugin
│   └── src/main/
│       ├── java/
│       │   ├── cliente/
│       │   │   ├── MainApp.java               # Ponto de entrada JavaFX
│       │   │   ├── Cliente.java               # Conexão TCP + BlockingQueue
│       │   │   ├── NotificacaoService.java    # Leitor único do socket
│       │   │   ├── ControladorPrincipal.java  # Lógica da janela principal
│       │   │   └── AdminPanel.java            # Painel de administração
│       │   └── shared/
│       │       ├── Protocolo.java             # (cópia partilhada)
│       │       └── Livro.java
│       └── resources/
│           ├── main.fxml              # Layout da interface gráfica
│           └── config.properties     # servidor.host e servidor.porta
│
├── servidor/data/                     # Criado em runtime pelo servidor
│   ├── livros.json                    # Base de dados de livros
│   └── log.txt                        # Registo de operações
│
├── docs/
│   ├── INDEX.md                       # Este ficheiro
│   ├── ARQUITETURA.md
│   ├── CONTEXT.md
│   ├── SKILLS.md
│   ├── PROTOCOLO.md
│   ├── INSTALACAO.md
│   ├── MANUAL.md
│   └── APRESENTACAO.md
└── README.md
```

---

## Divisão de Trabalho

| Módulo | Responsável | Estado |
|--------|-------------|--------|
| Servidor TCP + threads | Pessoa 1 | [ ] |
| Base de dados + persistência | Pessoa 1 | [ ] |
| Lógica de requisições e filas | Pessoa 1 | [ ] |
| Sistema de notificações (servidor) | Pessoa 1 | [ ] |
| Log de operações | Pessoa 1 | [ ] |
| Cliente TCP + protocolo | Pessoa 2 | [ ] |
| Interface gráfica JavaFX | Pessoa 2 | [ ] |
| Actualização automática da lista | Pessoa 2 | [ ] |
| Painel de notificações | Pessoa 2 | [ ] |
| Relatório de utilização | Pessoa 2 | [ ] |
| Documentação + relatório final | Ambos | [ ] |

---

## Fases do Projecto

```
Fase 1 — Comunicação base        Semana 1
Fase 2 — Lógica de negócio       Semana 2
Fase 3 — Features SD              Semana 3
Fase 4 — Interface + polish       Semana 4
Fase 5 — Testes + apresentação    Semana 5
```

---

## Conceitos de SD Implementados

- Arquitectura cliente-servidor com múltiplos clientes simultâneos
- Comunicação via Sockets TCP com protocolo de texto próprio
- Concorrência com `ExecutorService` (thread pool) + threads por cliente
- Comunicação assíncrona — notificações push do servidor para os clientes
- Leitor único do socket (`NotificacaoService`) com `BlockingQueue` para sincronização
- Sincronização com `synchronized` em todos os recursos partilhados
- Transparência de localização — `config.properties` abstrai host/porta
- Log de operações persistido em ficheiro com timestamp
- Painel de administração em tempo real (utilizadores, livros, log)
- Propagação de eventos via broadcast `ATUALIZAR` a todos os clientes
