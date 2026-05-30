package servidor;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;

import javax.crypto.SecretKey;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Utilitário JWT com HMAC-SHA256.
 * A chave secreta é gerada na primeira execução e guardada em data/jwt-secret.key
 * para sobreviver a reinícios do servidor.
 */
public class JwtUtil {

    private static final long EXPIRACAO_MS = 24L * 60 * 60 * 1000; // 24 horas
    private static final String FICHEIRO_CHAVE = "data" + File.separator + "jwt-secret.key";

    private final SecretKey chave;

    public JwtUtil() {
        this.chave = carregarOuGerarChave();
    }

    // ── Geração ──────────────────────────────────────────────────────────────

    /** Gera um JWT assinado com HMAC-SHA256 válido por 24 horas. */
    public String gerarToken(String nome, String email, boolean isAdmin) {
        Date agora  = new Date();
        Date expira = new Date(agora.getTime() + EXPIRACAO_MS);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())   // jti — para blacklist em logout
                .subject(nome)
                .claim("email", email)
                .claim("admin", isAdmin)
                .issuedAt(agora)
                .expiration(expira)
                .signWith(chave)
                .compact();
    }

    // ── Verificação ──────────────────────────────────────────────────────────

    /**
     * Verifica a assinatura e a expiração do token.
     * @return Claims se válido, null se inválido/expirado/malformado.
     */
    public Claims verificar(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            return Jwts.parser()
                    .verifyWith(chave)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Tempo de expiração em ms a partir de agora — para o cliente agendar o refresh. */
    public long msAteExpirar(Claims claims) {
        if (claims == null || claims.getExpiration() == null) return 0;
        return claims.getExpiration().getTime() - System.currentTimeMillis();
    }

    // ── Persistência da chave ─────────────────────────────────────────────

    private SecretKey carregarOuGerarChave() {
        Path path = Path.of(FICHEIRO_CHAVE);
        // Criar directório data/ se não existir
        try { Files.createDirectories(path.getParent()); } catch (IOException ignored) {}

        if (Files.exists(path)) {
            try {
                byte[] bytes = Base64.getDecoder().decode(Files.readString(path).trim());
                return io.jsonwebtoken.security.Keys.hmacShaKeyFor(bytes);
            } catch (Exception e) {
                System.err.println("[JwtUtil] Chave corrompida — a gerar nova: " + e.getMessage());
            }
        }
        // Gerar nova chave HS256
        SecretKey nova = Jwts.SIG.HS256.key().build();
        try {
            Files.writeString(path, Base64.getEncoder().encodeToString(nova.getEncoded()));
            System.out.println("[JwtUtil] Chave JWT gerada e guardada em " + FICHEIRO_CHAVE);
        } catch (IOException e) {
            System.err.println("[JwtUtil] Aviso: não foi possível guardar a chave: " + e.getMessage());
        }
        return nova;
    }
}
