# MANUAL.md — Manual do Utilizador

## 1. Acesso e Autenticação

### Criar conta
1. Abrir **http://localhost:8080** no browser
2. Clicar em **Criar conta**
3. Preencher Nome, Email e Password (mínimo 6 caracteres)
4. Clicar em **Registar**
5. Se o email estiver configurado, receberá um email de boas-vindas

### Iniciar sessão
1. Introduzir email e password
2. Clicar em **Entrar**

### Recuperar password
1. Clicar em **Esqueceste a password?**
2. Introduzir o email da conta
3. Se o email SMTP estiver configurado: receberá um código de 8 caracteres por email
4. Se não estiver: o administrador verá o código no terminal do servidor
5. Introduzir o código no campo **Código** e definir nova password

---

## 2. Interface Principal

Após login, a interface tem três zonas:

```
┌─────────────────────────────────────────────────────────────┐
│  CABEÇALHO: logo · barra de pesquisa · nome · botões        │
├──────────────────────────────────┬──────────────────────────┤
│                                  │                          │
│  CONTEÚDO PRINCIPAL              │  PAINEL LATERAL          │
│  ┌────────────────────────────┐  │  (admin: gestão)         │
│  │ Estatísticas               │  │                          │
│  ├────────────────────────────┤  │                          │
│  │ 💡 Recomendações para ti   │  │                          │
│  ├────────────────────────────┤  │                          │
│  │ Filtros · + Adicionar      │  │                          │
│  ├────────────────────────────┤  │                          │
│  │ Grelha de livros           │  │                          │
│  └────────────────────────────┘  │                          │
├──────────────────────────────────┴──────────────────────────┤
│  💬 Chat (botão flutuante, canto inferior direito)          │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. Catálogo de Livros

### Pesquisar
- Usar a **barra de pesquisa** no topo para filtrar por título, autor ou categoria
- Apagar o texto para voltar a ver todos

### Filtrar por estado
- **Todos** — mostra todos os livros
- **Disponíveis** — apenas livros com cópias disponíveis
- **Requisitados** — apenas livros totalmente requisitados

### Ver detalhes de um livro
Clicar no card do livro para abrir o painel de detalhes com:
- Informações completas (autor, categoria, estado, exemplares)
- Avaliação média e lista de comentários
- Lista de quem tem o livro e datas de prazo
- Fila de espera (se aplicável)
- Leitor de PDF integrado (se disponível)

---

## 4. Requisitar e Devolver

### Requisitar um livro disponível
1. Abrir os detalhes do livro
2. Clicar em **Requisitar**
3. O prazo é de **7 dias** a partir da data de hoje
4. Receberás uma notificação e um email de confirmação

### Entrar na fila de espera (livro indisponível)
1. Abrir os detalhes do livro
2. Clicar em **Entrar na fila de espera**
3. Quando alguém devolver, serás promovido automaticamente e notificado

### Devolver um livro
1. Clicar em **Os meus livros** (cabeçalho) para ver o que tens requisitado
2. Clicar em **↩ Devolver** no livro desejado
3. Se houver atraso: uma multa é calculada e adicionada à tua conta

### Ver os meus livros
- Clicar em **Os meus livros** no cabeçalho
- Mostra os livros actualmente requisitados, prazo e estado

---

## 5. Multas por Atraso

| Situação | Acção |
|---------|-------|
| Devolução no prazo | Sem multa |
| Devolução após o prazo | **0,50 € por cada dia de atraso** |
| Multa pendente | Não podes requisitar novos livros |
| Pagamento da multa | Contactar o administrador |

**Notificações automáticas de prazo:**
- 1 dia antes do prazo: lembrete (in-app + email)
- No dia do prazo: aviso urgente (in-app + email)
- Após o prazo: alerta com multa estimada (in-app + email, de hora em hora)

---

## 6. Adicionar Livros

Qualquer utilizador pode submeter livros:

1. Clicar em **+ Adicionar Livro**
2. Preencher Título, Autor, Categoria
3. Opcionalmente carregar um ficheiro PDF (máximo 50 MB)
4. Clicar em **Guardar**

**Com PDF:** o livro fica **pendente de aprovação** pelo administrador. A capa é extraída automaticamente da 1ª página.

**Sem PDF:** o livro é adicionado imediatamente ao catálogo.

---

## 7. Avaliações

Após requisitar (e devolver, ou enquanto tens) um livro:

1. Abrir os detalhes do livro
2. Clicar nas estrelas (1 a 5) e opcionalmente escrever um comentário
3. Clicar em **Avaliar**

A avaliação fica visível para todos os utilizadores. Podes actualizar a avaliação a qualquer momento.

---

## 8. Recomendações

A secção **💡 Recomendações para ti** aparece acima da grelha de livros com sugestões personalizadas. Cada card mostra o motivo da recomendação:

- **"Porque gostas de [Categoria]"** — baseado no teu historial de empréstimos
- **"Bem avaliado (X.X ⭐)"** — livros com boa classificação
- **"Leitores com gostos semelhantes também leram"** — collaborative filtering
- **"Popular na biblioteca"** — livros mais requisitados

Clicar num card abre os detalhes do livro.

---

## 9. Chat em Tempo Real

Clicar no botão **💬** no canto inferior direito para abrir o chat.

### Chat Global
- Mensagens visíveis para todos os utilizadores ligados
- Escrever na caixa de texto e premir Enter ou clicar **Enviar**

### Chat Privado
- Clicar no separador **Privado**
- Seleccionar o destinatário (ou "admin")
- As mensagens são visíveis apenas para os dois participantes

---

## 10. Histórico de Empréstimos

1. Clicar em **Histórico** no cabeçalho (apenas aparece para o admin; utilizadores normais vêem o seu histórico no painel de detalhes)
2. Mostra todos os empréstimos com datas de início, prazo e devolução

---

## 11. Painel de Administração

Disponível apenas para o utilizador **admin** no painel lateral direito.

### Gestão de Utilizadores
- Ver todos os utilizadores com estado (ligado/desligado)
- **Bloquear** — impede login
- **Avisar** — adiciona aviso (3 avisos = bloqueio automático)
- **Perdoar multa** — zera a multa pendente

### Aprovação de Livros
- Livros submetidos com PDF ficam pendentes
- Admin pode **Aprovar** (livro fica visível) ou **Rejeitar** (livro apagado + notificação ao uploader)
- Livros suspeitos (conteúdo inadequado detectado) são sinalizados com 🚨

### Moderação de Conteúdo
- Livros com palavras suspeitas são automaticamente sinalizados
- Admin vê os livros suspeitos separadamente para revisão

### Recuperações de Password
- Lista de tokens de recuperação activos com email e validade

### Exportar Relatórios CSV
- **📋 Empréstimos** — histórico completo com estado e dias de atraso
- **💸 Multas** — utilizadores com multas pendentes
- **👥 Utilizadores** — lista com avisos, bloqueios e multas
- **📚 Livros** — catálogo com estado, avaliações e disponibilidade

### Configuração de Email
- Configurar servidor SMTP para envio automático de notificações
- Testar com envio de email de prova

### Chat Privado
- Admin vê a lista de utilizadores que lhe enviaram mensagens privadas
- Pode responder directamente a cada um
