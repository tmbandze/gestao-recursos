package cliente;

import javafx.application.Platform;
import shared.Protocolo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class NotificacaoService implements Runnable {
    private final BufferedReader entrada;
    private final Cliente cliente;
    private final ControladorPrincipal controlador;
    private volatile boolean ativo = true;

    public NotificacaoService(Cliente cliente, ControladorPrincipal controlador) throws IOException {
        this.cliente     = cliente;
        this.controlador = controlador;
        this.entrada     = new BufferedReader(new InputStreamReader(cliente.getInputStream()));
    }

    @Override
    public void run() {
        try {
            String mensagem;
            while (ativo && (mensagem = entrada.readLine()) != null) {
                final String msg = mensagem;

                if (msg.startsWith(Protocolo.NOTIFICACAO + "|")) {
                    String texto = msg.substring(Protocolo.NOTIFICACAO.length() + 1);
                    Platform.runLater(() -> controlador.mostrarNotificacao(texto));

                } else if (msg.equals(Protocolo.ATUALIZAR)) {
                    Platform.runLater(() -> controlador.notificarAtualizacao());

                } else {
                    // Resposta síncrona — devolve ao Cliente para o enviar() desbloquear
                    cliente.receberResposta(msg);
                }
            }
        } catch (IOException e) {
            if (ativo) Platform.runLater(() -> controlador.mostrarErroConexao());
        }
    }

    public void parar() { ativo = false; }
}
