package shared;

import java.util.ArrayList;
import java.util.List;

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

    // Multas por atraso
    private double          multaTotal = 0.0;    // total em euros em dívida
    private List<RegistoMulta> multas;           // histórico de multas aplicadas

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

    // Multas
    public double getMultaTotal()            { return multaTotal < 0 ? 0 : multaTotal; }
    public void   setMultaTotal(double v)    { this.multaTotal = Math.max(0, v); }
    public List<RegistoMulta> getMultas() {
        if (multas == null) multas = new ArrayList<>();
        return multas;
    }
}
