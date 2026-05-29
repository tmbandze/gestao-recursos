# INSTALACAO.md — Compilação e Execução

## Pré-requisitos

| Software | Versão Mínima | Como verificar |
|----------|--------------|----------------|
| Java JDK | 17 | `java -version` |
| Maven | 3.8+ | `mvn -version` |
| Browser moderno | — | Chrome, Firefox, Edge (qualquer versão recente) |

> **Nota:** não é necessário instalar JavaFX, Node.js, nem nenhum outro software.  
> O cliente é uma página web servida pelo próprio servidor.

---

## 1. Obter o Projecto

```bash
git clone https://github.com/tmbandze/gestao-recursos.git
cd gestao-recursos
```

---

## 2. Compilar

```bash
cd servidor
mvn clean package
```

Gera `servidor/target/servidor.jar` (fat JAR com todas as dependências incluídas).

**Primeira compilação:** pode demorar 2-3 minutos enquanto o Maven descarrega as dependências. Compilações seguintes são muito mais rápidas.

---

## 3. Executar o Servidor

```bash
java -jar target/servidor.jar
```

Deverá aparecer no terminal:

```
╔══════════════════════════════════════════════════════════════╗
║  Servidor de Gestão de Recursos — Biblioteca Digital         ║
║  HTTP/SSE  : http://localhost:8080                           ║
║  TCP       : localhost:9090                                  ║
║  Rede      : http://192.168.x.x:8080                        ║
╚══════════════════════════════════════════════════════════════╝
[MONITOR] Monitor de prazos iniciado (verifica de hora em hora).
```

---

## 4. Aceder à Aplicação

Abrir o browser em **http://localhost:8080**

### Conta admin pré-criada

| Campo | Valor |
|-------|-------|
| Email | `admin@biblioteca.local` |
| Password | `admin123` |

> A conta admin é criada automaticamente se não existir nenhuma conta com o nome "admin".

### Criar conta de estudante

Clicar em **Criar conta** na página inicial e preencher nome, email e password (mínimo 6 caracteres).

---

## 5. Aceder de Outro Computador na Mesma Rede

O servidor exibe o seu IP de rede local no arranque (`Rede: http://192.168.x.x:8080`).  
Qualquer computador na mesma rede Wi-Fi/Ethernet pode abrir esse endereço no browser.

Para encontrar manualmente o IP:
```bash
# Windows
ipconfig

# Linux / macOS
ip addr   # ou ifconfig
```

---

## 6. Estrutura de Dados

O servidor cria automaticamente a pasta `data/` com:

```
servidor/data/
├── livros.json          # Catálogo de livros
├── utilizadores.json    # Contas de utilizadores (passwords em hash)
├── emprestimos.json     # Histórico de empréstimos
├── chat.json            # Mensagens do chat
├── email-config.json    # Configuração SMTP (criado após configurar)
├── log.txt              # Log de operações com timestamp
├── pdfs/                # Ficheiros PDF enviados
└── capas/               # Capas geradas automaticamente dos PDFs
```

Estes ficheiros persistem entre reinícios. Para começar do zero, apagar a pasta `data/`.

---

## 7. Configurar Notificações por Email (opcional)

1. Fazer login como **admin**
2. No painel lateral → **📧 Notificações por Email**
3. Preencher:
   - SMTP: `smtp.gmail.com`
   - Porta: `587`
   - Utilizador: endereço Gmail
   - Password: [App Password do Google](https://myaccount.google.com/apppasswords) *(requer 2FA activo)*
   - Marcar **Activar**
4. Clicar **💾 Guardar** e depois **📤 Enviar teste**

---

## 8. Resolução de Problemas

### `Address already in use: 8080`
A porta está ocupada por outro processo.
```bash
# Windows — encontrar o PID
netstat -ano | findstr :8080
taskkill /PID <PID> /F

# Linux / macOS
lsof -i :8080
kill -9 <PID>
```

### Browser mostra página em branco ou erro 404
Verificar se o servidor foi iniciado com `java -jar target/servidor.jar` a partir da pasta `servidor/` (não a partir da raiz do projecto). O servidor precisa de encontrar `data/` no directório de trabalho.

### Encoding incorrecto nos CSVs exportados (acentuação)
Os CSVs incluem BOM UTF-8. Se o Excel mostrar caracteres incorrectos, abrir o Excel, usar **Dados → De Texto/CSV** e seleccionar `UTF-8` como codificação.

### `BUILD FAILURE` no Maven
```bash
# Limpar cache de dependências corrompidas
mvn dependency:purge-local-repository
mvn clean package
```
