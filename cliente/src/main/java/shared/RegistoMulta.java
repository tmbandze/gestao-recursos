package shared;

import java.time.LocalDate;

/** Registo de uma multa individual por devolução em atraso. */
public class RegistoMulta {
    private String tituloLivro;
    private int    diasAtraso;
    private double valor;     // euros
    private String data;      // ISO date (yyyy-MM-dd)

    /** Para Gson */
    public RegistoMulta() {}

    public RegistoMulta(String tituloLivro, int diasAtraso, double valor) {
        this.tituloLivro = tituloLivro;
        this.diasAtraso  = diasAtraso;
        this.valor       = Math.round(valor * 100.0) / 100.0;
        this.data        = LocalDate.now().toString();
    }

    public String getTituloLivro() { return tituloLivro; }
    public int    getDiasAtraso()  { return diasAtraso; }
    public double getValor()       { return valor; }
    public String getData()        { return data; }
}
