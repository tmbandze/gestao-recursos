package servidor;

import shared.Protocolo;

import java.io.*;
import java.net.Socket;

public class GestorClientes implements Runnable {
    private final Socket socket;
    private final BufferedReader entrada;
    private final PrintWriter saida;
    private final GestorLivros gestorLivros;
    private final Logger logger;
    private final Servidor servidor;
    private String nome = "Anónimo";

    public GestorClientes(Socket socket, GestorLivros gestorLivros, Logger logger, Servidor servidor)
            throws IOException {
        this.socket = socket;
        this.gestorLivros = gestorLivros;
        this.logger = logger;
        this.servidor = servidor;
        this.entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.saida   = new PrintWriter(socket.getOutputStream(), true);
    }

    @Override
    public void run() {
        try {
            String linha;
            while ((linha = entrada.readLine()) != null) {
                String resposta = processarComando(linha.trim());
                if (resposta != null) enviar(resposta);
            }
        } catch (IOException e) {
            // cliente desconectou abruptamente
        } finally {
            servidor.removerCliente(this);
            logger.registar("DESCONECTAR", nome, "-");
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private String processarComando(String linha) {
        if (linha.isEmpty()) return null;
        String[] partes = linha.split("\\|", -1);
        String comando = partes[0];

        switch (comando) {
            case Protocolo.LOGIN:
                if (partes.length < 2 || partes[1].isBlank())
                    return Protocolo.ERRO + "|Nome inválido";
                nome = partes[1].trim();
                logger.registar("CONECTAR", nome, "-");
                return Protocolo.OK + "|Bem-vindo, " + nome;

            case Protocolo.LISTAR:
                return gestorLivros.listar();

            case Protocolo.LISTAR_DISPONIVEIS:
                return gestorLivros.listarDisponiveis();

            case Protocolo.PESQUISAR:
                if (partes.length < 2) return Protocolo.ERRO + "|Termo em falta";
                return gestorLivros.pesquisar(partes[1]);

            case Protocolo.INSERIR:
                if (partes.length < 4) return Protocolo.ERRO + "|Campos insuficientes";
                String ins = gestorLivros.inserir(partes[1], partes[2], partes[3]);
                if (ins.startsWith(Protocolo.OK)) {
                    logger.registar("INSERIR", nome, partes[1]);
                    servidor.notificarTodos(Protocolo.ATUALIZAR, this);
                }
                return ins;

            case Protocolo.REQUISITAR:
                if (partes.length < 2) return Protocolo.ERRO + "|ID em falta";
                String req = gestorLivros.requisitar(partes[1], this);
                if (req.startsWith(Protocolo.OK)) {
                    logger.registar("REQUISITAR", nome, partes[1]);
                    servidor.notificarTodos(Protocolo.ATUALIZAR, this);
                }
                return req;

            case Protocolo.DEVOLVER:
                if (partes.length < 2) return Protocolo.ERRO + "|ID em falta";
                String dev = gestorLivros.devolver(partes[1], nome);
                if (dev.startsWith(Protocolo.OK)) {
                    logger.registar("DEVOLVER", nome, partes[1]);
                    servidor.notificarTodos(Protocolo.ATUALIZAR, this);
                }
                return dev;

            case Protocolo.DETALHES:
                if (partes.length < 2) return Protocolo.ERRO + "|ID em falta";
                return gestorLivros.detalhes(partes[1]);

            case Protocolo.HISTORICO:
                return Protocolo.LOG + "|" + logger.lerUltimas(50);

            case Protocolo.RELATORIO:
                return gestorLivros.relatorio();

            case Protocolo.ADMIN_USUARIOS:
                if (!nome.equalsIgnoreCase("admin"))
                    return Protocolo.ERRO + "|Acesso negado";
                return servidor.listarUsuarios();

            case Protocolo.ADMIN_SISTEMA:
                if (!nome.equalsIgnoreCase("admin"))
                    return Protocolo.ERRO + "|Acesso negado";
                return gestorLivros.relatorioAdmin();

            case Protocolo.SAIR:
                return null;

            default:
                return Protocolo.ERRO + "|Comando desconhecido: " + comando;
        }
    }

    public String getEnderecoIP() {
        return socket.getInetAddress().getHostAddress();
    }

    public synchronized void enviarNotificacao(String texto) {
        saida.println(Protocolo.NOTIFICACAO + "|" + texto);
    }

    public synchronized void enviar(String mensagem) {
        saida.println(mensagem);
    }

    public String getNome() { return nome; }
}
