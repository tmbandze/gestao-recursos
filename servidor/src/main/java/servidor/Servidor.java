package servidor;

import shared.Protocolo;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class Servidor {
    private static final int PORTA = 8080;

    private final List<GestorClientes> clientes = Collections.synchronizedList(new ArrayList<>());
    private final GestorLivros gestorLivros;
    private final Logger logger;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public Servidor() {
        BaseDados bd = new BaseDados();
        gestorLivros = new GestorLivros(bd);
        logger = new Logger();
    }

    public void iniciar() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(PORTA)) {
            System.out.println("[INFO] Servidor iniciado na porta " + PORTA);
            System.out.println("[INFO] A aguardar conexões...");
            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("[INFO] Novo cliente: " + socket.getInetAddress());
                try {
                    GestorClientes handler = new GestorClientes(socket, gestorLivros, logger, this);
                    clientes.add(handler);
                    executor.submit(handler);
                } catch (IOException e) {
                    System.err.println("[ERRO] Falha ao criar handler: " + e.getMessage());
                }
            }
        }
    }

    public void removerCliente(GestorClientes cliente) {
        clientes.remove(cliente);
        System.out.println("[INFO] Cliente desconectado. Total activo: " + clientes.size());
    }

    // Envia uma mensagem a todos os clientes, excepcionalmente ao que a originou
    public void notificarTodos(String mensagem, GestorClientes excepto) {
        synchronized (clientes) {
            for (GestorClientes c : clientes) {
                if (c != excepto) c.enviar(mensagem);
            }
        }
    }

    public String listarUsuarios() {
        synchronized (clientes) {
            String lista = clientes.stream()
                    .filter(c -> !c.getNome().equals("Anónimo"))
                    .map(c -> c.getNome() + "," + c.getEnderecoIP())
                    .collect(Collectors.joining(";"));
            return Protocolo.USUARIOS + "|" + lista;
        }
    }

    public static void main(String[] args) throws IOException {
        new Servidor().iniciar();
    }
}
