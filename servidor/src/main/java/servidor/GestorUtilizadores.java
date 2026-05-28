package servidor;

import shared.RegistoMulta;
import shared.Utilizador;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
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

        if (u.isBloqueado())
            return Map.of("erro", "Conta bloqueada pelo administrador. Contacte o suporte.");

        return Map.of("ok", true, "nome", u.getNome(), "email", u.getEmail());
    }

    // ── Recuperação de password ───────────────────────────────────────────

    /**
     * Gera um token de recuperação de 8 caracteres e regista-o no utilizador.
     * O token é exibido na consola do servidor (sem SMTP configurado).
     */
    public synchronized Map<String, Object> pedirRecuperacao(String email) {
        if (email == null || email.isBlank()) return Map.of("erro", "Email inválido");
        String emailNorm = email.trim().toLowerCase();
        Utilizador u = utilizadores.stream()
                .filter(x -> x.getEmail().equalsIgnoreCase(emailNorm))
                .findFirst().orElse(null);

        if (u == null) {
            // Resposta genérica para não revelar se o email existe
            return Map.of("ok", true, "mensagem",
                "Se o email estiver registado, receberá as instruções. Contacte o administrador.");
        }

        String token  = gerarTokenReset();
        String expira = LocalDateTime.now().plusHours(2).toString();
        u.setTokenReset(token);
        u.setTokenExpira(expira);
        baseDados.guardar(utilizadores);

        // Sem SMTP: mostrar na consola do servidor para o admin comunicar ao utilizador
        System.out.printf("%n╔══════════════════════════════════════════════════════════╗%n");
        System.out.printf("║  RECUPERAÇÃO DE PASSWORD                                 ║%n");
        System.out.printf("║  Utilizador : %-42s  ║%n", u.getNome());
        System.out.printf("║  Email      : %-42s  ║%n", u.getEmail());
        System.out.printf("║  Token      : %-42s  ║%n", token);
        System.out.printf("║  Válido até : %-42s  ║%n", expira);
        System.out.printf("╚══════════════════════════════════════════════════════════╝%n%n");

        return Map.of("ok", true, "mensagem",
            "Código de recuperação gerado. Contacte o administrador para obter o código "
            + "ou verifique a consola do servidor. Válido por 2 horas.");
    }

    /**
     * Valida o token e redefine a password do utilizador.
     */
    public synchronized Map<String, Object> resetarPassword(String token, String novaPassword) {
        if (token == null || token.isBlank())            return Map.of("erro", "Token inválido");
        if (novaPassword == null || novaPassword.length() < 6)
            return Map.of("erro", "Nova password: mínimo 6 caracteres");

        Utilizador u = utilizadores.stream()
                .filter(x -> token.equals(x.getTokenReset()))
                .findFirst().orElse(null);

        if (u == null) return Map.of("erro", "Token de recuperação inválido ou já utilizado");

        // Verificar expiração
        try {
            LocalDateTime expira = LocalDateTime.parse(u.getTokenExpira());
            if (LocalDateTime.now().isAfter(expira))
                return Map.of("erro", "Token expirado. Solicite um novo código.");
        } catch (Exception e) {
            return Map.of("erro", "Token corrompido. Solicite um novo código.");
        }

        // Actualizar password
        String novoSalt = gerarSalt();
        u.setSalt(novoSalt);
        u.setPasswordHash(sha256(novoSalt + novaPassword));
        u.setTokenReset(null);
        u.setTokenExpira(null);
        baseDados.guardar(utilizadores);

        System.out.printf("[INFO] Password redefinida para o utilizador: %s%n", u.getNome());
        return Map.of("ok", true, "mensagem", "Password redefinida com sucesso! Já pode entrar.");
    }

    /** Devolve lista de pedidos de recuperação activos (token não expirado). */
    public synchronized List<Map<String, Object>> listarRecuperacoesPendentes() {
        LocalDateTime agora = LocalDateTime.now();
        return utilizadores.stream()
            .filter(u -> u.getTokenReset() != null && !u.getTokenReset().isBlank())
            .filter(u -> {
                try { return LocalDateTime.parse(u.getTokenExpira()).isAfter(agora); }
                catch (Exception e) { return false; }
            })
            .map(u -> {
                var m = new java.util.LinkedHashMap<String, Object>();
                m.put("nome",   u.getNome());
                m.put("email",  u.getEmail());
                m.put("token",  u.getTokenReset());
                m.put("expira", u.getTokenExpira());
                return (Map<String, Object>) m;
            })
            .collect(java.util.stream.Collectors.toList());
    }

    // ── Moderação ─────────────────────────────────────────────────────────

    public synchronized void bloquearUtilizador(String nome) {
        utilizadores.stream().filter(u -> u.getNome().equalsIgnoreCase(nome)).findFirst()
            .ifPresent(u -> { u.setBloqueado(true); baseDados.guardar(utilizadores); });
    }

    public synchronized void desbloquearUtilizador(String nome) {
        utilizadores.stream().filter(u -> u.getNome().equalsIgnoreCase(nome)).findFirst()
            .ifPresent(u -> { u.setBloqueado(false); baseDados.guardar(utilizadores); });
    }

    /** Adiciona um aviso ao utilizador e devolve o total de avisos. */
    public synchronized int adicionarAviso(String nome) {
        return utilizadores.stream().filter(u -> u.getNome().equalsIgnoreCase(nome)).findFirst()
            .map(u -> {
                u.setAvisos(u.getAvisos() + 1);
                baseDados.guardar(utilizadores);
                return u.getAvisos();
            }).orElse(0);
    }

    public synchronized int     getAvisos(String nome) {
        return utilizadores.stream().filter(u -> u.getNome().equalsIgnoreCase(nome))
            .findFirst().map(Utilizador::getAvisos).orElse(0);
    }

    public synchronized boolean estaBloqueado(String nome) {
        return utilizadores.stream().filter(u -> u.getNome().equalsIgnoreCase(nome))
            .findFirst().map(Utilizador::isBloqueado).orElse(false);
    }

    // ── Multas por atraso ─────────────────────────────────────────────────

    /** Regista uma multa no histórico do utilizador e incrementa o total. */
    public synchronized void adicionarMulta(String nome, RegistoMulta registo) {
        utilizadores.stream()
            .filter(u -> u.getNome().equals(nome))
            .findFirst()
            .ifPresent(u -> {
                u.setMultaTotal(u.getMultaTotal() + registo.getValor());
                u.getMultas().add(registo);
                baseDados.guardar(utilizadores);
                System.out.printf("[MULTA] %s — %.2f€ (%s, %dd atraso)%n",
                    nome, registo.getValor(), registo.getTituloLivro(), registo.getDiasAtraso());
            });
    }

    /** Devolve o total de multa pendente de um utilizador (0.0 se não existe). */
    public synchronized double getMultaTotal(String nome) {
        return utilizadores.stream()
            .filter(u -> u.getNome().equals(nome))
            .findFirst().map(Utilizador::getMultaTotal).orElse(0.0);
    }

    /** Admin perdoa toda a multa de um utilizador. */
    public synchronized Map<String, Object> perdoarMulta(String nome) {
        Utilizador u = utilizadores.stream()
            .filter(x -> x.getNome().equalsIgnoreCase(nome))
            .findFirst().orElse(null);
        if (u == null) return Map.of("erro", "Utilizador não encontrado");
        double anterior = u.getMultaTotal();
        if (anterior <= 0) return Map.of("erro", "Utilizador não tem multas pendentes");
        u.setMultaTotal(0.0);
        baseDados.guardar(utilizadores);
        System.out.printf("[MULTA] Perdoada multa de %s (%.2f€)%n", u.getNome(), anterior);
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("ok",       true);
        r.put("mensagem", String.format("Multa de %s (%.2f€) perdoada com sucesso.", u.getNome(), anterior));
        return r;
    }

    /** Lista todos os utilizadores com multas pendentes (para o admin). */
    public synchronized java.util.List<Map<String, Object>> listarMultas() {
        return utilizadores.stream()
            .filter(u -> u.getMultaTotal() > 0)
            .map(u -> {
                var m = new java.util.LinkedHashMap<String, Object>();
                m.put("nome",       u.getNome());
                m.put("multaTotal", u.getMultaTotal());
                m.put("multas",     u.getMultas());
                return (Map<String, Object>) m;
            })
            .collect(java.util.stream.Collectors.toList());
    }

    /** Devolve o resumo de multas de um utilizador (para ele próprio ver). */
    public synchronized Map<String, Object> verMultas(String nome) {
        Utilizador u = utilizadores.stream()
            .filter(x -> x.getNome().equals(nome))
            .findFirst().orElse(null);
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        if (u == null) {
            m.put("multaTotal", 0.0);
            m.put("multas", java.util.List.of());
        } else {
            m.put("multaTotal", u.getMultaTotal());
            m.put("multas",     u.getMultas());
        }
        return m;
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

    private static String gerarTokenReset() {
        // Token alfanumérico maiúsculo de 8 caracteres
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // sem I, O, 0, 1 para evitar confusão
        StringBuilder sb = new StringBuilder(8);
        SecureRandom rng = new SecureRandom();
        for (int i = 0; i < 8; i++) sb.append(chars.charAt(rng.nextInt(chars.length())));
        return sb.toString();
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
