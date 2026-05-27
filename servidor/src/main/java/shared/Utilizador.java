package shared;

public class Utilizador {
    private String  id;
    private String  nome;
    private String  email;
    private String  salt;
    private String  passwordHash;

    // Moderação
    private boolean bloqueado   = false;
    private int     avisos      = 0;

    // Recuperação de password
    private String  tokenReset;
    private String  tokenExpira; // ISO-8601 data-hora

    public Utilizador() {}

    public Utilizador(String id, String nome, String email, String salt, String passwordHash) {
        this.id           = id;
        this.nome         = nome;
        this.email        = email;
        this.salt         = salt;
        this.passwordHash = passwordHash;
    }

    // Getters base
    public String getId()           { return id; }
    public String getNome()         { return nome; }
    public String getEmail()        { return email; }
    public String getSalt()         { return salt; }
    public String getPasswordHash() { return passwordHash; }

    // Moderação
    public boolean isBloqueado()              { return bloqueado; }
    public void    setBloqueado(boolean b)    { this.bloqueado = b; }
    public int     getAvisos()               { return avisos; }
    public void    setAvisos(int a)          { this.avisos = a; }

    // Recuperação de password
    public String  getTokenReset()           { return tokenReset; }
    public void    setTokenReset(String t)   { this.tokenReset = t; }
    public String  getTokenExpira()          { return tokenExpira; }
    public void    setTokenExpira(String t)  { this.tokenExpira = t; }

    // Setter para actualizar a password após reset
    public void    setPasswordHash(String h) { this.passwordHash = h; }
    public void    setSalt(String s)         { this.salt = s; }
}
