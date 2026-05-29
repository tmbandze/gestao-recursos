package servidor;

import shared.Emprestimo;
import shared.Livro;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Motor de recomendações de livros.
 *
 * Combina 4 sinais, cada um com peso próprio:
 *
 *  1. Categoria favorita  — a categoria mais vezes requisitada pelo utilizador
 *  2. Avaliação           — livros com ≥ 3,5 estrelas de média
 *  3. Collaborative       — livros lidos por utilizadores com gostos semelhantes
 *  4. Popularidade global — vezes totais que o livro foi requisitado
 *
 * Para utilizadores novos (sem histórico) devolve os mais bem avaliados e
 * mais populares.
 */
public class GestorRecomendacoes {

    private final GestorHistorico gestorHistorico;
    private final GestorLivros    gestorLivros;

    public GestorRecomendacoes(GestorHistorico gestorHistorico, GestorLivros gestorLivros) {
        this.gestorHistorico = gestorHistorico;
        this.gestorLivros    = gestorLivros;
    }

    /**
     * Devolve até {@code max} recomendações para {@code nome}.
     * Cada item contém: id, titulo, autor, categoria, estado, totalExemplares,
     * copiasDisponiveis, mediaEstrelas, numAvaliacoes, temPdf, motivo.
     */
    public List<Map<String, Object>> recomendar(String nome, int max) {

        // ── Dados base ────────────────────────────────────────────────
        List<Emprestimo> histUser = gestorHistorico.porEstudante(nome);
        List<Emprestimo> todos    = gestorHistorico.listarTodos();
        List<Livro>      catalogo = gestorLivros.listarTodos();   // sem pendentes

        // IDs de livros que o utilizador já leu (alguma vez)
        Set<String> idsLidos = histUser.stream()
            .map(Emprestimo::getIdLivro)
            .collect(Collectors.toSet());

        // ── Categoria favorita ────────────────────────────────────────
        // Mapear idLivro → categoria (dos livros lidos)
        Map<String, String> idParaCat = new HashMap<>();
        catalogo.forEach(l -> idParaCat.put(l.getId(), l.getCategoria()));

        // Frequência de cada categoria no histórico do utilizador
        Map<String, Long> freqCat = histUser.stream()
            .map(e -> idParaCat.get(e.getIdLivro()))
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(c -> c.toLowerCase(), Collectors.counting()));

        List<String> catsOrdenadas = freqCat.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());

        // ── Collaborative filtering ───────────────────────────────────
        // Utilizadores que partilham pelo menos 1 livro com o utilizador actual
        Set<String> coLeitores = todos.stream()
            .filter(e -> idsLidos.contains(e.getIdLivro())
                      && !e.getEstudante().equalsIgnoreCase(nome))
            .map(Emprestimo::getEstudante)
            .collect(Collectors.toSet());

        // Contagem: quantos co-leitores leram cada livro que o utilizador ainda não leu
        Map<String, Long> scoreColab = todos.stream()
            .filter(e -> coLeitores.contains(e.getEstudante()))
            .filter(e -> !idsLidos.contains(e.getIdLivro()))
            .collect(Collectors.groupingBy(Emprestimo::getIdLivro, Collectors.counting()));

        // ── Popularidade global ───────────────────────────────────────
        Map<String, Long> popularidade = todos.stream()
            .collect(Collectors.groupingBy(Emprestimo::getIdLivro, Collectors.counting()));

        // ── Calcular score para cada candidato ────────────────────────
        List<Map<String, Object>> resultados = new ArrayList<>();

        for (Livro livro : catalogo) {
            // Excluir livros já lidos ou actualmente requisitados por este utilizador
            if (idsLidos.contains(livro.getId())) continue;
            if (livro.getEstudantesActuais().contains(nome)) continue;

            double score  = 0;
            String motivo = null;

            // 1. Categoria favorita
            double sCat = 0;
            for (int i = 0; i < Math.min(catsOrdenadas.size(), 3); i++) {
                if (catsOrdenadas.get(i).equalsIgnoreCase(livro.getCategoria())) {
                    sCat = switch (i) { case 0 -> 5.0; case 1 -> 3.0; default -> 1.5; };
                    break;
                }
            }
            if (sCat > 0) {
                score  += sCat;
                motivo  = "Porque gostas de " + livro.getCategoria();
            }

            // 2. Avaliação média
            double media = livro.mediaEstrelas();
            double sAval = 0;
            if      (media >= 4.5) sAval = 4.0;
            else if (media >= 4.0) sAval = 3.0;
            else if (media >= 3.5) sAval = 2.0;
            else if (media >= 3.0) sAval = 1.0;
            if (sAval > 0) {
                score += sAval;
                if (motivo == null || sAval > sCat)
                    motivo = String.format("Bem avaliado (%.1f ⭐)", media);
            }

            // 3. Collaborative filtering
            long  vColab = scoreColab.getOrDefault(livro.getId(), 0L);
            double sColab = Math.min(vColab * 0.7, 4.0);
            if (sColab > 0) {
                score += sColab;
                if (motivo == null || sColab > sCat && sColab > sAval)
                    motivo = "Leitores com gostos semelhantes também leram";
            }

            // 4. Popularidade
            long   vezes = popularidade.getOrDefault(livro.getId(), 0L);
            double sPop  = Math.min(vezes * 0.25, 2.0);
            if (sPop > 0) {
                score += sPop;
                if (motivo == null) motivo = "Popular na biblioteca";
            }

            // Utilizador novo sem histórico: mostrar catálogo ordenado por avaliação+popularidade
            if (idsLidos.isEmpty()) {
                score = sAval * 1.5 + sPop;
                if (score == 0) score = 0.01; // incluir mesmo livros sem dados
                motivo = media >= 3.0
                    ? String.format("Bem avaliado (%.1f ⭐)", media)
                    : "Popular na biblioteca";
            }

            if (score <= 0) continue; // sem ligação ao utilizador — não recomendar

            // Boost ligeiro se o livro está disponível imediatamente
            if (livro.isDisponivel()) score += 0.3;

            if (motivo == null) motivo = "Pode interessar-te";

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",               livro.getId());
            m.put("titulo",           livro.getTitulo());
            m.put("autor",            livro.getAutor());
            m.put("categoria",        livro.getCategoria());
            m.put("estado",           livro.getEstado().name());
            m.put("totalExemplares",  livro.getTotalExemplares());
            m.put("copiasDisponiveis",livro.copiasDisponiveis());
            m.put("mediaEstrelas",    Math.round(media * 10.0) / 10.0);
            m.put("numAvaliacoes",    livro.getAvaliacoes().size());
            m.put("temPdf",           livro.isTemPdf());
            m.put("motivo",           motivo);
            m.put("_score",           score);  // removido antes de retornar
            resultados.add(m);
        }

        // Ordenar por score e cortar
        resultados.sort(Comparator.comparingDouble(m -> -((double) m.get("_score"))));
        resultados.forEach(m -> m.remove("_score"));
        return resultados.stream().limit(max).collect(Collectors.toList());
    }
}
