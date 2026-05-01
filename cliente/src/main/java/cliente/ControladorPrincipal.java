package cliente;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;
import shared.Livro;
import shared.Protocolo;

import java.io.IOException;
import java.util.Optional;

public class ControladorPrincipal {

    @FXML private TableView<Livro>          tabelaLivros;
    @FXML private TableColumn<Livro,String> colTitulo;
    @FXML private TableColumn<Livro,String> colAutor;
    @FXML private TableColumn<Livro,String> colCategoria;
    @FXML private TableColumn<Livro,String> colEstado;
    @FXML private TextField                 campoPesquisa;
    @FXML private ComboBox<String>          filtroEstado;
    @FXML private TextArea                  painelNotificacoes;
    @FXML private TextArea                  painelDetalhes;
    @FXML private Label                     labelStatus;
    @FXML private Button                    btnAdmin;
    @FXML private Button                    btnReconectar;

    private Cliente             cliente;
    private String              nomeEstudante;
    private AdminPanel          adminPanel;
    private NotificacaoService  servicoNotificacoes;
    private final ObservableList<Livro> livros = FXCollections.observableArrayList();

    @FXML
    public void initialize() {
        configurarTabela();
        filtroEstado.setItems(FXCollections.observableArrayList("Todos", "Disponíveis", "Requisitados"));
        filtroEstado.setValue("Todos");
        btnAdmin.setVisible(false);
        btnReconectar.setVisible(false);
        pedirLogin();
    }

