package servidor;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import io.javalin.http.staticfiles.Location;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Servidor {
    private static final int PORTA = 8080;

    private final GestorLivros gestorLivros;
    private final Logger logger;
    private final Map<String, String> sessoes = new ConcurrentHashMap<>();      // sessionId → nome
    private final Map<String, SseClient> sseClientes = new ConcurrentHashMap<>(); // nome → SseClient

    public Servidor() {
        gestorLivros = new GestorLivros(new BaseDados(), this);
        logger = new Logger();
    }

    public void iniciar() {
        var app = Javalin.create(config -> {
            config.staticFiles.add(sf -> {
                sf.hostedPath = "/";
                sf.directory  = "/public";
                sf.location   = Location.CLASSPATH;
            });
            config.bundledPlugins.enableCors(cors -> cors.addRule(it -> it.anyHost()));
        });

        // ── Autenticação ──────────────────────────────────────────────
        app.post("/api/login", ctx -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> b = ctx.bodyAsClass(Map.class);
            String nome = b.get("nome") != null ? b.get("nome").toString().trim() : "";
            if (nome.isBlank()) { ctx.status(400).json(Map.of("erro", "Nome inválido")); return; }
            String sid = UUID.randomUUID().toString();
            sessoes.put(sid, nome);
            logger.registar("LOGIN", nome, "-");
            ctx.json(Map.of("sessionId", sid, "nome", nome));
        });

        app.post("/api/logout", ctx -> {
            String nome = sessoes.remove(ctx.header("X-Session-ID"));
            if (nome != null) { sseClientes.remove(nome); logger.registar("LOGOUT", nome, "-"); }
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
            @SuppressWarnings("unchecked")
            Map<String, Object> b = ctx.bodyAsClass(Map.class);
            String titulo    = b.get("titulo")    != null ? b.get("titulo").toString()    : "";
            String autor     = b.get("autor")     != null ? b.get("autor").toString()     : "";
            String categoria = b.get("categoria") != null ? b.get("categoria").toString() : "Geral";
            var r = gestorLivros.inserir(titulo, autor, categoria, nome);
            if (r.containsKey("ok")) logger.registar("INSERIR", nome, titulo);
            ctx.json(r);
        });

        app.post("/api/livros/{id}/requisitar", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            String id = ctx.pathParam("id");
            var r = gestorLivros.requisitar(id, nome);
            if (r.containsKey("ok")) logger.registar("REQUISITAR", nome, id);
            ctx.json(r);
        });

        app.post("/api/livros/{id}/devolver", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            String id = ctx.pathParam("id");
            var r = gestorLivros.devolver(id, nome);
            if (r.containsKey("ok")) logger.registar("DEVOLVER", nome, id);
            ctx.json(r);
        });

        // ── Admin / relatórios ────────────────────────────────────────
        app.get("/api/relatorio", ctx ->
            ctx.json(gestorLivros.relatorio()));

        app.get("/api/historico", ctx ->
            ctx.json(Map.of("log", logger.lerUltimas(50))));

        app.get("/api/usuarios", ctx ->
            ctx.json(Map.of("usuarios", new ArrayList<>(sessoes.values()))));

        app.get("/api/admin/sistema", ctx -> {
            String nome = autenticar(ctx); if (nome == null) return;
            if (!nome.equalsIgnoreCase("admin")) {
                ctx.status(403).json(Map.of("erro", "Acesso negado")); return;
            }
            ctx.json(gestorLivros.relatorioAdmin());
        });

        // ── SSE — eventos em tempo real ───────────────────────────────
        // O browser envia sessionId como query param porque o EventSource
        // nativo não suporta headers personalizados.
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

    /** Envia evento SSE a todos os clientes excepto o que originou a acção. */
    public void notificarTodos(String evento, String dados, String excepto) {
        sseClientes.forEach((nome, client) -> {
            if (!nome.equals(excepto)) client.sendEvent(evento, dados);
        });
    }

    /** Envia notificação privada a um utilizador específico. */
    public void notificarUsuario(String nome, String mensagem) {
        SseClient client = sseClientes.get(nome);
        if (client != null) client.sendEvent("notificacao", mensagem);
    }

    public static void main(String[] args) {
        new Servidor().iniciar();
    }
}
