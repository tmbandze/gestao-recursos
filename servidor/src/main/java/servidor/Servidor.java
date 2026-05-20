package servidor;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import io.javalin.http.staticfiles.Location;
import jakarta.servlet.MultipartConfigElement;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Servidor {
    private static final int PORTA = 8080;

    private final GestorLivros gestorLivros;
    private final GestorUtilizadores gestorUtilizadores;
    private final GestorHistorico gestorHistorico;
    private final Logger logger;
    private final Map<String, String> sessoes     = new ConcurrentHashMap<>(); // sessionId → nome
    private final Map<String, SseClient> sseClientes = new ConcurrentHashMap<>(); // nome → SseClient

    public Servidor() {
        gestorHistorico    = new GestorHistorico();
        gestorLivros       = new GestorLivros(new BaseDados(), gestorHistorico, this);
        gestorUtilizadores = new GestorUtilizadores(new BaseDadosUtilizadores());
        logger             = new Logger();
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

        // ── Admin / relatórios ────────────────────────────────────────
        app.get("/api/relatorio", ctx ->
            ctx.json(gestorLivros.relatorio()));

        app.get("/api/historico", ctx ->
            ctx.json(Map.of("log", logger.lerUltimas(50))));

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
            var conectados = new java.util.HashSet<>(sessoes.values());
            var lista = gestorUtilizadores.listarNomes().stream().map(n -> {
                var m = new java.util.LinkedHashMap<String, Object>();
                m.put("nome", n);
                m.put("conectado", conectados.contains(n));
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

        app.start(PORTA);
        System.out.println("[INFO] Servidor web iniciado em http://0.0.0.0:" + PORTA);
        System.out.println("[INFO] Partilha com colegas: http://<SEU_IP>:" + PORTA);
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

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v != null ? v.toString().trim() : "";
    }

    public static void main(String[] args) {
        new Servidor().iniciar();
    }
}
