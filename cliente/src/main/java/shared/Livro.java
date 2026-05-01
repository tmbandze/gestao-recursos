package shared;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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

    public boolean isDisponivel() {
        return estado == EstadoLivro.DISPONIVEL;
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
}
