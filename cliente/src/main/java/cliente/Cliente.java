package cliente;

import shared.Protocolo;

import java.io.*;
import java.net.Socket;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class Cliente {
    private static final int MAX_TENTATIVAS = 5;
    private static final int TIMEOUT_SEG    = 10;

    private Socket socket;
    private PrintWriter saida;
    private final String host;
    private final int porta;

    // Respostas síncronas são entregues aqui pela NotificacaoService
    private final BlockingQueue<String> respostas = new LinkedBlockingQueue<>();

    public Cliente() {
        Properties props = carregarConfig();
        this.host  = props.getProperty("servidor.host", "localhost");
        this.porta = Integer.parseInt(props.getProperty("servidor.porta", "8080"));
    }

    public Cliente(String host, int porta) {
        this.host  = host;
        this.porta = porta;
    }

    private static Properties carregarConfig() {
        Properties props = new Properties();
        try (InputStream is = Cliente.class.getResourceAsStream("/config.properties")) {
            if (is != null) props.load(is);
        } catch (IOException e) {
            System.err.println("[AVISO] config.properties não encontrado, usando localhost:8080");
        }
        return props;
    }

    public void conectar() throws IOException {
        IOException ultimo = null;
        for (int i = 0; i < MAX_TENTATIVAS; i++) {
            try {
                socket = new Socket(host, porta);
                saida  = new PrintWriter(socket.getOutputStream(), true);
                return;
            } catch (IOException e) {
                ultimo = e;
                try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        throw ultimo;
    }

    // Envia um comando e aguarda a resposta síncrona (colocada na fila pela NotificacaoService)
    public String enviar(String comando) {
        saida.println(comando);
        try {
            String r = respostas.poll(TIMEOUT_SEG, TimeUnit.SECONDS);
            return r != null ? r : Protocolo.ERRO + "|Timeout — sem resposta do servidor";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Protocolo.ERRO + "|Interrompido";
        }
    }

    // Chamado pela NotificacaoService para entregar respostas síncronas
    public void receberResposta(String mensagem) {
        respostas.offer(mensagem);
    }

    // Remove mensagens residuais que ficaram na fila após um timeout ou race condition
    public void drenaFila() {
        respostas.clear();
    }

    public InputStream getInputStream() throws IOException {
        return socket.getInputStream();
    }

    public boolean isConectado() {
        return socket != null && !socket.isClosed() && socket.isConnected();
    }

    public void fechar() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }
}
