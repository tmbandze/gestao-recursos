package shared;

public class Utilizador {
    private String id;
    private String nome;
    private String email;
    private String salt;
    private String passwordHash;

    public Utilizador() {}

    public Utilizador(String id, String nome, String email, String salt, String passwordHash) {
        this.id           = id;
        this.nome         = nome;
        this.email        = email;
        this.salt         = salt;
        this.passwordHash = passwordHash;
    }

    public String getId()           { return id; }
    public String getNome()         { return nome; }
    public String getEmail()        { return email; }
    public String getSalt()         { return salt; }
    public String getPasswordHash() { return passwordHash; }
}
