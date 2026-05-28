package shared;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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

    // ── Exemplares ───────────────────────────────────────────────────────
    private int                 totalExemplares   = 1;
    private List<String>        estudantesActuais;
    private java.util.Map<String, String> prazosEstudantes;

    public boolean isDisponivel() {
        return getEstudantesActuais().size() < totalExemplares;
    }

    public int copiasDisponiveis()       { return getTotalExemplares() - getEstudantesActuais().size(); }
    public int getTotalExemplares()      { return totalExemplares < 1 ? 1 : totalExemplares; }
    public void setTotalExemplares(int n){ this.totalExemplares = Math.max(1, n); }

    public List<String> getEstudantesActuais() {
        if (estudantesActuais == null) estudantesActuais = new ArrayList<>();
        return estudantesActuais;
    }

    public java.util.Map<String, String> getPrazosEstudantes() {
        if (prazosEstudantes == null) prazosEstudantes = new java.util.LinkedHashMap<>();
        return prazosEstudantes;
    }

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

    // ── Avaliações ───────────────────────────────────────────────────────
    private List<Avaliacao> avaliacoes;

    public List<Avaliacao> getAvaliacoes() {
        if (avaliacoes == null) avaliacoes = new ArrayList<>();
        return avaliacoes;
    }

    public double mediaEstrelas() {
        List<Avaliacao> a = getAvaliacoes();
        if (a.isEmpty()) return 0.0;
        return a.stream().mapToInt(Avaliacao::getEstrelas).average().orElse(0.0);
    }

    // Aprovação (usada pelo painel admin)
    private boolean pendente       = false;
    private String  relatorioScan;
    public boolean isPendente()               { return pendente; }
    public void    setPendente(boolean p)     { this.pendente = p; }
    public String  getRelatorioScan()         { return relatorioScan; }
    public void    setRelatorioScan(String r) { this.relatorioScan = r; }
}
