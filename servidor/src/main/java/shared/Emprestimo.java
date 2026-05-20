package shared;

public class Emprestimo {
    private String id;
    private String idLivro;
    private String tituloLivro;
    private String estudante;
    private String dataInicio;
    private String prazo;
    private String dataFim; // null = ainda activo

    public Emprestimo() {}

    public Emprestimo(String id, String idLivro, String tituloLivro,
                      String estudante, String dataInicio, String prazo) {
        this.id          = id;
        this.idLivro     = idLivro;
        this.tituloLivro = tituloLivro;
        this.estudante   = estudante;
        this.dataInicio  = dataInicio;
        this.prazo       = prazo;
    }

    public String getId()          { return id; }
    public String getIdLivro()     { return idLivro; }
    public String getTituloLivro() { return tituloLivro; }
    public String getEstudante()   { return estudante; }
    public String getDataInicio()  { return dataInicio; }
    public String getPrazo()       { return prazo; }
    public String getDataFim()     { return dataFim; }
    public void   setDataFim(String dataFim) { this.dataFim = dataFim; }
}
