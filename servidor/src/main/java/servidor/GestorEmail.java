package servidor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jakarta.mail.*;
import jakarta.mail.internet.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gere o envio de emails transacionais da biblioteca.
 * A configuração SMTP é guardada em data/email-config.json.
 * Todos os envios são assíncronos para não bloquear o thread do servidor.
 */
public class GestorEmail {

    private static final String FICHEIRO_CONFIG = "data/email-config.json";
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private ConfigEmail config;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "email-sender");
        t.setDaemon(true);
        return t;
    });

    public GestorEmail() {
        new File("data").mkdirs();
        this.config = carregarConfig();
    }

    /** Verifica se o email está configurado e activo. */
    public boolean isConfigurado() {
        return config != null && config.ativo
            && config.smtp != null && !config.smtp.isBlank()
            && config.utilizador != null && !config.utilizador.isBlank()
            && config.password != null && !config.password.isBlank();
    }

    /** Envia de forma assíncrona (não bloqueia). Silencia erros no log. */
    public void enviarAsync(String para, String assunto, String corpoHtml) {
        if (!isConfigurado() || para == null || para.isBlank()) return;
        executor.submit(() -> {
            try {
                enviarInterno(para, assunto, corpoHtml);
            } catch (Exception e) {
                System.err.printf("[EMAIL] Falha ao enviar para %s: %s%n", para, e.getMessage());
            }
        });
    }

    /** Envia um email de teste síncrono — devolve "ok" ou a mensagem de erro. */
    public String testar(String para) {
        if (!isConfigurado()) return "Email não está configurado ou não está activo";
        try {
            enviarInterno(para, "✅ Teste — Biblioteca Digital", htmlTeste());
            return "ok";
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    public ConfigEmail getConfig() { return config != null ? config : new ConfigEmail(); }

    public void guardarConfig(ConfigEmail cfg) {
        this.config = cfg;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(FICHEIRO_CONFIG), StandardCharsets.UTF_8)) {
            gson.toJson(cfg, w);
        } catch (IOException e) {
            System.err.println("[EMAIL] Erro ao guardar config: " + e.getMessage());
        }
    }

    // ── Implementação SMTP ─────────────────────────────────────────────────

    private void enviarInterno(String para, String assunto, String corpoHtml)
            throws MessagingException, UnsupportedEncodingException {

        Properties props = new Properties();
        props.put("mail.smtp.host",  config.smtp);
        props.put("mail.smtp.port",  String.valueOf(config.porta));
        props.put("mail.smtp.auth",  "true");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout",           "10000");
        if (config.ssl) {
            props.put("mail.smtp.ssl.enable",          "true");
            props.put("mail.smtp.ssl.checkserveridentity", "false");
        } else {
            props.put("mail.smtp.starttls.enable",     "true");
            props.put("mail.smtp.starttls.required",   "false");
        }

        final String user = config.utilizador;
        final String pass = config.password;

        Session session = Session.getInstance(props, new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(user, pass);
            }
        });

        Message msg = new MimeMessage(session);
        String nome = (config.nomeRemetente != null && !config.nomeRemetente.isBlank())
            ? config.nomeRemetente : "Biblioteca Digital";
        msg.setFrom(new InternetAddress(user, nome, "UTF-8"));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(para));
        msg.setSubject(assunto);
        msg.setContent(corpoHtml, "text/html; charset=UTF-8");

        Transport.send(msg);
        System.out.printf("[EMAIL] ✓ Enviado para %s — %s%n", para, assunto);
    }

    private ConfigEmail carregarConfig() {
        File f = new File(FICHEIRO_CONFIG);
        if (!f.exists()) return new ConfigEmail();
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            ConfigEmail cfg = gson.fromJson(r, ConfigEmail.class);
            return cfg != null ? cfg : new ConfigEmail();
        } catch (IOException e) {
            return new ConfigEmail();
        }
    }

    // ── Templates HTML ─────────────────────────────────────────────────────

    public String htmlBemVindo(String nome) {
        return template("📚 Bem-vindo(a) à Biblioteca Digital!",
            "<h2>Olá, " + esc(nome) + "! 👋</h2>" +
            "<p>A tua conta na <strong>Biblioteca Digital</strong> foi criada com sucesso.</p>" +
            "<p>Podes agora:</p>" +
            "<ul>" +
            "  <li>📖 Pesquisar e requisitar livros</li>" +
            "  <li>📚 Acompanhar o teu histórico de empréstimos</li>" +
            "  <li>💬 Usar o chat em tempo real</li>" +
            "  <li>⭐ Avaliar e comentar livros</li>" +
            "</ul>" +
            "<p>Boas leituras!</p>");
    }

    public String htmlLembrete(String nome, String titulo, String prazo) {
        return template("⏰ Lembrete: devolução amanhã",
            "<h2>Olá, " + esc(nome) + "!</h2>" +
            "<p>Este é um lembrete de que o livro:</p>" +
            "<blockquote style='border-left:4px solid #3498db;padding:10px 15px;color:#333'>" +
            "  <strong>" + esc(titulo) + "</strong>" +
            "</blockquote>" +
            "<p>deve ser devolvido <strong>amanhã (" + esc(prazo) + ")</strong>.</p>" +
            "<p>Por favor não te esqueças de o devolver para evitar multas (0,50€ por dia de atraso).</p>");
    }

    public String htmlPrazoHoje(String nome, String titulo, String prazo) {
        return template("📅 URGENTE: devolução hoje!",
            "<h2>Olá, " + esc(nome) + "!</h2>" +
            "<p>O prazo de devolução do livro:</p>" +
            "<blockquote style='border-left:4px solid #e74c3c;padding:10px 15px;color:#333'>" +
            "  <strong>" + esc(titulo) + "</strong>" +
            "</blockquote>" +
            "<p>termina <strong>HOJE (" + esc(prazo) + ")</strong>.</p>" +
            "<p style='color:#e74c3c'><strong>⚠️ Devolve hoje para evitar multa de 0,50€ por cada dia de atraso!</strong></p>");
    }

    public String htmlAtraso(String nome, String titulo, long dias, double multa) {
        return template("⚠️ Livro em atraso — multa acumulada",
            "<h2>Atenção, " + esc(nome) + "!</h2>" +
            "<p>O livro:</p>" +
            "<blockquote style='border-left:4px solid #e74c3c;padding:10px 15px;color:#333'>" +
            "  <strong>" + esc(titulo) + "</strong>" +
            "</blockquote>" +
            "<p>está em atraso há <strong>" + dias + " dia(s)</strong>.</p>" +
            "<p>Multa acumulada até ao momento: " +
            "<strong style='color:#e74c3c;font-size:18px'>" + String.format("%.2f€", multa) + "</strong></p>" +
            "<p>Por favor devolve o livro o mais brevemente possível para não aumentar a multa.</p>");
    }

    public String htmlMultaAplicada(String nome, String titulo, double multa, long dias) {
        return template("💰 Multa aplicada na devolução",
            "<h2>Olá, " + esc(nome) + "!</h2>" +
            "<p>O livro <strong>" + esc(titulo) + "</strong> foi devolvido com " +
            "<strong>" + dias + " dia(s) de atraso</strong>.</p>" +
            "<p>Foi aplicada uma multa de:</p>" +
            "<p style='text-align:center;font-size:28px;font-weight:bold;color:#e74c3c'>" +
            String.format("%.2f€", multa) + "</p>" +
            "<p>Por favor liquida a multa junto do administrador para poderes requisitar novos livros.</p>");
    }

    public String htmlRecuperacao(String nome, String token) {
        return template("🔑 Recuperação de password",
            "<h2>Olá, " + esc(nome) + "!</h2>" +
            "<p>Recebemos um pedido de recuperação de password para a tua conta.</p>" +
            "<p>O teu código de recuperação é:</p>" +
            "<p style='text-align:center;font-size:32px;font-weight:bold;letter-spacing:6px;" +
            "background:#f0f0f0;padding:15px;border-radius:8px;color:#2c3e50'>" + token + "</p>" +
            "<p>Este código é válido durante <strong>2 horas</strong>.</p>" +
            "<p style='color:#aaa;font-size:13px'>Se não solicitaste este código, ignora este email.</p>");
    }

    private String htmlTeste() {
        return template("✅ Configuração funcionando!",
            "<h2>Tudo a funcionar! 🎉</h2>" +
            "<p>O sistema de notificações por email da <strong>Biblioteca Digital</strong> " +
            "está configurado correctamente.</p>" +
            "<p>Os utilizadores vão receber emails automáticos para:</p>" +
            "<ul>" +
            "  <li>📧 Boas-vindas ao registar</li>" +
            "  <li>⏰ Lembretes antes do prazo de devolução</li>" +
            "  <li>⚠️ Alertas de atraso com multa</li>" +
            "  <li>🔑 Recuperação de password</li>" +
            "</ul>");
    }

    private static String template(String titulo, String conteudo) {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
            "<style>" +
            "  body{font-family:Arial,sans-serif;background:#f0f4f8;margin:0;padding:20px}" +
            "  .card{background:#fff;border-radius:10px;padding:32px;max-width:520px;" +
            "        margin:0 auto;box-shadow:0 4px 16px rgba(0,0,0,.08)}" +
            "  h1{color:#3498db;font-size:16px;margin:0 0 4px}" +
            "  h2{color:#2c3e50;font-size:20px;margin-top:0}" +
            "  p,li{color:#555;line-height:1.7;font-size:15px}" +
            "  blockquote{margin:12px 0;background:#f8f9fa;border-radius:4px}" +
            "  .footer{text-align:center;color:#bbb;font-size:12px;margin-top:24px;padding-top:16px;" +
            "          border-top:1px solid #eee}" +
            "</style></head><body>" +
            "<div class='card'>" +
            "<h1>📚 Biblioteca Digital</h1>" +
            "<hr style='border:none;border-top:3px solid #3498db;margin:8px 0 20px'>" +
            conteudo +
            "<div class='footer'>Email automático — por favor não responda a esta mensagem.</div>" +
            "</div></body></html>";
    }

    /** Escapa caracteres especiais HTML para evitar injecção. */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    // ── DTO de configuração ────────────────────────────────────────────────

    public static class ConfigEmail {
        public String  smtp          = "";
        public int     porta         = 587;
        public String  utilizador    = "";
        public String  password      = "";
        public String  nomeRemetente = "Biblioteca Digital";
        public boolean ssl           = false;
        public boolean ativo         = false;
    }
}
