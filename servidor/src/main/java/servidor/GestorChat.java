package servidor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import shared.MensagemChat;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class GestorChat {

    private static final String FICHEIRO      = "data/chat.json";
    private static final int    MAX_MENSAGENS = 500;

    private final Gson         gson = new GsonBuilder().setPrettyPrinting().create();
    private List<MensagemChat> mensagens;

    public GestorChat() {
        new File("data").mkdirs();
        this.mensagens = carregar();
    }

    // ── Envio ─────────────────────────────────────────────────────────────

    public synchronized MensagemChat enviar(String de, String para, String texto) {
        if (texto == null || texto.isBlank()) return null;
        if (texto.length() > 500) texto = texto.substring(0, 500);
        MensagemChat msg = new MensagemChat(de, para, texto);
        mensagens.add(msg);
        if (mensagens.size() > MAX_MENSAGENS)
            mensagens = new ArrayList<>(
                mensagens.subList(mensagens.size() - MAX_MENSAGENS, mensagens.size()));
        guardar();
        return msg;
    }

    // ── Consulta ──────────────────────────────────────────────────────────

    /** Últimas n mensagens do chat global (para == null). */
    public synchronized List<MensagemChat> listarGlobal(int n) {
        List<MensagemChat> global = mensagens.stream()
            .filter(m -> m.getPara() == null)
            .collect(Collectors.toList());
        int from = Math.max(0, global.size() - n);
        return new ArrayList<>(global.subList(from, global.size()));
    }

    /** Conversa privada entre dois utilizadores (qualquer direcção). */
    public synchronized List<MensagemChat> listarPrivada(String u1, String u2, int n) {
        List<MensagemChat> priv = mensagens.stream()
            .filter(m -> m.getPara() != null)
            .filter(m -> (m.getDe().equalsIgnoreCase(u1) && m.getPara().equalsIgnoreCase(u2))
                      || (m.getDe().equalsIgnoreCase(u2) && m.getPara().equalsIgnoreCase(u1)))
            .collect(Collectors.toList());
        int from = Math.max(0, priv.size() - n);
        return new ArrayList<>(priv.subList(from, priv.size()));
    }

    /** Utilizadores que têm conversas privadas com o admin (para o painel de admin). */
    public synchronized List<String> interlocutoresDoAdmin() {
        return mensagens.stream()
            .filter(m -> m.getPara() != null)
            .filter(m -> m.getDe().equalsIgnoreCase("admin")
                      || m.getPara().equalsIgnoreCase("admin"))
            .map(m -> m.getDe().equalsIgnoreCase("admin") ? m.getPara() : m.getDe())
            .distinct()
            .collect(Collectors.toList());
    }

    // ── Persistência ──────────────────────────────────────────────────────

    private List<MensagemChat> carregar() {
        File f = new File(FICHEIRO);
        if (!f.exists()) return new ArrayList<>();
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            Type tipo = new TypeToken<List<MensagemChat>>() {}.getType();
            List<MensagemChat> lista = gson.fromJson(r, tipo);
            return lista != null ? lista : new ArrayList<>();
        } catch (IOException e) {
            System.err.println("[ERRO] Falha ao carregar chat: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private void guardar() {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(FICHEIRO), StandardCharsets.UTF_8)) {
            gson.toJson(mensagens, w);
        } catch (IOException e) {
            System.err.println("[ERRO] Falha ao guardar chat: " + e.getMessage());
        }
    }
}
