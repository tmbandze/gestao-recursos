package shared;

import java.time.LocalDateTime;
import java.util.UUID;

public class MensagemChat {
    private String id;
    private String de;      // remetente
    private String para;    // null = global; ou nome do destinatário
    private String texto;
    private String data;    // ISO datetime

    public MensagemChat() {}

    public MensagemChat(String de, String para, String texto) {
        this.id    = UUID.randomUUID().toString();
        this.de    = de;
        this.para  = (para == null || para.isBlank()) ? null : para;
        this.texto = texto.trim();
        this.data  = LocalDateTime.now().toString();
    }

    public String getId()    { return id; }
    public String getDe()    { return de; }
    public String getPara()  { return para; }
    public String getTexto() { return texto; }
    public String getData()  { return data; }
}
