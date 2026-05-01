# INSTALACAO.md — Compilação e Execução

## Pré-requisitos

| Software | Versão Mínima | Como verificar |
|----------|--------------|----------------|
| Java JDK | 17 | `java -version` |
| JavaFX SDK | 17 | Incluído no pom.xml via Maven |
| Maven | 3.8+ | `mvn -version` |

---

## 1. Clonar / Obter o Projecto

```bash
# Se estiver no GitLab
git clone https://gitlab.com/grupo/gestao-recursos.git
cd gestao-recursos

# Ou descompactar o ZIP entregue
unzip gestao-recursos.zip
cd gestao-recursos
```

---

## 2. Compilar

```bash
mvn clean package
```

Isto gera dois ficheiros JAR na pasta `target/`:
- `servidor.jar` — o servidor
- `cliente.jar` — o cliente

---

## 3. Executar

### Passo 1 — Iniciar o Servidor (sempre primeiro)

```bash
# Numa janela de terminal
java -jar target/servidor.jar
```

Deverá aparecer:
```
[INFO] Servidor iniciado na porta 8080
[INFO] A aguardar conexões...
```

### Passo 2 — Iniciar o(s) Cliente(s)

```bash
# Noutra janela de terminal (ou noutro computador)
java -jar target/cliente.jar
```

A interface gráfica abrirá e pedirá o nome do estudante.

Para testar múltiplos clientes em simultâneo, abrir múltiplas janelas de terminal e executar o cliente em cada uma.

---

## 4. Configuração

Por defeito, o cliente conecta a `localhost:8080`. Para alterar (ex: servidor noutro computador):

Editar `src/main/resources/config.properties`:
```properties
servidor.host=192.168.1.100
servidor.porta=8080
```

---

## 5. Estrutura de Dados

O servidor cria automaticamente a pasta `data/` com:
- `livros.json` — base de dados de livros
- `log.txt` — log de operações

Estes ficheiros persistem entre execuções. Para começar do zero, apagar a pasta `data/`.

---

## 6. Resolução de Problemas

**`Connection refused` no cliente**
→ O servidor não está a correr. Iniciar o servidor primeiro.

**`Address already in use`**
→ A porta 8080 está ocupada.
```bash
# No Windows
netstat -ano | findstr :8080
taskkill /PID <PID> /F

# No Linux/Mac
lsof -i :8080
kill -9 <PID>
```

**Interface gráfica não abre (Linux)**
→ Pode ser necessário instalar JavaFX separadamente:
```bash
sudo apt install openjfx
```
