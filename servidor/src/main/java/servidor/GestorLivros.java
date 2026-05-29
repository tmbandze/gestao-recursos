package servidor;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import shared.Avaliacao;
import shared.EstadoLivro;
import shared.Livro;
import shared.RegistoMulta;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

public class GestorLivros {
    private static final String PDF_DIR      = "data/pdfs";
    private static final String COVER_DIR    = "data/capas";
    private static final int    DIAS_PRAZO   = 7;
    /** Valor da multa por cada dia de atraso (em euros). */
    public  static final double MULTA_POR_DIA = 0.50;

    private final BaseDados         baseDados;
    private final GestorHistorico   gestorHistorico;
    private final Servidor          servidor;
    private final AnalisadorConteudo analisador = new AnalisadorConteudo();
    private GestorTCP               gestorTCP;          // injectado após construção
    private GestorUtilizadores      gestorUtilizadores; // injectado após construção
    private GestorEmail             gestorEmail;        // injectado após construção
    private List<Livro> livros;
    private final Map<String, Queue<String>> filasEspera = new HashMap<>();

    public GestorLivros(BaseDados baseDados, GestorHistorico gestorHistorico, Servidor servidor) {
        this.baseDados       = baseDados;
        this.gestorHistorico = gestorHistorico;
        this.servidor        = servidor;
        this.livros          = baseDados.carregar();
        migrarExemplaresAntigos();  // converte estudanteActual → estudantesActuais
        new File(PDF_DIR).mkdirs();
        new File(COVER_DIR).mkdirs();
        gerarCapasEmFalta();   // migração: capas para PDFs já existentes
    }

    /** Migração one-shot: popula estudantesActuais / filasEspera a partir dos dados legacy. */
    private void migrarExemplaresAntigos() {
        for (Livro l : livros) {
            l.migrarExemplaresAntigos();
            // Reconstituir filasEspera em memória (perdem-se no restart)
            l.getFilaEspera().forEach(nome ->
                filasEspera.computeIfAbsent(l.getId(), k -> new LinkedList<>())
                           .add(nome));
        }
        baseDados.guardar(livros); // garante que novos campos ficam no JSON
    }

    /** Injecta o GestorTCP após criação (evita dependência circular). */
    public void setGestorTCP(GestorTCP tcp) { this.gestorTCP = tcp; }

    /** Injecta o GestorUtilizadores após criação (necessário para multas). */
    public void setGestorUtilizadores(GestorUtilizadores gu) { this.gestorUtilizadores = gu; }

    /** Injecta o GestorEmail após criação (para notificações de multa). */
    public void setGestorEmail(GestorEmail ge) { this.gestorEmail = ge; }

    public synchronized List<Livro> listarTodos() {
        // Livros pendentes de aprovação não são visíveis no catálogo público
        return livros.stream().filter(l -> !l.isPendente()).collect(Collectors.toList());
    }

    /** Devolve todos os livros incluindo pendentes — para relatório CSV do admin. */
    public synchronized List<Livro> listarLivros() {
        return java.util.Collections.unmodifiableList(livros);
    }

    public synchronized List<Livro> pesquisar(String termo) {
        if (termo.isBlank()) return listarTodos();
        String t = termo.toLowerCase();
        return livros.stream()
                .filter(l -> !l.isPendente())
                .filter(l -> l.getTitulo().toLowerCase().contains(t)
                          || l.getAutor().toLowerCase().contains(t)
                          || l.getCategoria().toLowerCase().contains(t))
                .collect(Collectors.toList());
    }

