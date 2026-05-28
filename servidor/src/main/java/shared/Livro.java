package shared;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class Livro {
    private String id;
    private String titulo;
    private String autor;
    private String categoria;
    private EstadoLivro estado;
    private String estudanteActual;
    private List<String> filaEspera;
    private String dataInsercao;

    public Livro(String id, String titulo, String autor, String categoria) {
        this.id = id;
        this.titulo = titulo;
        this.autor = autor;
        this.categoria = categoria;
        this.estado = EstadoLivro.DISPONIVEL;
        this.estudanteActual = null;
        this.filaEspera = new ArrayList<>();
        this.dataInsercao = LocalDateTime.now().toString();
    }

    // Construtor para o cliente (parse do protocolo)
    public Livro(String id, String titulo, String autor, String categoria, String estado) {
        this(id, titulo, autor, categoria);
        this.estado = EstadoLivro.valueOf(estado);
    }

    public String toProtocol() {
        return id + "," + titulo + "," + autor + "," + categoria + "," + estado.name();
    }

    // ── Exemplares (múltiplas cópias físicas) ────────────────────────────
    private int                 totalExemplares   = 1;
    private List<String>        estudantesActuais;    // todos os detentores actuais
    private Map<String, String> prazosEstudantes;     // estudante → prazo

    public boolean isDisponivel() {
        return getEstudantesActuais().size() < totalExemplares;
    }

    /** Número de cópias ainda disponíveis para requisição. */
    public int copiasDisponiveis() {
        return getTotalExemplares() - getEstudantesActuais().size();
    }

    /** Nunca devolve menos de 1 — protege contra Gson que ignora o inicializador. */
    public int  getTotalExemplares()     { return totalExemplares < 1 ? 1 : totalExemplares; }
    public void setTotalExemplares(int n){ this.totalExemplares = Math.max(1, n); }

    public List<String> getEstudantesActuais() {
        if (estudantesActuais == null) estudantesActuais = new ArrayList<>();
        return estudantesActuais;
    }

    public Map<String, String> getPrazosEstudantes() {
        if (prazosEstudantes == null) prazosEstudantes = new java.util.LinkedHashMap<>();
        return prazosEstudantes;
    }

    /**
     * Migração one-shot: converte o campo legado {@code estudanteActual} (String)
     * para a nova lista {@code estudantesActuais}. Seguro chamar múltiplas vezes.
     */
    public void migrarExemplaresAntigos() {
        if (totalExemplares < 1) totalExemplares = 1;   // corrige Gson que ignora inicializador
        if (estudantesActuais == null) estudantesActuais = new ArrayList<>();
        if (prazosEstudantes  == null) prazosEstudantes  = new java.util.LinkedHashMap<>();
        if (estudantesActuais.isEmpty() && estudanteActual != null) {
            estudantesActuais.add(estudanteActual);
            if (prazoDevolvacao != null) prazosEstudantes.put(estudanteActual, prazoDevolvacao);
        }
    }

    private boolean temPdf          = false;
    private String  uploadPor;
    private String  dataRequisicao;
    private String  prazoDevolvacao;

    // ── Avaliações ───────────────────────────────────────────────────────
    private List<Avaliacao> avaliacoes;

    public List<Avaliacao> getAvaliacoes() {
        if (avaliacoes == null) avaliacoes = new ArrayList<>();
        return avaliacoes;
    }

    /** Média das estrelas (0.0 se não há avaliações). */
    public double mediaEstrelas() {
        List<Avaliacao> a = getAvaliacoes();
        if (a.isEmpty()) return 0.0;
        return a.stream().mapToInt(Avaliacao::getEstrelas).average().orElse(0.0);
    }

    // Moderação de conteúdo
    private boolean flagAdmin       = false;   // true = conteúdo sinalizado
    private String  motivoSuspeicao;           // motivo do flag

    // Aprovação pelo administrador
    private boolean pendente        = false;   // true = aguarda aprovação do admin
    private String  relatorioScan;             // resumo da análise de conteúdo

    public String getId() { return id; }
    public String getTitulo() { return titulo; }
    public String getAutor() { return autor; }
    public String getCategoria() { return categoria; }
    public EstadoLivro getEstado() { return estado; }
    public void setEstado(EstadoLivro estado) { this.estado = estado; }
    public String getEstudanteActual() { return estudanteActual; }
    public void setEstudanteActual(String estudanteActual) { this.estudanteActual = estudanteActual; }
    public List<String> getFilaEspera() { return filaEspera != null ? filaEspera : new ArrayList<>(); }
    public void setFilaEspera(List<String> filaEspera) { this.filaEspera = filaEspera; }
    public String getDataInsercao() { return dataInsercao; }
    public boolean isTemPdf() { return temPdf; }
    public void setTemPdf(boolean temPdf) { this.temPdf = temPdf; }
    public String getUploadPor() { return uploadPor; }
    public void setUploadPor(String uploadPor) { this.uploadPor = uploadPor; }
    public String getDataRequisicao() { return dataRequisicao; }
    public void setDataRequisicao(String dataRequisicao) { this.dataRequisicao = dataRequisicao; }
    public String getPrazoDevolvacao() { return prazoDevolvacao; }
    public void setPrazoDevolvacao(String prazoDevolvacao) { this.prazoDevolvacao = prazoDevolvacao; }

    // Moderação
    public boolean isFlagAdmin()                    { return flagAdmin; }
    public void    setFlagAdmin(boolean f)          { this.flagAdmin = f; }
    public String  getMotivoSuspeicao()             { return motivoSuspeicao; }
    public void    setMotivoSuspeicao(String m)     { this.motivoSuspeicao = m; }

    // Aprovação
    public boolean isPendente()                     { return pendente; }
    public void    setPendente(boolean p)           { this.pendente = p; }
    public String  getRelatorioScan()               { return relatorioScan; }
    public void    setRelatorioScan(String r)       { this.relatorioScan = r; }
}
