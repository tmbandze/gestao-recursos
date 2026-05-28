package servidor;

import shared.Livro;
import shared.Protocolo;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servidor TCP que permite ao cliente JavaFX comunicar com o servidor.
 * Escuta na porta 9090 e gere conexões persistentes por utilizador,
 * suportando tanto respostas síncronas como notificações push assíncronas.
 */
public class GestorTCP {
    public  static final int PORTA_TCP = 9090;

    private final GestorLivros         gestorLivros;
    private final GestorUtilizadores   gestorUtilizadores;
    private final GestorHistorico      gestorHistorico;
    private final Logger               logger;

    // nome → PrintWriter (conexão activa). ConcurrentHashMap para acesso multi-thread.
    private final Map<String, PrintWriter> clientes = new ConcurrentHashMap<>();

    public GestorTCP(GestorLivros gestorLivros, GestorUtilizadores gestorUtilizadores,
                     GestorHistorico gestorHistorico, Logger logger) {
        this.gestorLivros       = gestorLivros;
        this.gestorUtilizadores = gestorUtilizadores;
        this.gestorHistorico    = gestorHistorico;
        this.logger             = logger;
    }

    /** Inicia o servidor TCP numa thread daemon. */
    public void iniciar() {
        Thread t = new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(PORTA_TCP)) {
                System.out.println("[TCP] Servidor JavaFX iniciado na porta " + PORTA_TCP);
                while (!ss.isClosed()) {
                    Socket socket = ss.accept();
                    Thread ct = new Thread(() -> tratarCliente(socket), "tcp-client");
                    ct.setDaemon(true);
                    ct.start();
                }
            } catch (IOException e) {
                System.err.println("[TCP] Erro no servidor: " + e.getMessage());
            }
        }, "tcp-acceptor");
        t.setDaemon(true);
        t.start();
    }

    // ── Tratamento de conexão ─────────────────────────────────────────────

    private void tratarCliente(Socket socket) {
        String nome = null;
        try (
            BufferedReader in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            PrintWriter    out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true)
        ) {
            String linha;
            while ((linha = in.readLine()) != null) {
                String[] p   = linha.split("\\|", -1);
                String   cmd = p[0].trim().toUpperCase();

                switch (cmd) {

                    // ── Autenticação ─────────────────────────────────────
                    case Protocolo.LOGIN -> {
                        String nomeReq = p.length > 1 ? p[1].trim() : "";
                        if (nomeReq.isEmpty()) { enviar(out, Protocolo.ERRO + "|Nome inválido"); break; }

                        // Verificar se está bloqueado
                        if (gestorUtilizadores.estaBloqueado(nomeReq)) {
                            enviar(out, Protocolo.ERRO + "|Conta bloqueada pelo administrador");
                            break;
                        }

                        nome = nomeReq;
                        clientes.put(nome, out);
                        logger.registar("LOGIN_TCP", nome, "-");
                        enviar(out, Protocolo.OK + "|Bem-vindo, " + nome);
                        notificarTodos(Protocolo.ATUALIZAR, nome);
                    }

                    // ── Livros ────────────────────────────────────────────
                    case Protocolo.LISTAR -> enviar(out, serializarLivros(gestorLivros.listarTodos()));
                    case Protocolo.LISTAR_DISPONIVEIS -> {
                        var lista = gestorLivros.listarTodos().stream().filter(Livro::isDisponivel).toList();
                        enviar(out, serializarLivros(lista));
                    }
                    case Protocolo.PESQUISAR -> {
                        String termo = p.length > 1 ? p[1] : "";
                        enviar(out, serializarLivros(gestorLivros.pesquisar(termo)));
                    }
                    case Protocolo.DETALHES -> {
                        if (p.length < 2) { enviar(out, Protocolo.ERRO + "|ID em falta"); break; }
                        var det = gestorLivros.detalhes(p[1]);
                        if (det.containsKey("erro")) { enviar(out, Protocolo.ERRO + "|" + det.get("erro")); break; }
                        String est  = det.get("estudanteActual") != null ? (String) det.get("estudanteActual") : "-";
                        String fila = det.get("filaEspera")    != null ? det.get("filaEspera").toString()   : "-";
                        enviar(out, "DETALHES|" + det.get("id") + "|" + det.get("titulo") + "|"
                            + det.get("autor") + "|" + det.get("categoria") + "|"
                            + det.get("estado") + "|" + est + "|" + fila);
                    }
                    case Protocolo.INSERIR -> {
                        if (nome == null) { enviar(out, Protocolo.ERRO + "|Não autenticado"); break; }
                        String titulo = p.length > 1 ? p[1] : "";
                        String autor  = p.length > 2 ? p[2] : "";
                        String cat    = p.length > 3 ? p[3] : "Geral";
                        var r = gestorLivros.inserir(titulo, autor, cat, nome, null);
                        if (r.containsKey("ok")) {
                            logger.registar("INSERIR", nome, titulo);
                            notificarTodos(Protocolo.ATUALIZAR, nome);
                            enviar(out, Protocolo.OK + "|" + r.get("mensagem"));
                        } else {
                            enviar(out, Protocolo.ERRO + "|" + r.get("erro"));
                        }
                    }
                    case Protocolo.REQUISITAR -> {
                        if (nome == null) { enviar(out, Protocolo.ERRO + "|Não autenticado"); break; }
                        if (p.length < 2)  { enviar(out, Protocolo.ERRO + "|ID em falta"); break; }
                        var r = gestorLivros.requisitar(p[1], nome);
                        if (r.containsKey("ok")) {
                            logger.registar("REQUISITAR", nome, p[1]);
                            notificarTodos(Protocolo.ATUALIZAR, nome);
                            enviar(out, Protocolo.OK + "|" + r.get("mensagem"));
                        } else {
                            enviar(out, Protocolo.ERRO + "|" + r.get("erro"));
                        }
                    }
                    case Protocolo.DEVOLVER -> {
                        if (nome == null) { enviar(out, Protocolo.ERRO + "|Não autenticado"); break; }
                        if (p.length < 2)  { enviar(out, Protocolo.ERRO + "|ID em falta"); break; }
                        var r = gestorLivros.devolver(p[1], nome);
                        if (r.containsKey("ok")) {
                            logger.registar("DEVOLVER", nome, p[1]);
                            notificarTodos(Protocolo.ATUALIZAR, nome);
                            enviar(out, Protocolo.OK + "|" + r.get("mensagem"));
                        } else {
                            enviar(out, Protocolo.ERRO + "|" + r.get("erro"));
                        }
                    }
                    case Protocolo.HISTORICO -> {
                        if (!isAdmin(nome)) { enviar(out, Protocolo.ERRO + "|Acesso restrito ao administrador"); break; }
                        enviar(out, Protocolo.LOG + "|" + logger.lerUltimas(50));
                    }
                    case Protocolo.RELATORIO -> {
                        var rel = gestorLivros.relatorio();
                        enviar(out, Protocolo.STATS + "|Total: " + rel.get("total")
                            + "|Disponíveis: " + rel.get("disponiveis")
                            + "|Requisitados: " + rel.get("requisitados"));
                    }

                    // ── Admin ─────────────────────────────────────────────
                    case Protocolo.ADMIN_USUARIOS -> {
                        if (!isAdmin(nome)) { enviar(out, Protocolo.ERRO + "|Acesso negado"); break; }
                        List<String> todos = gestorUtilizadores.listarNomes();
                        StringBuilder sb = new StringBuilder(Protocolo.USUARIOS + "|");
                        for (int i = 0; i < todos.size(); i++) {
                            if (i > 0) sb.append(";");
                            String n       = todos.get(i);
                            boolean online = clientes.containsKey(n);
                            int avisos     = gestorUtilizadores.getAvisos(n);
                            boolean block  = gestorUtilizadores.estaBloqueado(n);
                            sb.append(n).append(",")
                              .append(online ? "CONECTADO" : "DESCONECTADO").append(",")
                              .append(avisos).append(",")
                              .append(block ? "BLOQUEADO" : "ACTIVO");
                        }
                        enviar(out, sb.toString());
                    }
                    case Protocolo.ADMIN_SISTEMA -> {
                        if (!isAdmin(nome)) { enviar(out, Protocolo.ERRO + "|Acesso negado"); break; }
                        var rel = gestorLivros.relatorioAdmin();
                        StringBuilder sb = new StringBuilder(Protocolo.SISTEMA + "|");
                        sb.append("Total: ").append(rel.get("total"))
                          .append("|Disponíveis: ").append(rel.get("disponiveis"))
                          .append("|Requisitados: ").append(rel.get("requisitados"))
                          .append("|LIVROS|");
                        @SuppressWarnings("unchecked")
                        var livros = (List<Map<String, Object>>) rel.get("livros");
                        if (livros != null) {
                            for (int i = 0; i < livros.size(); i++) {
                                var l = livros.get(i);
                                if (i > 0) sb.append("|");
                                sb.append(l.get("titulo")).append("~")
                                  .append(l.get("autor")).append("~")
                                  .append(l.get("estado")).append("~")
                                  .append(l.getOrDefault("estudanteActual", "-")).append("~")
                                  .append(l.getOrDefault("filaEspera", "-"));
                            }
                        }
                        enviar(out, sb.toString());
                    }

                    // ── Moderação ─────────────────────────────────────────
                    case Protocolo.ADMIN_FLAGGED -> {
                        if (!isAdmin(nome)) { enviar(out, Protocolo.ERRO + "|Acesso negado"); break; }
                        List<Livro> suspeitos = gestorLivros.listarSuspeitos();
                        StringBuilder sb = new StringBuilder(Protocolo.FLAGGED + "|");
                        for (int i = 0; i < suspeitos.size(); i++) {
                            if (i > 0) sb.append(";");
                            Livro l = suspeitos.get(i);
                            sb.append(l.getId()).append("~")
                              .append(l.getTitulo()).append("~")
                              .append(l.getUploadPor() != null ? l.getUploadPor() : "-").append("~")
                              .append(l.getMotivoSuspeicao() != null ? l.getMotivoSuspeicao() : "-");
                        }
                        enviar(out, sb.toString());
                    }
                    case Protocolo.ADMIN_AVISAR -> {
                        if (!isAdmin(nome)) { enviar(out, Protocolo.ERRO + "|Acesso negado"); break; }
                        String dest   = p.length > 1 ? p[1].trim() : "";
                        String motivo = p.length > 2 ? p[2].trim() : "Conteúdo impróprio detectado no upload";
                        int totalAvisos = gestorUtilizadores.adicionarAviso(dest);
                        notificarUtilizador(dest, "⚠  AVISO ADMINISTRATIVO: " + motivo
                            + "  —  A sua conta tem " + totalAvisos + " aviso(s)."
                            + (totalAvisos >= 3 ? " Mais avisos resultarão no bloqueio." : ""));
                        logger.registar("AVISO", nome, "→ " + dest + " (" + totalAvisos + "avs)");
                        enviar(out, Protocolo.OK + "|Aviso enviado a " + dest
                            + " (total: " + totalAvisos + ")");
                    }
                    case Protocolo.ADMIN_BLOQUEAR -> {
                        if (!isAdmin(nome)) { enviar(out, Protocolo.ERRO + "|Acesso negado"); break; }
                        String dest = p.length > 1 ? p[1].trim() : "";
                        gestorUtilizadores.bloquearUtilizador(dest);
                        notificarUtilizador(dest,
                            "🚫  A sua conta foi BLOQUEADA pelo administrador por violação das "
                            + "políticas de uso aceitável da biblioteca. Contacte o administrador.");
                        logger.registar("BLOQUEAR", nome, "→ " + dest);
                        enviar(out, Protocolo.OK + "|Utilizador " + dest + " bloqueado");
                    }
                    case Protocolo.ADMIN_DESBLOQUEAR -> {
                        if (!isAdmin(nome)) { enviar(out, Protocolo.ERRO + "|Acesso negado"); break; }
                        String dest = p.length > 1 ? p[1].trim() : "";
                        gestorUtilizadores.desbloquearUtilizador(dest);
                        notificarUtilizador(dest, "✅  A sua conta foi DESBLOQUEADA pelo administrador.");
                        logger.registar("DESBLOQUEAR", nome, "→ " + dest);
                        enviar(out, Protocolo.OK + "|Utilizador " + dest + " desbloqueado");
                    }

                    // ── Aprovação de livros pendentes ─────────────────────
                    case Protocolo.ADMIN_PENDENTES -> {
                        if (!isAdmin(nome)) { enviar(out, Protocolo.ERRO + "|Acesso negado"); break; }
                        List<Livro> pendentes = gestorLivros.listarPendentes();
                        StringBuilder sb = new StringBuilder(Protocolo.PENDENTES + "|");
                        for (int i = 0; i < pendentes.size(); i++) {
                            if (i > 0) sb.append(";");
                            Livro l = pendentes.get(i);
                            Livro similar = gestorLivros.buscarSimilar(l.getTitulo(), l.getAutor(), l.getId());
                            String dupInfo = similar != null
                                ? "DUP:" + similar.getId() + "(" + similar.getTotalExemplares() + "ex)"
                                : "-";
                            sb.append(l.getId()).append("~")
                              .append(l.getTitulo()).append("~")
                              .append(l.getAutor()).append("~")
                              .append(l.getUploadPor() != null ? l.getUploadPor() : "-").append("~")
                              .append(l.getRelatorioScan() != null ? l.getRelatorioScan() : "N/A").append("~")
                              .append(l.isFlagAdmin() ? "SUSPEITO" : "OK").append("~")
                              .append(dupInfo);
                        }
                        enviar(out, sb.toString());
                    }
                    case Protocolo.ADMIN_APROVAR -> {
                        if (!isAdmin(nome)) { enviar(out, Protocolo.ERRO + "|Acesso negado"); break; }
                        String id = p.length > 1 ? p[1].trim() : "";
                        var r = gestorLivros.aprovarLivro(id);
                        if (r.containsKey("ok")) {
                            logger.registar("APROVAR_TCP", nome, id);
                            notificarTodos(Protocolo.ATUALIZAR, nome);
                            enviar(out, Protocolo.OK + "|" + r.get("mensagem"));
                        } else {
                            enviar(out, Protocolo.ERRO + "|" + r.get("erro"));
                        }
                    }
                    case Protocolo.ADMIN_REJEITAR -> {
                        if (!isAdmin(nome)) { enviar(out, Protocolo.ERRO + "|Acesso negado"); break; }
                        String idRej   = p.length > 1 ? p[1].trim() : "";
                        String motivoR = p.length > 2 ? p[2].trim() : "";
                        var r = gestorLivros.rejeitarLivro(idRej, motivoR);
                        if (r.containsKey("ok")) {
                            logger.registar("REJEITAR_TCP", nome, idRej);
                            notificarTodos(Protocolo.ATUALIZAR, nome);
                            enviar(out, Protocolo.OK + "|" + r.get("mensagem"));
                        } else {
                            enviar(out, Protocolo.ERRO + "|" + r.get("erro"));
                        }
                    }
                    // ── Multas ───────────────────────────────────────────
                    case Protocolo.VER_MULTAS -> {
                        if (nome == null) { enviar(out, Protocolo.ERRO + "|Não autenticado"); break; }
                        var r = gestorUtilizadores.verMultas(nome);
                        double total = ((Number) r.get("multaTotal")).doubleValue();
                        if (total <= 0) {
                            enviar(out, Protocolo.OK + "|Sem multas pendentes.");
                        } else {
                            @SuppressWarnings("unchecked")
                            var lista = (java.util.List<shared.RegistoMulta>) r.get("multas");
                            StringBuilder sbM = new StringBuilder(Protocolo.MULTA)
                                .append("|total:").append(String.format("%.2f", total)).append("|");
                            for (int i = 0; i < lista.size(); i++) {
                                if (i > 0) sbM.append(";");
                                shared.RegistoMulta rm = lista.get(i);
                                sbM.append(rm.getTituloLivro()).append("~")
                                   .append(rm.getDiasAtraso()).append("d~")
                                   .append(String.format("%.2f", rm.getValor())).append("€~")
                                   .append(rm.getData());
                            }
                            enviar(out, sbM.toString());
                        }
                    }
                    case Protocolo.ADMIN_LISTAR_MULTAS -> {
                        if (!isAdmin(nome)) { enviar(out, Protocolo.ERRO + "|Acesso negado"); break; }
                        var lista = gestorUtilizadores.listarMultas();
                        if (lista.isEmpty()) { enviar(out, Protocolo.OK + "|Sem multas pendentes."); break; }
                        StringBuilder sbM = new StringBuilder(Protocolo.MULTA + "|");
                        for (int i = 0; i < lista.size(); i++) {
                            if (i > 0) sbM.append(";");
                            var u = lista.get(i);
                            sbM.append(u.get("nome")).append("~")
                               .append(String.format("%.2f", ((Number) u.get("multaTotal")).doubleValue())).append("€");
                        }
                        enviar(out, sbM.toString());
                    }
                    case Protocolo.ADMIN_PERDOAR_MULTA -> {
                        if (!isAdmin(nome)) { enviar(out, Protocolo.ERRO + "|Acesso negado"); break; }
                        String dest = p.length > 1 ? p[1].trim() : "";
                        var r = gestorUtilizadores.perdoarMulta(dest);
                        if (r.containsKey("ok")) {
                            logger.registar("PERDOAR_MULTA_TCP", nome, dest);
                            notificarUtilizador(dest,
                                "✅ A tua multa foi perdoada pelo administrador. Já podes requisitar livros.");
                            enviar(out, Protocolo.OK + "|" + r.get("mensagem"));
                        } else {
                            enviar(out, Protocolo.ERRO + "|" + r.get("erro"));
                        }
                    }

                    // ── Avaliações ────────────────────────────────────────
                    case Protocolo.AVALIAR -> {
                        if (nome == null) { enviar(out, Protocolo.ERRO + "|Não autenticado"); break; }
                        if (p.length < 3) { enviar(out, Protocolo.ERRO + "|Uso: AVALIAR|id|estrelas[|comentario]"); break; }
                        int estrelas;
                        try { estrelas = Integer.parseInt(p[2].trim()); }
                        catch (NumberFormatException e) { enviar(out, Protocolo.ERRO + "|Estrelas deve ser 1-5"); break; }
                        String comentAv = p.length > 3 ? p[3].trim() : "";
                        var r = gestorLivros.avaliar(p[1].trim(), nome, estrelas, comentAv);
                        if (r.containsKey("ok")) {
                            logger.registar("AVALIAR_TCP", nome, p[1] + " " + estrelas + "★");
                            enviar(out, Protocolo.OK + "|" + r.get("mensagem")
                                + " (média: " + r.get("media") + "★, " + r.get("total") + " avaliação(ões))");
                        } else {
                            enviar(out, Protocolo.ERRO + "|" + r.get("erro"));
                        }
                    }
                    case Protocolo.LISTAR_AVALIACOES -> {
                        if (nome == null) { enviar(out, Protocolo.ERRO + "|Não autenticado"); break; }
                        if (p.length < 2) { enviar(out, Protocolo.ERRO + "|ID em falta"); break; }
                        var r = gestorLivros.listarAvaliacoes(p[1].trim());
                        if (r.containsKey("erro")) { enviar(out, Protocolo.ERRO + "|" + r.get("erro")); break; }
                        @SuppressWarnings("unchecked")
                        var avalList = (java.util.List<shared.Avaliacao>) r.get("avaliacoes");
                        StringBuilder sbAv = new StringBuilder(Protocolo.AVALIACOES + "|");
                        sbAv.append("media:").append(r.get("media"))
                            .append("|total:").append(r.get("total")).append("|");
                        for (int i = 0; i < avalList.size(); i++) {
                            if (i > 0) sbAv.append(";");
                            shared.Avaliacao a = avalList.get(i);
                            sbAv.append(a.getUtilizador()).append("~")
                                .append(a.getEstrelas()).append("~")
                                .append(a.getComentario() != null ? a.getComentario().replace("~", " ") : "")
                                .append("~").append(a.getData() != null ? a.getData() : "");
                        }
                        enviar(out, sbAv.toString());
                    }
                    case Protocolo.APAGAR_AVALIACAO -> {
                        if (nome == null) { enviar(out, Protocolo.ERRO + "|Não autenticado"); break; }
                        if (p.length < 3) { enviar(out, Protocolo.ERRO + "|Uso: APAGAR_AVALIACAO|idLivro|utilizador"); break; }
                        String alvo = p[2].trim();
                        if (!isAdmin(nome) && !nome.equals(alvo)) {
                            enviar(out, Protocolo.ERRO + "|Só podes remover a tua própria avaliação"); break;
                        }
                        var r = gestorLivros.apagarAvaliacao(p[1].trim(), alvo);
                        if (r.containsKey("ok")) {
                            logger.registar("APAGAR_AVAL_TCP", nome, alvo);
                            enviar(out, Protocolo.OK + "|" + r.get("mensagem"));
                        } else {
                            enviar(out, Protocolo.ERRO + "|" + r.get("erro"));
                        }
                    }

                    case Protocolo.ADMIN_APROVAR_EXEMPLAR -> {
                        if (!isAdmin(nome)) { enviar(out, Protocolo.ERRO + "|Acesso negado"); break; }
                        String idPend = p.length > 1 ? p[1].trim() : "";
                        String idExis = p.length > 2 ? p[2].trim() : "";
                        var r = gestorLivros.adicionarExemplar(idPend, idExis);
                        if (r.containsKey("ok")) {
                            logger.registar("EXEMPLAR_TCP", nome, idPend + "→" + idExis);
                            notificarTodos(Protocolo.ATUALIZAR, nome);
                            enviar(out, Protocolo.OK + "|" + r.get("mensagem"));
                        } else {
                            enviar(out, Protocolo.ERRO + "|" + r.get("erro"));
                        }
                    }

                    // ── Recuperação de password ───────────────────────────
                    case Protocolo.RECUPERAR_PASSWORD -> {
                        String email = p.length > 1 ? p[1].trim() : "";
                        var r = gestorUtilizadores.pedirRecuperacao(email);
                        if (r.containsKey("ok")) {
                            enviar(out, Protocolo.OK + "|" + r.get("mensagem"));
                        } else {
                            enviar(out, Protocolo.ERRO + "|" + r.get("erro"));
                        }
                    }
                    case Protocolo.RESET_PASSWORD -> {
                        String token     = p.length > 1 ? p[1].trim() : "";
                        String novaPass  = p.length > 2 ? p[2].trim() : "";
                        var r = gestorUtilizadores.resetarPassword(token, novaPass);
                        if (r.containsKey("ok")) {
                            enviar(out, Protocolo.OK + "|" + r.get("mensagem"));
                        } else {
                            enviar(out, Protocolo.ERRO + "|" + r.get("erro"));
                        }
                    }

                    case Protocolo.SAIR -> {
                        if (nome != null) clientes.remove(nome);
                        enviar(out, Protocolo.OK + "|Até logo");
                        return;
                    }
                    default -> enviar(out, Protocolo.ERRO + "|Comando desconhecido: " + cmd);
                }
            }
        } catch (IOException e) {
            System.err.println("[TCP] Conexão encerrada: " + (nome != null ? nome : "desconhecido"));
        } finally {
            if (nome != null) {
                clientes.remove(nome);
                notificarTodos(Protocolo.ATUALIZAR, null);
            }
        }
    }

    // ── Notificações push ─────────────────────────────────────────────────

    /** Envia uma notificação visível ao utilizador. */
    public void notificarUtilizador(String nome, String mensagem) {
        PrintWriter pw = clientes.get(nome);
        if (pw != null) enviar(pw, Protocolo.NOTIFICACAO + "|" + mensagem);
    }

    /** Envia mensagem de actualização a todos os clientes excepto um. */
    public void notificarTodos(String msg, String excepto) {
        clientes.forEach((n, pw) -> {
            if (excepto == null || !n.equals(excepto)) enviar(pw, msg);
        });
    }

    /** Devolve true se o utilizador tem sessão TCP activa. */
    public boolean isConectado(String nome) {
        return clientes.containsKey(nome);
    }

    // ── Utilitários ───────────────────────────────────────────────────────

    /** Escrita thread-safe no PrintWriter do cliente. */
    private static void enviar(PrintWriter pw, String msg) {
        synchronized (pw) { pw.println(msg); }
    }

    private static String serializarLivros(List<Livro> lista) {
        StringBuilder sb = new StringBuilder(Protocolo.LIVROS + "|");
        for (int i = 0; i < lista.size(); i++) {
            if (i > 0) sb.append(";");
            sb.append(lista.get(i).toProtocol());
        }
        return sb.toString();
    }

    private static boolean isAdmin(String nome) {
        return nome != null && nome.equalsIgnoreCase("admin");
    }
}
