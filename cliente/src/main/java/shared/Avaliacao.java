package shared;

import java.time.LocalDate;

public class Avaliacao {
    private String utilizador;
    private int    estrelas;    // 1-5
    private String comentario;
    private String data;        // ISO LocalDate (yyyy-MM-dd)

    /** Construtor vazio para Gson */
    public Avaliacao() {}

    public Avaliacao(String utilizador, int estrelas, String comentario) {
        this.utilizador = utilizador;
        this.estrelas   = Math.min(5, Math.max(1, estrelas));
        this.comentario = comentario != null ? comentario.trim() : "";
        this.data       = LocalDate.now().toString();
    }

    public String getUtilizador() { return utilizador; }
    public int    getEstrelas()   { return estrelas; }
    public String getComentario() { return comentario; }
    public String getData()       { return data; }
}