    private void configurarTabela() {
        colTitulo.setCellValueFactory(   d -> new SimpleStringProperty(d.getValue().getTitulo()));
        colAutor.setCellValueFactory(    d -> new SimpleStringProperty(d.getValue().getAutor()));
        colCategoria.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getCategoria()));
        colEstado.setCellValueFactory(   d -> new SimpleStringProperty(d.getValue().getEstado().name()));
        tabelaLivros.setItems(livros);
        tabelaLivros.getSelectionModel().selectedItemProperty().addListener(
                (obs, ant, novo) -> { if (novo != null) carregarDetalhes(novo); });
    }

    private void pedirLogin() {
        TextInputDialog dialogo = new TextInputDialog();
        dialogo.setTitle("Login");
        dialogo.setHeaderText("Sistema de Gestão de Recursos");
        dialogo.setContentText("Nome de estudante:");
        Optional<String> resultado = dialogo.showAndWait();
        resultado.ifPresent(nome -> {
            nomeEstudante = nome.trim();
            if (!nomeEstudante.isEmpty()) conectar();
        });
    }

    private void conectar() {
        // Limpar sessão anterior se existir
        if (servicoNotificacoes != null) servicoNotificacoes.parar();
        if (cliente != null) cliente.fechar();

        btnReconectar.setVisible(false);
        btnAdmin.setVisible(false);
        labelStatus.setStyle("");
        labelStatus.setText("A ligar...");

        try {
            cliente = new Cliente();
            cliente.conectar();

            // NotificacaoService arranca primeiro — é o único leitor do socket
            servicoNotificacoes = new NotificacaoService(cliente, this);
            Thread t = new Thread(servicoNotificacoes, "notificacoes");
            t.setDaemon(true);
            t.start();

            cliente.enviar(Protocolo.LOGIN + "|" + nomeEstudante);
            labelStatus.setText("Ligado como: " + nomeEstudante);
            if (nomeEstudante.equalsIgnoreCase("admin")) {
                btnAdmin.setVisible(true);
                labelStatus.setStyle("-fx-text-fill: #E53935; -fx-font-weight: bold;");
                labelStatus.setText("Admin");
            }
            recarregarLivros();
        } catch (IOException e) {
            mostrarErroConexao();
        }
    }

    @FXML
    public void reconectar() {
        if (nomeEstudante == null || nomeEstudante.isEmpty()) {
            pedirLogin();
        } else {
            conectar();
        }
    }

    public void notificarAtualizacao() {
        recarregarLivros();
        if (adminPanel != null) adminPanel.notificarAtualizacao();
    }

    public void recarregarLivros() {
        if (cliente == null || !cliente.isConectado()) return;
        String termo = campoPesquisa.getText().trim();
        String resposta;
        if (!termo.isEmpty()) {
            resposta = cliente.enviar(Protocolo.PESQUISAR + "|" + termo);
        } else if ("Disponíveis".equals(filtroEstado.getValue())) {
            resposta = cliente.enviar(Protocolo.LISTAR_DISPONIVEIS);
        } else {
            resposta = cliente.enviar(Protocolo.LISTAR);
        }
        parsearLivros(resposta);
    }

    private void parsearLivros(String resposta) {
        if (resposta == null || !resposta.startsWith(Protocolo.LIVROS + "|")) return;
        livros.clear();
        String dados = resposta.substring(Protocolo.LIVROS.length() + 1);
        if (dados.isEmpty()) return;
        for (String ls : dados.split(";")) {
            String[] c = ls.split(",", 5);
            if (c.length == 5) livros.add(new Livro(c[0], c[1], c[2], c[3], c[4]));
        }
        if ("Requisitados".equals(filtroEstado.getValue())) {
            livros.removeIf(Livro::isDisponivel);
        }
    }

    private void carregarDetalhes(Livro livro) {
        if (cliente == null) return;
        String r = cliente.enviar(Protocolo.DETALHES + "|" + livro.getId());
        if (r == null || !r.startsWith("DETALHES|")) return;
        String[] p = r.split("\\|", -1);
        if (p.length >= 8) {
            painelDetalhes.setText(
                    "Título:    " + p[2] + "\n" +
                    "Autor:     " + p[3] + "\n" +
                    "Categoria: " + p[4] + "\n" +
                    "Estado:    " + p[5] + "\n" +
                    "Com:       " + p[6] + "\n" +
                    "Fila:      " + p[7]);
        }
    }

    @FXML
    public void inserirLivro() {
        Dialog<String[]> dialogo = new Dialog<>();
        dialogo.setTitle("Inserir Livro");
        dialogo.setHeaderText("Adicionar novo livro");

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10);
        grid.setPadding(new Insets(20));

        TextField campoTitulo    = new TextField();
        TextField campoAutor     = new TextField();
        TextField campoCategoria = new TextField();
        campoTitulo.setPromptText("ex: Sistemas Distribuídos");
        campoAutor.setPromptText("ex: Tanenbaum");
        campoCategoria.setPromptText("ex: Redes");

        grid.add(new Label("Título:"),    0, 0); grid.add(campoTitulo,    1, 0);
        grid.add(new Label("Autor:"),     0, 1); grid.add(campoAutor,     1, 1);
        grid.add(new Label("Categoria:"), 0, 2); grid.add(campoCategoria, 1, 2);

        dialogo.getDialogPane().setContent(grid);
        dialogo.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialogo.setResultConverter(bt -> bt == ButtonType.OK
                ? new String[]{campoTitulo.getText().trim(), campoAutor.getText().trim(), campoCategoria.getText().trim()}
                : null);

        dialogo.showAndWait().ifPresent(campos -> {
            if (campos[0].isEmpty() || campos[1].isEmpty() || campos[2].isEmpty()) {
                mostrarNotificacao("[AVISO] Todos os campos são obrigatórios.");
                return;
            }
            String r = cliente.enviar(Protocolo.INSERIR + "|" + campos[0] + "|" + campos[1] + "|" + campos[2]);
            mostrarNotificacao(extrairMensagem(r));
            recarregarLivros();
        });
    }

    @FXML
    public void requisitarLivro() {
        Livro sel = tabelaLivros.getSelectionModel().getSelectedItem();
        if (sel == null) { mostrarNotificacao("[AVISO] Selecciona um livro primeiro."); return; }
        String r = cliente.enviar(Protocolo.REQUISITAR + "|" + sel.getId());
        mostrarNotificacao(extrairMensagem(r));
        recarregarLivros();
    }

    @FXML
    public void devolverLivro() {
        Livro sel = tabelaLivros.getSelectionModel().getSelectedItem();
        if (sel == null) { mostrarNotificacao("[AVISO] Selecciona um livro primeiro."); return; }
        String r = cliente.enviar(Protocolo.DEVOLVER + "|" + sel.getId());
        mostrarNotificacao(extrairMensagem(r));
        recarregarLivros();
    }

    @FXML
    public void verRelatorio() {
        String r = cliente.enviar(Protocolo.RELATORIO);
        if (r != null && r.startsWith(Protocolo.STATS + "|")) {
            String dados = r.substring(Protocolo.STATS.length() + 1).replace("|", "\n");
            mostrarNotificacao("--- RELATÓRIO ---\n" + dados + "\n-----------------");
        }
    }

    @FXML
    public void verHistorico() {
        String r = cliente.enviar(Protocolo.HISTORICO);
        if (r == null || !r.startsWith(Protocolo.LOG + "|")) {
            mostrarNotificacao("[AVISO] Sem histórico disponível.");
            return;
        }
        String conteudo = r.substring(Protocolo.LOG.length() + 1);

        TextArea area = new TextArea(conteudo);
        area.setEditable(false);
        area.setWrapText(false);
        area.setStyle("-fx-font-family: monospace; -fx-font-size: 11;");
        area.setPrefSize(620, 400);

        Dialog<Void> dlg = new Dialog<>();
        dlg.setTitle("Histórico de Operações");
        dlg.setHeaderText("Últimas operações registadas");
        dlg.getDialogPane().setContent(area);
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dlg.showAndWait();
    }

    @FXML public void pesquisar()     { recarregarLivros(); }
    @FXML public void filtrarEstado() { recarregarLivros(); }

    @FXML
    public void abrirPainelAdmin() {
        Stage dono = (Stage) tabelaLivros.getScene().getWindow();
        adminPanel = new AdminPanel(cliente, dono);
        adminPanel.mostrar();
    }

    private String extrairMensagem(String resposta) {
        if (resposta == null) return "[ERRO] Sem resposta";
        int idx = resposta.indexOf('|');
        return idx >= 0 ? resposta.substring(idx + 1) : resposta;
    }

    public void mostrarNotificacao(String texto) {
        painelNotificacoes.appendText(texto + "\n");
    }

    public void mostrarErroConexao() {
        labelStatus.setStyle("-fx-text-fill: #E53935;");
        labelStatus.setText("Sem ligação");
        btnReconectar.setVisible(true);
        mostrarNotificacao("[ERRO] Ligação perdida. Clica em Reconectar para tentar novamente.");
    }
}