    public synchronized Map<String, Object> detalhes(String id) {
        Livro l = buscar(id);
        if (l == null) return Map.of("erro", "Livro não encontrado");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                l.getId());
        m.put("titulo",            l.getTitulo());
        m.put("autor",             l.getAutor());
        m.put("categoria",         l.getCategoria());
        m.put("estado",            l.getEstado().name());
        m.put("totalExemplares",   l.getTotalExemplares());
        m.put("copiasDisponiveis", l.copiasDisponiveis());
        m.put("estudanteActual",   l.getEstudanteActual());          // compat
        m.put("estudantesActuais", l.getEstudantesActuais());        // lista completa
        m.put("prazosEstudantes",  l.getPrazosEstudantes());         // estudante→prazo
        m.put("filaEspera",        l.getFilaEspera());
        m.put("temPdf",            l.isTemPdf());
        m.put("prazoDevolvacao",   l.getPrazoDevolvacao());          // compat
        // Avaliações
        m.put("avaliacoes",        l.getAvaliacoes());
        double media = l.mediaEstrelas();
        m.put("mediaEstrelas",     Math.round(media * 10.0) / 10.0);
        m.put("numAvaliacoes",     l.getAvaliacoes().size());
        return m;
    }

    public synchronized Map<String, Object> inserir(String titulo, String autor, String categoria, String nome, InputStream pdfStream) {
        if (titulo == null || titulo.isBlank()) return Map.of("erro", "Título em falta");
        if (autor  == null || autor.isBlank())  return Map.of("erro", "Autor em falta");
        String id  = UUID.randomUUID().toString();
        String cat = (categoria != null && !categoria.isBlank()) ? categoria.trim() : "Geral";
        Livro livro = new Livro(id, titulo.trim(), autor.trim(), cat);

        if (pdfStream != null) {
            try {
                File pdfFile = new File(PDF_DIR, id + ".pdf");
                Files.copy(pdfStream, pdfFile.toPath());
                livro.setTemPdf(true);
                livro.setUploadPor(nome);

                // ── Análise de conteúdo / vírus ───────────────────────────
                AnalisadorConteudo.ResultadoAnalise resultado = analisador.analisar(pdfFile);
                if (!resultado.aprovado()) {
                    livro.setFlagAdmin(true);
                    livro.setMotivoSuspeicao(resultado.motivo());
                    livro.setRelatorioScan("⚠ CONTEÚDO SUSPEITO: " + resultado.motivo());
                    System.out.printf("[MODERAÇÃO] Livro '%s' sinalizado por: %s (upload por %s)%n",
                        titulo, resultado.motivo(), nome);
                } else {
                    livro.setRelatorioScan("✅ Análise concluída: nenhum problema detectado.");
                    System.out.printf("[MODERAÇÃO] PDF sem problemas: '%s' (upload por %s)%n", titulo, nome);
                }

                // ── Livro fica pendente de aprovação do admin ─────────────
                livro.setPendente(true);

                // ── Notificar admin sobre novo livro pendente ─────────────
                String aviso = livro.isFlagAdmin()
                    ? " 🚨 SUSPEITO: " + resultado.motivo()
                    : "";
                servidor.notificarTodos("pendente_update",
                    "novo_pendente:" + titulo + " (por " + nome + ")" + aviso, "");
                if (gestorTCP != null) {
                    gestorTCP.notificarUtilizador("admin",
                        "⏳ Novo livro pendente de aprovação: \"" + titulo + "\" (upload: " + nome + ")"
                        + (livro.isFlagAdmin() ? " — 🚨 " + resultado.motivo() : ""));
                }

                // ── Extrair capa (1.ª página do PDF) em background ────────
                final File pdfParaCapa = pdfFile;
                final String idParaCapa = id;
                Thread capaThr = new Thread(() -> {
                    try {
                        extrairCapa(pdfParaCapa, new File(COVER_DIR, idParaCapa + ".jpg"));
                    } catch (Exception e) {
                        System.err.println("[CAPA] Falha ao extrair capa de '" + titulo + "': " + e.getMessage());
                    }
                }, "cover-extract");
                capaThr.setDaemon(true);
                capaThr.start();

            } catch (IOException e) {
                System.err.println("[AVISO] Falha ao guardar PDF: " + e.getMessage());
            }
        }

        livros.add(livro);
        baseDados.guardar(livros);

        if (!livro.isPendente()) {
            // Livro sem PDF → disponível imediatamente
            servidor.notificarTodos("atualizacao", "novo_livro", nome);
        }

        String msgRetorno = livro.isPendente()
            ? "Livro submetido! Aguarda aprovação do administrador."
            : "Livro inserido com sucesso!";
        return Map.of("ok", true, "id", id,
            "mensagem", msgRetorno,
            "pendente",  livro.isPendente(),
            "flagAdmin", livro.isFlagAdmin());
    }

    public File getPdfFile(String id) {
        File f = new File(PDF_DIR, id + ".pdf");
        return f.exists() ? f : null;
    }

    public synchronized Map<String, Object> apagar(String id) {
        Livro livro = buscar(id);
        if (livro == null) return Map.of("erro", "Livro não encontrado");
        if (!livro.isDisponivel())
            return Map.of("erro", "Não é possível apagar um livro que está requisitado");
        livros.remove(livro);
        File pdf  = new File(PDF_DIR,   id + ".pdf");
        File capa = new File(COVER_DIR, id + ".jpg");
        if (pdf.exists())  pdf.delete();
        if (capa.exists()) capa.delete();
        baseDados.guardar(livros);
        return Map.of("ok", true, "mensagem", "Livro apagado");
    }

    public synchronized Map<String, Object> requisitar(String id, String nome) {
        Livro livro = buscar(id);
        if (livro == null) return Map.of("erro", "Livro não encontrado");
        if (livro.getEstudantesActuais().contains(nome))
            return Map.of("erro", "Já tens este livro requisitado");

        // Bloquear requisição se o utilizador tiver multas pendentes
        if (gestorUtilizadores != null) {
            double multa = gestorUtilizadores.getMultaTotal(nome);
            if (multa > 0) {
                return Map.of("erro", String.format(
                    "Tens uma multa pendente de %.2f€ por atraso de devolução. " +
                    "Regulariza a situação com o administrador para requisitar novos livros.", multa));
            }
        }

        if (livro.isDisponivel()) {
            String hoje  = LocalDate.now().toString();
            String prazo = LocalDate.now().plusDays(DIAS_PRAZO).toString();
            livro.getEstudantesActuais().add(nome);
            livro.getPrazosEstudantes().put(nome, prazo);
            // Sincronizar campos legacy (primeiro detentor)
            livro.setEstudanteActual(livro.getEstudantesActuais().get(0));
            livro.setDataRequisicao(hoje);
            livro.setPrazoDevolvacao(livro.getPrazosEstudantes().get(livro.getEstudanteActual()));
            // Actualizar estado: REQUISITADO se todas as cópias tomadas
            if (!livro.isDisponivel()) livro.setEstado(EstadoLivro.REQUISITADO);
            gestorHistorico.registarInicio(id, livro.getTitulo(), nome, hoje, prazo);
            baseDados.guardar(livros);
            servidor.notificarTodos("atualizacao", "requisitado", nome);
            String msg = "Livro requisitado! Prazo de devolução: " + formatarData(prazo);
            if (livro.getTotalExemplares() > 1)
                msg += " (" + livro.copiasDisponiveis() + "/" + livro.getTotalExemplares() + " cópias ainda disponíveis)";
            return Map.of("ok", true, "mensagem", msg);
        } else {
            Queue<String> fila = filasEspera.computeIfAbsent(id, k -> new LinkedList<>());
            if (fila.contains(nome)) return Map.of("erro", "Já estás na fila de espera deste livro");
            fila.add(nome);
            if (!livro.getFilaEspera().contains(nome)) livro.getFilaEspera().add(nome);
            baseDados.guardar(livros);
            return Map.of("ok", true, "mensagem", "Adicionado à fila de espera (posição " + fila.size() + ")");
        }
    }

    public synchronized Map<String, Object> devolver(String id, String nome) {
        Livro livro = buscar(id);
        if (livro == null) return Map.of("erro", "Livro não encontrado");
        if (!livro.getEstudantesActuais().contains(nome))
            return Map.of("erro", "Não tens este livro requisitado");

        // Guardar prazo antes de remover
        String prazoStr = livro.getPrazosEstudantes().get(nome);

        // Remover este detentor
        livro.getEstudantesActuais().remove(nome);
        livro.getPrazosEstudantes().remove(nome);
        gestorHistorico.registarFim(id, nome, LocalDate.now().toString());

        // ── Calcular multa por atraso ──────────────────────────────────
        long   diasAtraso  = 0;
        double multaValor  = 0.0;
        if (prazoStr != null && gestorUtilizadores != null) {
            try {
                LocalDate prazo = LocalDate.parse(prazoStr);
                diasAtraso = ChronoUnit.DAYS.between(prazo, LocalDate.now());
                if (diasAtraso > 0) {
                    multaValor = diasAtraso * MULTA_POR_DIA;
                    gestorUtilizadores.adicionarMulta(nome,
                        new RegistoMulta(livro.getTitulo(), (int) diasAtraso, multaValor));
                    // Notificar utilizador da multa aplicada
                    servidor.notificarUsuario(nome, String.format(
                        "💸 Multa aplicada: %.2f€ (%d dia(s) de atraso em \"%s\"). " +
                        "Contacta o administrador para regularizar.",
                        multaValor, diasAtraso, livro.getTitulo()));
                    if (gestorTCP != null) gestorTCP.notificarUtilizador(nome, String.format(
                        "💸 Multa de %.2f€ aplicada (%d dia(s) de atraso em \"%s\").",
                        multaValor, diasAtraso, livro.getTitulo()));
                    servidor.notificarTodos("multa_update",
                        "nova_multa:" + nome + ":" + String.format("%.2f", multaValor), "");
                    // Email de multa aplicada
                    if (gestorEmail != null && gestorUtilizadores != null) {
                        String emailUser = gestorUtilizadores.getEmailPorNome(nome);
                        if (emailUser != null) {
                            final long dA = diasAtraso; final double mV = multaValor;
                            gestorEmail.enviarAsync(emailUser,
                                String.format("💰 Multa de %.2f€ aplicada — \"%s\"", mV, livro.getTitulo()),
                                gestorEmail.htmlMultaAplicada(nome, livro.getTitulo(), mV, dA));
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // Se há fila de espera, promover o próximo para a cópia que ficou livre
        Queue<String> fila = filasEspera.get(id);
        if (fila != null && !fila.isEmpty()) {
            String proximo = fila.poll();
            String hoje  = LocalDate.now().toString();
            String prazo = LocalDate.now().plusDays(DIAS_PRAZO).toString();
            livro.getEstudantesActuais().add(proximo);
            livro.getPrazosEstudantes().put(proximo, prazo);
            livro.getFilaEspera().remove(proximo);
            gestorHistorico.registarInicio(id, livro.getTitulo(), proximo, hoje, prazo);
            servidor.notificarUsuario(proximo,
                "📚 A tua cópia de \"" + livro.getTitulo() + "\" está disponível! Prazo: " + formatarData(prazo));
        }

        // Actualizar campos legacy (primeiro detentor ou null)
        String primeiro = livro.getEstudantesActuais().isEmpty() ? null : livro.getEstudantesActuais().get(0);
        livro.setEstudanteActual(primeiro);
        livro.setPrazoDevolvacao(primeiro != null ? livro.getPrazosEstudantes().get(primeiro) : null);
        if (livro.getEstudantesActuais().isEmpty()) {
            livro.setEstado(EstadoLivro.DISPONIVEL);
            livro.setDataRequisicao(null);
        } else {
            livro.setEstado(EstadoLivro.REQUISITADO); // ainda existem detentores
        }

        baseDados.guardar(livros);
        servidor.notificarTodos("atualizacao", "devolvido", nome);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        if (multaValor > 0) {
            resp.put("mensagem",     String.format(
                "Devolvido com %d dia(s) de atraso. Multa aplicada: %.2f€.", diasAtraso, multaValor));
            resp.put("multaAplicada", multaValor);
            resp.put("diasAtraso",    diasAtraso);
        } else {
            resp.put("mensagem", "Livro devolvido com sucesso");
        }
        return resp;
    }

    public synchronized Map<String, Object> relatorio() {
        var aprovados = livros.stream().filter(l -> !l.isPendente()).collect(Collectors.toList());
        long total    = aprovados.size();
        long req      = aprovados.stream().filter(l -> !l.isDisponivel()).count();
        return Map.of("total", total, "disponiveis", total - req, "requisitados", req);
    }

    public synchronized Map<String, Object> relatorioAdmin() {
        long total = livros.size();
        long req   = livros.stream().filter(l -> !l.isDisponivel()).count();
        List<Map<String, Object>> lista = livros.stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("titulo",          l.getTitulo());
            m.put("autor",           l.getAutor());
            m.put("estado",          l.getEstado().name());
            m.put("estudanteActual", l.getEstudanteActual());
            m.put("filaEspera",      l.getFilaEspera());
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("total", total);
        r.put("disponiveis", total - req);
        r.put("requisitados", req);
        r.put("livros", lista);
        return r;
    }

    /** Devolve todos os livros sinalizados pelo sistema de moderação. */
    public synchronized List<Livro> listarSuspeitos() {
        return livros.stream().filter(Livro::isFlagAdmin).collect(Collectors.toList());
    }

    /** Devolve todos os livros que aguardam aprovação do admin. */
    public synchronized List<Livro> listarPendentes() {
        return livros.stream().filter(Livro::isPendente).collect(Collectors.toList());
    }

    /** Admin aprova um livro pendente — torna-o visível no catálogo. */
    public synchronized Map<String, Object> aprovarLivro(String id) {
        Livro livro = buscar(id);
        if (livro == null)        return Map.of("erro", "Livro não encontrado");
        if (!livro.isPendente())  return Map.of("erro", "Este livro não está pendente");
        livro.setPendente(false);
        baseDados.guardar(livros);
        // Notificar todos os utilizadores sobre o novo livro disponível
        servidor.notificarTodos("atualizacao", "novo_livro", "");
        // Notificar o uploader via SSE e TCP
        if (livro.getUploadPor() != null) {
            String msg = "✅ O teu livro \"" + livro.getTitulo() + "\" foi aprovado e já está disponível na biblioteca!";
            servidor.notificarUsuario(livro.getUploadPor(), msg);
            if (gestorTCP != null) gestorTCP.notificarUtilizador(livro.getUploadPor(), msg);
        }
        return Map.of("ok", true, "mensagem", "Livro \"" + livro.getTitulo() + "\" aprovado e publicado.");
    }

    /**
     * Admin aprova um livro pendente como novo exemplar de um livro já existente.
     * O livro pendente é removido; o existente tem o totalExemplares incrementado.
     */
    public synchronized Map<String, Object> adicionarExemplar(String idPendente, String idExistente) {
        Livro pendente  = buscar(idPendente);
        Livro existente = buscar(idExistente);
        if (pendente  == null) return Map.of("erro", "Livro pendente não encontrado");
        if (existente == null) return Map.of("erro", "Livro existente não encontrado");
        if (!pendente.isPendente()) return Map.of("erro", "Este livro não está pendente");

        int novoTotal = existente.getTotalExemplares() + 1;
        existente.setTotalExemplares(novoTotal);
        existente.setEstado(EstadoLivro.DISPONIVEL); // mais uma cópia disponível

        String uploader = pendente.getUploadPor();
        String titulo   = pendente.getTitulo();
        livros.remove(pendente);
        new File(PDF_DIR,   idPendente + ".pdf").delete();
        new File(COVER_DIR, idPendente + ".jpg").delete();

        baseDados.guardar(livros);
        servidor.notificarTodos("atualizacao", "novo_livro", "");
        servidor.notificarTodos("pendente_update", "aprovado", "");

        if (uploader != null) {
            String msg = "✅ O teu exemplar de \"" + titulo + "\" foi aprovado! "
                + "A obra já existia na biblioteca e agora tem " + novoTotal + " exemplar(es).";
            servidor.notificarUsuario(uploader, msg);
            if (gestorTCP != null) gestorTCP.notificarUtilizador(uploader, msg);
        }
        return Map.of("ok", true,
            "mensagem", "\"" + existente.getTitulo() + "\" agora tem " + novoTotal + " exemplar(es).");
    }

    /**
     * Devolve o primeiro livro aprovado com título e autor semelhantes (case-insensitive),
     * excluindo o próprio livro com {@code excludeId}. Usado para detecção de duplicados.
     */
    public synchronized Livro buscarSimilar(String titulo, String autor, String excludeId) {
        String tN = norm(titulo), aN = norm(autor);
        return livros.stream()
            .filter(l -> !l.isPendente() && !l.getId().equals(excludeId))
            .filter(l -> norm(l.getTitulo()).equals(tN) && norm(l.getAutor()).equals(aN))
            .findFirst().orElse(null);
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    /** Admin rejeita um livro pendente — apaga-o e notifica o uploader. */
    public synchronized Map<String, Object> rejeitarLivro(String id, String motivo) {
        Livro livro = buscar(id);
        if (livro == null)        return Map.of("erro", "Livro não encontrado");
        if (!livro.isPendente())  return Map.of("erro", "Este livro não está pendente");
        String uploader = livro.getUploadPor();
        String titulo   = livro.getTitulo();
        livros.remove(livro);
        File pdf  = new File(PDF_DIR,   id + ".pdf");
        File capa = new File(COVER_DIR, id + ".jpg");
        if (pdf.exists())  pdf.delete();
        if (capa.exists()) capa.delete();
        baseDados.guardar(livros);
        // Notificar o uploader
        if (uploader != null) {
            String msg = "❌ O teu livro \"" + titulo + "\" foi rejeitado pelo administrador."
                + (motivo != null && !motivo.isBlank() ? " Motivo: " + motivo : "");
            servidor.notificarUsuario(uploader, msg);
            if (gestorTCP != null) gestorTCP.notificarUtilizador(uploader, msg);
        }
        return Map.of("ok", true, "mensagem", "Livro \"" + titulo + "\" rejeitado e removido.");
    }

    /** Devolve o ficheiro JPEG da capa, ou null se não existir. */
    public File getCoverFile(String id) {
        File f = new File(COVER_DIR, id + ".jpg");
        return f.exists() ? f : null;
    }

    /** Gera capas em background para livros que já têm PDF mas ainda sem capa. */
    private void gerarCapasEmFalta() {
        Thread t = new Thread(() -> {
            int count = 0;
            for (Livro l : new ArrayList<>(livros)) {
                if (!l.isTemPdf()) continue;
                File capaFile = new File(COVER_DIR, l.getId() + ".jpg");
                if (capaFile.exists()) continue;
                File pdfFile  = new File(PDF_DIR,   l.getId() + ".pdf");
                if (!pdfFile.exists()) continue;
                try {
                    extrairCapa(pdfFile, capaFile);
                    count++;
                } catch (Exception e) {
                    System.err.println("[CAPA] Falha na migração de '" + l.getTitulo() + "': " + e.getMessage());
                }
            }
            if (count > 0)
                System.out.println("[CAPA] Capas geradas para " + count + " livro(s) já existente(s).");
        }, "cover-migration");
        t.setDaemon(true);
        t.start();
    }

    /** Renderiza a 1.ª página do PDF para JPEG (96 DPI, max 480 px de largura). */
    private static void extrairCapa(File pdfFile, File capaFile) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            BufferedImage img = renderer.renderImageWithDPI(0, 96, ImageType.RGB);

            // Redimensionar para max 480 px de largura (mantém proporção)
            int maxW = 480;
            if (img.getWidth() > maxW) {
                int newH = (int) (img.getHeight() * ((double) maxW / img.getWidth()));
                java.awt.image.BufferedImage resized =
                    new java.awt.image.BufferedImage(maxW, newH, java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g2 = resized.createGraphics();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(img, 0, 0, maxW, newH, null);
                g2.dispose();
                img = resized;
            }
            ImageIO.write(img, "JPEG", capaFile);
        }
    }

    // ── Avaliações ────────────────────────────────────────────────────────

    /**
     * Regista ou actualiza a avaliação de um utilizador num livro.
     * Cada utilizador tem apenas uma avaliação por livro — nova submissão substitui a anterior.
     */
    public synchronized Map<String, Object> avaliar(String id, String utilizador, int estrelas, String comentario) {
        Livro livro = buscar(id);
        if (livro == null)      return Map.of("erro", "Livro não encontrado");
        if (livro.isPendente()) return Map.of("erro", "Livro não disponível");
        if (estrelas < 1 || estrelas > 5)
            return Map.of("erro", "Classificação inválida — escolhe entre 1 e 5 estrelas");

        // Remove avaliação anterior (se existir) e adiciona a nova
        livro.getAvaliacoes().removeIf(a -> a.getUtilizador().equals(utilizador));
        livro.getAvaliacoes().add(new Avaliacao(utilizador, estrelas, comentario));
        baseDados.guardar(livros);

        double media = livro.mediaEstrelas();
        return Map.of(
            "ok",       true,
            "mensagem", "Avaliação registada!",
            "media",    Math.round(media * 10.0) / 10.0,
            "total",    livro.getAvaliacoes().size()
        );
    }

    /** Devolve todas as avaliações de um livro com média calculada. */
    public synchronized Map<String, Object> listarAvaliacoes(String id) {
        Livro livro = buscar(id);
        if (livro == null) return Map.of("erro", "Livro não encontrado");
        double media = livro.mediaEstrelas();
        return Map.of(
            "avaliacoes", livro.getAvaliacoes(),
            "media",      Math.round(media * 10.0) / 10.0,
            "total",      livro.getAvaliacoes().size()
        );
    }

    /**
     * Remove a avaliação de um utilizador num livro.
     * Pode ser chamado pelo próprio utilizador ou pelo admin.
     */
    public synchronized Map<String, Object> apagarAvaliacao(String id, String utilizador) {
        Livro livro = buscar(id);
        if (livro == null) return Map.of("erro", "Livro não encontrado");
        boolean removed = livro.getAvaliacoes().removeIf(a -> a.getUtilizador().equals(utilizador));
        if (!removed) return Map.of("erro", "Avaliação não encontrada");
        baseDados.guardar(livros);
        return Map.of("ok", true, "mensagem", "Avaliação removida");
    }

    // ─────────────────────────────────────────────────────────────────────

    private Livro buscar(String id) {
        return livros.stream().filter(l -> l.getId().equals(id)).findFirst().orElse(null);
    }

    private static String formatarData(String iso) {
        try {
            LocalDate d = LocalDate.parse(iso);
            return d.getDayOfMonth() + "/" + d.getMonthValue() + "/" + d.getYear();
        } catch (Exception e) {
            return iso;
        }
    }
}
