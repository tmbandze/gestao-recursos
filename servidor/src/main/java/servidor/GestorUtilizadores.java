package servidor;

import shared.Utilizador;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class GestorUtilizadores {
    private final BaseDadosUtilizadores baseDados;
    private List<Utilizador> utilizadores;

    public GestorUtilizadores(BaseDadosUtilizadores baseDados) {
        this.baseDados   = baseDados;
        this.utilizadores = baseDados.carregar();
        criarAdminSeNaoExistir();
    }

    public synchronized Map<String, Object> registar(String nome, String email, String password) {
        if (nome == null || nome.isBlank())     return Map.of("erro", "Nome inválido");
        if (email == null || !email.contains("@")) return Map.of("erro", "Email inválido");
        if (password == null || password.length() < 6) return Map.of("erro", "Password: mínimo 6 caracteres");

        String emailNorm = email.trim().toLowerCase();
        if (utilizadores.stream().anyMatch(u -> u.getEmail().equalsIgnoreCase(emailNorm)))
            return Map.of("erro", "Este email já está registado");

        String salt = gerarSalt();
        String hash = sha256(salt + password);
        Utilizador u = new Utilizador(UUID.randomUUID().toString(), nome.trim(), emailNorm, salt, hash);
        utilizadores.add(u);
        baseDados.guardar(utilizadores);
        return Map.of("ok", true, "nome", u.getNome(), "email", u.getEmail());
    }

    public synchronized List<String> listarNomes() {
        return utilizadores.stream().map(Utilizador::getNome).collect(java.util.stream.Collectors.toList());
    }

    public synchronized Map<String, Object> login(String email, String password) {
        if (email == null || password == null) return Map.of("erro", "Credenciais inválidas");
        String emailNorm = email.trim().toLowerCase();
        Utilizador u = utilizadores.stream()
                .filter(x -> x.getEmail().equalsIgnoreCase(emailNorm))
                .findFirst().orElse(null);

        if (u == null || !sha256(u.getSalt() + password).equals(u.getPasswordHash()))
            return Map.of("erro", "Email ou password incorrectos");

        return Map.of("ok", true, "nome", u.getNome(), "email", u.getEmail());
    }

    // Cria conta admin automática na primeira execução se não existir
    private void criarAdminSeNaoExistir() {
        boolean existe = utilizadores.stream().anyMatch(u -> u.getNome().equalsIgnoreCase("admin"));
        if (existe) return;
        String salt = gerarSalt();
        utilizadores.add(new Utilizador(UUID.randomUUID().toString(), "admin", "admin@biblioteca.local", salt, sha256(salt + "admin123")));
        baseDados.guardar(utilizadores);
        System.out.println("[INFO] Conta admin criada — email: admin@biblioteca.local  password: admin123");
    }

    private static String gerarSalt() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return toHex(bytes);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
