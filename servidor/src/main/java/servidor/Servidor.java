package servidor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import io.javalin.http.staticfiles.Location;
import jakarta.servlet.MultipartConfigElement;
import shared.MensagemChat;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Servidor {
    private static final int PORTA = 8080;

    private final GestorLivros       gestorLivros;
    private final GestorUtilizadores gestorUtilizadores;
    private final GestorHistorico    gestorHistorico;
    private final Logger             logger;
    private final GestorTCP          gestorTCP;
    private final MonitorPrazos      monitorPrazos;
    private final GestorChat         gestorChat;
    private final Gson               gson = new GsonBuilder().create();
    private final Map<String, String>    sessoes     = new ConcurrentHashMap<>(); // sessionId → nome
    private final Map<String, SseClient> sseClientes = new ConcurrentHashMap<>(); // nome → SseClient

    public Servidor() {
        gestorHistorico    = new GestorHistorico();
        gestorLivros       = new GestorLivros(new BaseDados(), gestorHistorico, this);
        gestorUtilizadores = new GestorUtilizadores(new BaseDadosUtilizadores());
        logger             = new Logger();
        gestorTCP          = new GestorTCP(gestorLivros, gestorUtilizadores, gestorHistorico, logger);
        monitorPrazos      = new MonitorPrazos(gestorHistorico, gestorTCP, logger);
        gestorChat         = new GestorChat();
        // Injectar dependências no gestor de livros
        gestorLivros.setGestorTCP(gestorTCP);
        gestorLivros.setGestorUtilizadores(gestorUtilizadores);
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

            String sid  = UUID.randomUUID().toString();
            String nome = (String) r.get("nome");
            sessoes.put(sid, nome);
            logger.registar("REGISTAR", nome, email);
            notificarTodos("utilizadores_update", "login", "");
            ctx.json(Map.of("sessionId", sid, "nome", nome, "email", r.get("email"),
                            "isAdmin", nome.equalsIgnoreCase("admin")));
        });

        app.post("/api/login", ctx -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> b = ctx.bodyAsClass(Map.class);
            String email    = str(b, "email");
            String password = str(b, "password");

            var r = gestorUtilizadores.login(email, password);
            if (r.containsKey("erro")) { ctx.status(401).json(r); return; }

            String sid  = UUID.randomUUID().toString();
            String nome = (String) r.get("nome");
            sessoes.put(sid, nome);
            logger.registar("LOGIN", nome, email);
            notificarTodos("utilizadores_update", "login", "");
            ctx.json(Map.of("sessionId", sid, "nome", nome, "email", r.get("email"),
                            "isAdmin", nome.equalsIgnoreCase("admin")));
        });

        app.post("/api/logout", ctx -> {
            String nome = sessoes.remove(ctx.header("X-Session-ID"));
            if (nome != null) {
                sseClientes.remove(nome);
                logger.registar("LOGOUT", nome, "-");
                notificarTodos("utilizadores_update", "logout", "");
            }
            ctx.json(Map.of("ok", true));
        });

        // ── Livros ────────────────────────────────────────────────────
        app.get("/api/livros", ctx ->
            ctx.json(gestorLivros.listarTodos()));

        app.get("/api/livros/pesquisa", ctx -> {
            String q = ctx.queryParam("q");
            ctx.json(gestorLivros.pesquisar(q != null ? q : ""));
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

        app.get("/api/usuarios", ctx ->
            ctx.json(Map.of("usuarios", new ArrayList<>(sessoes.values()))));

        app.get("/api/admin/utilizadores", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            if (!nome.equalsIgnoreCase("admin")) { ctx.status(403).json(Map.of("erro","Acesso negado")); return; }
            var conectadosHTTP = new java.util.HashSet<>(sessoes.values());
            var lista = gestorUtilizadores.listarNomes().stream().map(n -> {
                var m = new java.util.LinkedHashMap<String, Object>();
                m.put("nome", n);
                // Online = sessão HTTP (web) OU ligação TCP activa (cliente JavaFX)
                m.put("conectado", conectadosHTTP.contains(n) || gestorTCP.isConectado(n));
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
            String sid  = client.ctx().queryParam("sid");
            String nome = sessoes.get(sid);
            if (nome == null) { client.close(); return; }
            sseClientes.put(nome, client);
            client.sendEvent("conectado", nome);
            client.onClose(() -> sseClientes.remove(nome));
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

    private String autenticar(Context ctx) {
        String nome = sessoes.get(ctx.header("X-Session-ID"));
        if (nome == null) ctx.status(401).json(Map.of("erro", "Não autenticado"));
        return nome;
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
