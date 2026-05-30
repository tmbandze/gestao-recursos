package servidor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import io.javalin.http.staticfiles.Location;
import jakarta.servlet.MultipartConfigElement;
import shared.MensagemChat;

import io.jsonwebtoken.Claims;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Servidor {
    private static final int PORTA = 8080;

    private final GestorLivros       gestorLivros;
    private final GestorUtilizadores gestorUtilizadores;
    private final GestorHistorico    gestorHistorico;
    private final Logger             logger;
    private final GestorTCP          gestorTCP;
    private final MonitorPrazos      monitorPrazos;
    private final GestorChat             gestorChat;
    private final GestorEmail            gestorEmail;
    private final GestorRecomendacoes    gestorRec;
    private final JwtUtil                jwtUtil     = new JwtUtil();
    private final Gson                   gson = new GsonBuilder().create();
    private final Set<String>            jwtBloqueados = ConcurrentHashMap.newKeySet(); // jti de tokens invalidados
    private final Map<String, SseClient> sseClientes   = new ConcurrentHashMap<>();     // nome → SseClient

    public Servidor() {
        gestorHistorico    = new GestorHistorico();
        gestorLivros       = new GestorLivros(new BaseDados(), gestorHistorico, this);
        gestorUtilizadores = new GestorUtilizadores(new BaseDadosUtilizadores());
        logger             = new Logger();
        gestorEmail        = new GestorEmail();
        gestorTCP          = new GestorTCP(gestorLivros, gestorUtilizadores, gestorHistorico, logger);
        monitorPrazos      = new MonitorPrazos(gestorHistorico, gestorTCP, logger, gestorEmail, gestorUtilizadores);
        gestorChat         = new GestorChat();
        gestorRec          = new GestorRecomendacoes(gestorHistorico, gestorLivros);
        // Injectar dependências no gestor de livros
        gestorLivros.setGestorTCP(gestorTCP);
        gestorLivros.setGestorUtilizadores(gestorUtilizadores);
        gestorLivros.setGestorEmail(gestorEmail);
    }

    public void iniciar() {
        var app = Javalin.create(config -> {
            config.staticFiles.add(sf -> {
                sf.hostedPath = "/";
                sf.directory  = "/public";
                sf.location   = Location.CLASSPATH;
            });
            config.bundledPlugins.enableCors(cors -> cors.addRule(it -> it.anyHost()));
            // Multipart: 50 MB max para uploads de PDF
            config.jetty.modifyServletContextHandler(h -> h.setAttribute(
                "org.eclipse.jetty.multipartConfig",
                new MultipartConfigElement(System.getProperty("java.io.tmpdir"),
                    50_000_000L, 50_000_000L, 1_000_000)));
        });

        // ── Autenticação ──────────────────────────────────────────────
        app.post("/api/registar", ctx -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> b = ctx.bodyAsClass(Map.class);
            String nomeInput = str(b, "nome");
            String email     = str(b, "email");
            String password  = str(b, "password");

            var r = gestorUtilizadores.registar(nomeInput, email, password);
            if (r.containsKey("erro")) { ctx.status(400).json(r); return; }

            String nome    = (String) r.get("nome");
            String emailOk = (String) r.get("email");
            boolean admin  = nome.equalsIgnoreCase("admin");
            String token   = jwtUtil.gerarToken(nome, emailOk != null ? emailOk : "", admin);
            logger.registar("REGISTAR", nome, email);
            notificarTodos("utilizadores_update", "login", "");
            // Email de boas-vindas
            if (email != null && !email.isBlank())
                gestorEmail.enviarAsync(email.trim().toLowerCase(),
                    "📚 Bem-vindo(a) à Biblioteca Digital!", gestorEmail.htmlBemVindo(nome));
            ctx.json(Map.of("token", token, "nome", nome, "email", emailOk != null ? emailOk : "",
                            "isAdmin", admin));
        });

        app.post("/api/login", ctx -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> b = ctx.bodyAsClass(Map.class);
            String email    = str(b, "email");
            String password = str(b, "password");

            var r = gestorUtilizadores.login(email, password);
            if (r.containsKey("erro")) { ctx.status(401).json(r); return; }

            String nome    = (String) r.get("nome");
            String emailOk = (String) r.get("email");
            boolean admin  = nome.equalsIgnoreCase("admin");
            String token   = jwtUtil.gerarToken(nome, emailOk != null ? emailOk : "", admin);
            logger.registar("LOGIN", nome, email);
            notificarTodos("utilizadores_update", "login", "");
            ctx.json(Map.of("token", token, "nome", nome, "email", emailOk != null ? emailOk : "",
                            "isAdmin", admin));
        });

        app.post("/api/logout", ctx -> {
            Claims c = extrairClaims(ctx);
            if (c != null) {
                String jti  = c.getId();
                String nome = c.getSubject();
                if (jti  != null) jwtBloqueados.add(jti);
                if (nome != null) sseClientes.remove(nome);
                logger.registar("LOGOUT", nome != null ? nome : "?", "-");
                notificarTodos("utilizadores_update", "logout", "");
            }
            ctx.json(Map.of("ok", true));
        });

        // Renovação de token (chamado pelo cliente 30 min antes da expiração)
        app.post("/api/auth/refresh", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            Claims c = extrairClaims(ctx);
            // Invalidar token antigo
            if (c != null && c.getId() != null) jwtBloqueados.add(c.getId());
            String email   = c != null ? (String) c.get("email") : "";
            boolean admin  = c != null && Boolean.TRUE.equals(c.get("admin"));
            String novoTok = jwtUtil.gerarToken(nome, email != null ? email : "", admin);
            ctx.json(Map.of("token", novoTok));
        });

        // ── Livros ────────────────────────────────────────────────────
        app.get("/api/livros", ctx ->
            ctx.json(gestorLivros.listarTodos()));

        app.get("/api/livros/pesquisa", ctx -> {
            String q = ctx.queryParam("q");
            ctx.json(gestorLivros.pesquisar(q != null ? q : ""));
        });

        app.get("/api/recomendacoes", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            int max = 8;
            try { String p = ctx.queryParam("max"); if (p != null) max = Math.min(20, Integer.parseInt(p)); }
            catch (Exception ignored) {}
            ctx.json(gestorRec.recomendar(nome, max));
        });

        app.get("/api/livros/{id}", ctx ->
            ctx.json(gestorLivros.detalhes(ctx.pathParam("id"))));

        app.post("/api/livros", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            String titulo    = ctx.formParam("titulo");
            String autor     = ctx.formParam("autor");
            String categoria = ctx.formParam("categoria");
            InputStream pdfStream = null;
            var uploaded = ctx.uploadedFile("pdf");
            if (uploaded != null && uploaded.size() > 0) pdfStream = uploaded.content();
            String t = titulo    != null ? titulo.trim()    : "";
            String a = autor     != null ? autor.trim()     : "";
            String c = categoria != null ? categoria.trim() : "Geral";
            var r = gestorLivros.inserir(t, a, c, nome, pdfStream);
            if (r.containsKey("ok")) logger.registar("INSERIR", nome, t);
            ctx.json(r);
        });

        app.delete("/api/livros/{id}", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            if (!nome.equalsIgnoreCase("admin")) {
                ctx.status(403).json(Map.of("erro", "Apenas o admin pode apagar livros")); return;
            }
            String id = ctx.pathParam("id");
            var r = gestorLivros.apagar(id);
            if (r.containsKey("ok")) logger.registar("APAGAR", nome, id);
            ctx.json(r);
        });

        app.get("/api/livros/{id}/ler", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            String id   = ctx.pathParam("id");
            var det = gestorLivros.detalhes(id);
            if (det.containsKey("erro")) { ctx.status(404).json(det); return; }
            if (!Boolean.TRUE.equals(det.get("temPdf"))) {
                ctx.status(404).json(Map.of("erro", "Este livro não tem PDF")); return;
            }
            String estudante = (String) det.get("estudanteActual");
            if (!nome.equalsIgnoreCase("admin") && !nome.equals(estudante)) {
                ctx.status(403).json(Map.of("erro", "Requisita o livro primeiro para poder ler o PDF")); return;
            }
            File pdf = gestorLivros.getPdfFile(id);
            if (pdf == null) { ctx.status(404).json(Map.of("erro", "Ficheiro PDF não encontrado")); return; }
            ctx.contentType("application/pdf");
            ctx.header("Content-Disposition", "inline; filename=\"livro.pdf\"");
            ctx.result(new FileInputStream(pdf));
        });

        // Capa do livro — público, sem autenticação (para carregar em <img>)
        app.get("/api/livros/{id}/capa", ctx -> {
            String id   = ctx.pathParam("id");
            File   capa = gestorLivros.getCoverFile(id);
            if (capa == null) { ctx.status(404).result(""); return; }
            ctx.contentType("image/jpeg");
            ctx.header("Cache-Control", "public, max-age=86400");
            ctx.result(new FileInputStream(capa));
        });

        app.post("/api/livros/{id}/requisitar", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            String id   = ctx.pathParam("id");
            var r = gestorLivros.requisitar(id, nome);
            if (r.containsKey("ok")) logger.registar("REQUISITAR", nome, id);
            ctx.json(r);
        });

        app.post("/api/livros/{id}/devolver", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            String id   = ctx.pathParam("id");
            var r = gestorLivros.devolver(id, nome);
            if (r.containsKey("ok")) logger.registar("DEVOLVER", nome, id);
            ctx.json(r);
        });

        // ── Avaliações ────────────────────────────────────────────────
        app.get("/api/livros/{id}/avaliacoes", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            ctx.json(gestorLivros.listarAvaliacoes(ctx.pathParam("id")));
        });

        app.post("/api/livros/{id}/avaliar", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            @SuppressWarnings("unchecked")
            Map<String, Object> b = ctx.bodyAsClass(Map.class);
            int estrelas = b.containsKey("estrelas")
                ? ((Number) b.get("estrelas")).intValue() : 0;
            String comentario = str(b, "comentario");
            String id = ctx.pathParam("id");
            var r = gestorLivros.avaliar(id, nome, estrelas, comentario);
            if (r.containsKey("ok"))
                logger.registar("AVALIAR", nome, id + " " + estrelas + "★");
            ctx.json(r);
        });

        // Utilizador remove a própria avaliação; admin remove qualquer uma
        app.delete("/api/livros/{id}/avaliacoes/{utilizador}", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            String alvo = ctx.pathParam("utilizador");
            if (!nome.equalsIgnoreCase("admin") && !nome.equals(alvo)) {
                ctx.status(403).json(Map.of("erro", "Só podes remover a tua própria avaliação"));
                return;
            }
            var r = gestorLivros.apagarAvaliacao(ctx.pathParam("id"), alvo);
            if (r.containsKey("ok")) logger.registar("APAGAR_AVAL", nome, alvo);
            ctx.json(r);
        });

        // ── Multas ────────────────────────────────────────────────────
        // Utilizador vê as suas próprias multas
        app.get("/api/multas", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            ctx.json(gestorUtilizadores.verMultas(nome));
        });

        // Admin: lista todos os utilizadores com multas
        app.get("/api/admin/multas", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            if (!nome.equalsIgnoreCase("admin")) { ctx.status(403).json(Map.of("erro","Acesso negado")); return; }
            ctx.json(Map.of("multas", gestorUtilizadores.listarMultas()));
        });

        // Admin: perdoa a multa de um utilizador
        app.post("/api/admin/multas/{utilizador}/perdoar", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            if (!nome.equalsIgnoreCase("admin")) { ctx.status(403).json(Map.of("erro","Acesso negado")); return; }
            String alvo = ctx.pathParam("utilizador");
            var r = gestorUtilizadores.perdoarMulta(alvo);
            if (r.containsKey("ok")) {
                logger.registar("PERDOAR_MULTA", nome, alvo);
                notificarUsuario(alvo,
                    "✅ A tua multa por atraso foi perdoada pelo administrador. " +
                    "Já podes requisitar novos livros.");
                notificarTodos("multa_update", "perdoada:" + alvo, "");
            }
            ctx.json(r);
        });

        // ── Admin / relatórios ────────────────────────────────────────
        app.get("/api/relatorio", ctx ->
            ctx.json(gestorLivros.relatorio()));

        app.get("/api/historico", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            if (!nome.equalsIgnoreCase("admin")) {
                ctx.status(403).json(Map.of("erro", "Acesso restrito ao administrador"));
                return;
            }
            ctx.json(Map.of("log", logger.lerUltimas(50)));
        });

        app.get("/api/historico/pessoal", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            var todos      = gestorHistorico.porEstudante(nome);
            var activos    = todos.stream().filter(e -> e.getDataFim() == null).collect(java.util.stream.Collectors.toList());
            var devolvidos = todos.stream().filter(e -> e.getDataFim() != null).collect(java.util.stream.Collectors.toList());
            ctx.json(Map.of("activos", activos, "devolvidos", devolvidos));
        });

        // Utilizadores com SSE activo = "online" via web
        app.get("/api/usuarios", ctx ->
            ctx.json(Map.of("usuarios", new ArrayList<>(sseClientes.keySet()))));

        app.get("/api/admin/utilizadores", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            if (!nome.equalsIgnoreCase("admin")) { ctx.status(403).json(Map.of("erro","Acesso negado")); return; }
            var conectadosSSE = sseClientes.keySet();
            var lista = gestorUtilizadores.listarNomes().stream().map(n -> {
                var m = new java.util.LinkedHashMap<String, Object>();
                m.put("nome", n);
                // Online = ligação SSE activa (web) OU ligação TCP activa (cliente JavaFX)
                m.put("conectado", conectadosSSE.contains(n) || gestorTCP.isConectado(n));
                return m;
            }).collect(java.util.stream.Collectors.toList());
            ctx.json(Map.of("utilizadores", lista));
        });

        app.get("/api/admin/sistema", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            if (!nome.equalsIgnoreCase("admin")) { ctx.status(403).json(Map.of("erro","Acesso negado")); return; }
            ctx.json(gestorLivros.relatorioAdmin());
        });

        // ── SSE ───────────────────────────────────────────────────────
        app.sse("/api/eventos", client -> {
            String token  = client.ctx().queryParam("token");
            Claims claims = jwtUtil.verificar(token);
            if (claims == null || jwtBloqueados.contains(claims.getId())) {
                client.close(); return;
            }
            String nome = claims.getSubject();
            sseClientes.put(nome, client);
            client.sendEvent("conectado", nome);
            client.onClose(() -> {
                sseClientes.remove(nome);
                notificarTodos("utilizadores_update", "logout", nome);
            });
        });

        // ── Recuperação de password (HTTP) ────────────────────────────
        app.post("/api/recuperar-password", ctx -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> b = ctx.bodyAsClass(Map.class);
            String email = str(b, "email");
            var r = gestorUtilizadores.pedirRecuperacao(email);
            if (r.containsKey("erro")) { ctx.status(400).json(r); return; }
            // Notificar o admin em tempo real via SSE
            SseClient adminSse = sseClientes.get("admin");
            if (adminSse != null) adminSse.sendEvent("recuperacao_update", email);
            // Enviar token por email se SMTP configurado
            if (email != null && gestorEmail.isConfigurado()) {
                String token = gestorUtilizadores.getTokenRecuperacao(email);
                String nomeUser = gestorUtilizadores.getNomePorEmail(email);
                if (token != null && nomeUser != null) {
                    gestorEmail.enviarAsync(email.trim().toLowerCase(),
                        "🔑 Recuperação de password — Biblioteca Digital",
                        gestorEmail.htmlRecuperacao(nomeUser, token));
                }
            }
            ctx.json(r);
        });

        app.get("/api/admin/recuperacoes", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            if (!nome.equalsIgnoreCase("admin")) { ctx.status(403).json(Map.of("erro","Acesso negado")); return; }
            ctx.json(Map.of("recuperacoes", gestorUtilizadores.listarRecuperacoesPendentes()));
        });

        app.post("/api/reset-password", ctx -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> b = ctx.bodyAsClass(Map.class);
            String token    = str(b, "token");
            String novaPwd  = str(b, "novaPassword");
            var r = gestorUtilizadores.resetarPassword(token, novaPwd);
            if (r.containsKey("erro")) { ctx.status(400).json(r); return; }
            ctx.json(r);
        });

        // ── Admin: livros suspeitos ────────────────────────────────────
        app.get("/api/admin/suspeitos", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            if (!nome.equalsIgnoreCase("admin")) { ctx.status(403).json(Map.of("erro","Acesso negado")); return; }
            ctx.json(Map.of("livros", gestorLivros.listarSuspeitos()));
        });

        app.post("/api/admin/avisar/{nome}", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            if (!nome.equalsIgnoreCase("admin")) { ctx.status(403).json(Map.of("erro","Acesso negado")); return; }
            String dest = ctx.pathParam("nome");
            int total = gestorUtilizadores.adicionarAviso(dest);
            // Push via SSE se o utilizador estiver ligado
            SseClient sc = sseClientes.get(dest);
            if (sc != null) sc.sendEvent("notificacao", "⚠ Aviso administrativo da biblioteca (total: " + total + ")");
            logger.registar("AVISO", nome, "→ " + dest);
            ctx.json(Map.of("ok", true, "avisos", total));
        });

        app.post("/api/admin/bloquear/{nome}", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            if (!nome.equalsIgnoreCase("admin")) { ctx.status(403).json(Map.of("erro","Acesso negado")); return; }
            String dest = ctx.pathParam("nome");
            gestorUtilizadores.bloquearUtilizador(dest);
            SseClient sc = sseClientes.get(dest);
            if (sc != null) sc.sendEvent("notificacao", "🚫 A sua conta foi bloqueada pelo administrador.");
            logger.registar("BLOQUEAR", nome, "→ " + dest);
            ctx.json(Map.of("ok", true));
        });

        app.post("/api/admin/desbloquear/{nome}", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            if (!nome.equalsIgnoreCase("admin")) { ctx.status(403).json(Map.of("erro","Acesso negado")); return; }
            String dest = ctx.pathParam("nome");
            gestorUtilizadores.desbloquearUtilizador(dest);
            logger.registar("DESBLOQUEAR", nome, "→ " + dest);
            ctx.json(Map.of("ok", true));
        });

        // ── Aprovação de livros pendentes ─────────────────────────────
        app.get("/api/admin/pendentes", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            if (!nome.equalsIgnoreCase("admin")) { ctx.status(403).json(Map.of("erro","Acesso negado")); return; }
            var pendentes = gestorLivros.listarPendentes().stream().map(l -> {
                var m = new java.util.LinkedHashMap<String, Object>();
                m.put("id",             l.getId());
                m.put("titulo",         l.getTitulo());
                m.put("autor",          l.getAutor());
                m.put("categoria",      l.getCategoria());
                m.put("uploadPor",      l.getUploadPor());
                m.put("relatorioScan",  l.getRelatorioScan());
                m.put("flagAdmin",      l.isFlagAdmin());
                m.put("motivoSuspeicao",l.getMotivoSuspeicao());
                m.put("temPdf",         l.isTemPdf());
                // Detecção de duplicado: livro aprovado com mesmo título+autor
                var similar = gestorLivros.buscarSimilar(l.getTitulo(), l.getAutor(), l.getId());
                if (similar != null) {
                    m.put("duplicadoId",        similar.getId());
                    m.put("duplicadoTitulo",    similar.getTitulo());
                    m.put("duplicadoExemplares",similar.getTotalExemplares());
                }
                return m;
            }).collect(java.util.stream.Collectors.toList());
            ctx.json(Map.of("pendentes", pendentes));
        });

        app.post("/api/admin/livros/{idPendente}/aprovar-como-exemplar/{idExistente}", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            if (!nome.equalsIgnoreCase("admin")) { ctx.status(403).json(Map.of("erro","Acesso negado")); return; }
            var r = gestorLivros.adicionarExemplar(ctx.pathParam("idPendente"), ctx.pathParam("idExistente"));
            if (r.containsKey("ok"))
                logger.registar("EXEMPLAR", nome, ctx.pathParam("idPendente") + "→" + ctx.pathParam("idExistente"));
            ctx.json(r);
        });

        app.post("/api/admin/livros/{id}/aprovar", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            if (!nome.equalsIgnoreCase("admin")) { ctx.status(403).json(Map.of("erro","Acesso negado")); return; }
            String id = ctx.pathParam("id");
            var r = gestorLivros.aprovarLivro(id);
            if (r.containsKey("ok")) {
                logger.registar("APROVAR", nome, id);
                notificarTodos("pendente_update", "aprovado", "");
            }
            ctx.json(r);
        });

        app.post("/api/admin/livros/{id}/rejeitar", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            if (!nome.equalsIgnoreCase("admin")) { ctx.status(403).json(Map.of("erro","Acesso negado")); return; }
            String id = ctx.pathParam("id");
            @SuppressWarnings("unchecked")
            Map<String, Object> b = ctx.bodyAsClass(Map.class);
            String motivo = str(b, "motivo");
            var r = gestorLivros.rejeitarLivro(id, motivo);
            if (r.containsKey("ok")) {
                logger.registar("REJEITAR", nome, id + (motivo.isBlank() ? "" : " (" + motivo + ")"));
                notificarTodos("pendente_update", "rejeitado", "");
            }
            ctx.json(r);
        });

        // ── Exportar relatórios CSV ───────────────────────────────────
        app.get("/api/admin/relatorio/emprestimos.csv", ctx -> {
            String nome = autenticarDownload(ctx); if (nome == null) return;
            if (!nome.equalsIgnoreCase("admin")) { ctx.status(403).result("Acesso negado"); return; }
            StringBuilder sb = new StringBuilder("﻿"); // BOM para Excel
            sb.append("ID,Livro,Utilizador,Data Início,Prazo,Data Devolução,Estado,Dias Atraso\n");
            for (var e : gestorHistorico.listarTodos()) {
                boolean devolvido = e.getDataFim() != null;
                long diasAtraso = 0;
                if (!devolvido && e.getPrazo() != null) {
                    try {
                        java.time.LocalDate prazo = java.time.LocalDate.parse(e.getPrazo());
                        diasAtraso = java.time.temporal.ChronoUnit.DAYS.between(prazo, java.time.LocalDate.now());
                        if (diasAtraso < 0) diasAtraso = 0;
                    } catch (Exception ignored) {}
                }
                String estado = devolvido ? "Devolvido"
                    : (diasAtraso > 0 ? "Atrasado (" + diasAtraso + "d)" : "Activo");
                sb.append(csvQ(e.getId())).append(',')
                  .append(csvQ(e.getTituloLivro())).append(',')
                  .append(csvQ(e.getEstudante())).append(',')
                  .append(csvQ(fmtData(e.getDataInicio()))).append(',')
                  .append(csvQ(fmtData(e.getPrazo()))).append(',')
                  .append(csvQ(devolvido ? fmtData(e.getDataFim()) : "—")).append(',')
                  .append(csvQ(estado)).append(',')
                  .append(devolvido ? "" : (diasAtraso > 0 ? diasAtraso : "")).append('\n');
            }
            ctx.contentType("text/csv; charset=utf-8");
            ctx.header("Content-Disposition", "attachment; filename=\"emprestimos.csv\"");
            ctx.result(sb.toString());
        });

        app.get("/api/admin/relatorio/multas.csv", ctx -> {
            String nome = autenticarDownload(ctx); if (nome == null) return;
            if (!nome.equalsIgnoreCase("admin")) { ctx.status(403).result("Acesso negado"); return; }
            StringBuilder sb = new StringBuilder("﻿");
            sb.append("Utilizador,Total Pendente (€),Livro,Dias Atraso,Valor (€),Data\n");
            for (var u : gestorUtilizadores.listarTodos()) {
                if (u.getMultaTotal() <= 0 && u.getMultas().isEmpty()) continue;
                if (u.getMultas().isEmpty()) {
                    sb.append(csvQ(u.getNome())).append(',')
                      .append(String.format("%.2f", u.getMultaTotal())).append(",,,\n");
                } else {
                    for (var m : u.getMultas()) {
                        sb.append(csvQ(u.getNome())).append(',')
                          .append(String.format("%.2f", u.getMultaTotal())).append(',')
                          .append(csvQ(m.getTituloLivro())).append(',')
                          .append(m.getDiasAtraso()).append(',')
                          .append(String.format("%.2f", m.getValor())).append(',')
                          .append(csvQ(fmtData(m.getData()))).append('\n');
                    }
                }
            }
            ctx.contentType("text/csv; charset=utf-8");
            ctx.header("Content-Disposition", "attachment; filename=\"multas.csv\"");
            ctx.result(sb.toString());
        });

        app.get("/api/admin/relatorio/utilizadores.csv", ctx -> {
            String nome = autenticarDownload(ctx); if (nome == null) return;
            if (!nome.equalsIgnoreCase("admin")) { ctx.status(403).result("Acesso negado"); return; }
            StringBuilder sb = new StringBuilder("﻿");
            sb.append("Nome,Email,Bloqueado,Avisos,Multa Pendente (€)\n");
            for (var u : gestorUtilizadores.listarTodos()) {
                sb.append(csvQ(u.getNome())).append(',')
                  .append(csvQ(u.getEmail())).append(',')
                  .append(u.isBloqueado() ? "Sim" : "Não").append(',')
                  .append(u.getAvisos()).append(',')
                  .append(String.format("%.2f", u.getMultaTotal())).append('\n');
            }
            ctx.contentType("text/csv; charset=utf-8");
            ctx.header("Content-Disposition", "attachment; filename=\"utilizadores.csv\"");
            ctx.result(sb.toString());
        });

        app.get("/api/admin/relatorio/livros.csv", ctx -> {
            String nome = autenticarDownload(ctx); if (nome == null) return;
            if (!nome.equalsIgnoreCase("admin")) { ctx.status(403).result("Acesso negado"); return; }
            StringBuilder sb = new StringBuilder("﻿");
            sb.append("Título,Autor,Categoria,Estado,Exemplares,Disponíveis,Avaliação Média,Nº Avaliações,PDF\n");
            for (var livro : gestorLivros.listarLivros()) {
                sb.append(csvQ(livro.getTitulo())).append(',')
                  .append(csvQ(livro.getAutor())).append(',')
                  .append(csvQ(livro.getCategoria())).append(',')
                  .append(csvQ(livro.getEstado().toString())).append(',')
                  .append(livro.getTotalExemplares()).append(',')
                  .append(livro.copiasDisponiveis()).append(',')
                  .append(String.format("%.1f", livro.mediaEstrelas())).append(',')
                  .append(livro.getAvaliacoes().size()).append(',')
                  .append(livro.isTemPdf() ? "Sim" : "Não").append('\n');
            }
            ctx.contentType("text/csv; charset=utf-8");
            ctx.header("Content-Disposition", "attachment; filename=\"livros.csv\"");
            ctx.result(sb.toString());
        });

        // ── Configuração de Email ─────────────────────────────────────
        app.get("/api/admin/email/config", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            if (!nome.equalsIgnoreCase("admin")) { ctx.status(403).json(Map.of("erro","Acesso negado")); return; }
            GestorEmail.ConfigEmail cfg = gestorEmail.getConfig();
            // Mascarar a password na resposta
            Map<String, Object> r = new java.util.LinkedHashMap<>();
            r.put("smtp",          cfg.smtp);
            r.put("porta",         cfg.porta);
            r.put("utilizador",    cfg.utilizador);
            r.put("passwordMask",  (cfg.password != null && !cfg.password.isBlank()) ? "********" : "");
            r.put("nomeRemetente", cfg.nomeRemetente);
            r.put("ssl",           cfg.ssl);
            r.put("ativo",         cfg.ativo);
            ctx.json(r);
        });

        app.post("/api/admin/email/config", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            if (!nome.equalsIgnoreCase("admin")) { ctx.status(403).json(Map.of("erro","Acesso negado")); return; }
            @SuppressWarnings("unchecked")
            Map<String, Object> b = ctx.bodyAsClass(Map.class);
            GestorEmail.ConfigEmail cfg = new GestorEmail.ConfigEmail();
            cfg.smtp          = str(b, "smtp");
            cfg.utilizador    = str(b, "utilizador");
            cfg.nomeRemetente = str(b, "nomeRemetente");
            cfg.ativo         = Boolean.TRUE.equals(b.get("ativo"));
            cfg.ssl           = Boolean.TRUE.equals(b.get("ssl"));
            try { cfg.porta = Integer.parseInt(String.valueOf(b.getOrDefault("porta", 587))); }
            catch (Exception e) { cfg.porta = 587; }
            // Só actualizar a password se foi enviada uma não-vazia e não é a máscara
            String pwd = str(b, "password");
            if (!pwd.isBlank() && !pwd.equals("********")) {
                cfg.password = pwd;
            } else {
                cfg.password = gestorEmail.getConfig().password; // manter a existente
            }
            gestorEmail.guardarConfig(cfg);
            ctx.json(Map.of("ok", true, "mensagem", "Configuração guardada."));
        });

        app.post("/api/admin/email/testar", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            if (!nome.equalsIgnoreCase("admin")) { ctx.status(403).json(Map.of("erro","Acesso negado")); return; }
            @SuppressWarnings("unchecked")
            Map<String, Object> b = ctx.bodyAsClass(Map.class);
            String para = str(b, "para");
            if (para.isBlank()) {
                // Se não especificado, enviar para o email do admin
                para = gestorUtilizadores.getEmailPorNome("admin");
            }
            if (para == null || para.isBlank()) {
                ctx.status(400).json(Map.of("erro", "Destinatário não especificado")); return;
            }
            String resultado = gestorEmail.testar(para);
            if ("ok".equals(resultado)) {
                ctx.json(Map.of("ok", true, "mensagem", "Email de teste enviado para " + para));
            } else {
                ctx.status(500).json(Map.of("erro", "Falha ao enviar: " + resultado));
            }
        });

        // ── Chat em Tempo Real ─────────────────────────────────────────
        app.get("/api/chat/mensagens", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            String para = ctx.queryParam("para");
            List<MensagemChat> msgs;
            if (para == null || para.isBlank()) {
                msgs = gestorChat.listarGlobal(80);
            } else {
                msgs = gestorChat.listarPrivada(nome, para, 80);
            }
            ctx.json(msgs);
        });

        app.post("/api/chat/enviar", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            @SuppressWarnings("unchecked")
            Map<String, Object> b = ctx.bodyAsClass(Map.class);
            String para  = str(b, "para");
            String texto = str(b, "texto");
            if (texto.isBlank()) { ctx.status(400).json(Map.of("erro", "Mensagem vazia")); return; }
            String paraFinal = para.isBlank() ? null : para;
            MensagemChat msg = gestorChat.enviar(nome, paraFinal, texto);
            if (msg == null) { ctx.status(400).json(Map.of("erro", "Erro ao enviar")); return; }
            String payload = gson.toJson(msg);
            if (paraFinal == null) {
                // mensagem global → todos via SSE
                notificarTodos("chat_mensagem", payload, "");
            } else {
                // mensagem privada → remetente e destinatário via SSE
                notificarUsuarioEvento(nome,      "chat_priv", payload);
                notificarUsuarioEvento(paraFinal, "chat_priv", payload);
            }
            ctx.json(Map.of("ok", true));
        });

        // Admin: lista utilizadores com conversas privadas
        app.get("/api/chat/interlocutores", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            if (!nome.equalsIgnoreCase("admin")) { ctx.status(403).json(Map.of("erro","Acesso negado")); return; }
            ctx.json(gestorChat.interlocutoresDoAdmin());
        });

        app.start(PORTA);
        String ipLocal = detectarIpLocal();
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║  SERVIDOR INICIADO                                           ║");
        System.out.printf( "║  Local    : http://localhost:%d                          ║%n", PORTA);
        System.out.printf( "║  Rede     : http://%-41s║%n", ipLocal + ":" + PORTA + "  ");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println("  → Partilha o link de rede com os teus colegas");

        // ── Serviços auxiliares ───────────────────────────────────────
        gestorTCP.iniciar();
        monitorPrazos.iniciar();
    }

    /** Extrai e valida os Claims do Bearer token no header Authorization. */
    private Claims extrairClaims(Context ctx) {
        String header = ctx.header("Authorization");
        String token  = null;
        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7).trim();
        }
        // Fallback: header X-Session-ID (compatibilidade)
        if (token == null || token.isBlank()) token = ctx.header("X-Session-ID");
        Claims c = jwtUtil.verificar(token);
        if (c != null && jwtBloqueados.contains(c.getId())) return null;
        return c;
    }

    private String autenticar(Context ctx) {
        Claims c = extrairClaims(ctx);
        if (c == null) { ctx.status(401).json(Map.of("erro", "Não autenticado")); return null; }
        return c.getSubject();
    }

    public void notificarTodos(String evento, String dados, String excepto) {
        sseClientes.forEach((nome, client) -> {
            if (!nome.equals(excepto)) client.sendEvent(evento, dados);
        });
    }

    public void notificarUsuario(String nome, String mensagem) {
        SseClient client = sseClientes.get(nome);
        if (client != null) client.sendEvent("notificacao", mensagem);
    }

    public void notificarUsuarioEvento(String nome, String evento, String dados) {
        SseClient client = sseClientes.get(nome);
        if (client != null) client.sendEvent(evento, dados);
    }

    /** Autenticação para downloads directos: aceita ?token= OU ?sid= (legado) como query param. */
    private String autenticarDownload(Context ctx) {
        // Tentar primeiro via header normal
        Claims c = extrairClaims(ctx);
        if (c == null) {
            // Fallback para query param (browser não pode enviar headers em downloads directos)
            String qp = ctx.queryParam("token");
            if (qp == null || qp.isBlank()) qp = ctx.queryParam("sid"); // legado
            c = jwtUtil.verificar(qp);
            if (c != null && jwtBloqueados.contains(c.getId())) c = null;
        }
        if (c == null) { ctx.status(401).result("Não autenticado"); return null; }
        return c.getSubject();
    }

    /** Escapa um valor para CSV: envolve em aspas se contiver vírgula, aspas ou newline. */
    private static String csvQ(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n"))
            return "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }

    /** Formata uma data ISO (yyyy-MM-dd ou datetime) para dd/MM/yyyy. */
    private static String fmtData(String iso) {
        if (iso == null) return "";
        try {
            java.time.LocalDate d = iso.length() > 10
                ? java.time.LocalDateTime.parse(iso).toLocalDate()
                : java.time.LocalDate.parse(iso);
            return String.format("%02d/%02d/%d", d.getDayOfMonth(), d.getMonthValue(), d.getYear());
        } catch (Exception e) { return iso; }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v != null ? v.toString().trim() : "";
    }

    /** Detecta o IP local na rede (exclui loopback e interfaces virtuais). */
    private static String detectarIpLocal() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String nome = ni.getDisplayName().toLowerCase();
                if (nome.contains("vmware") || nome.contains("virtualbox")
                        || nome.contains("hyper-v") || nome.contains("wsl")
                        || nome.contains("bluetooth") || nome.contains("virtual")) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof java.net.Inet4Address && !addr.getHostAddress().startsWith("127."))
                        return addr.getHostAddress();
                }
            }
            // Fallback: qualquer IPv4 não-loopback
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof java.net.Inet4Address && !addr.getHostAddress().startsWith("127."))
                        return addr.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "localhost";
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");  // PDFBox rendering sem display
        new Servidor().iniciar();
    }
}
