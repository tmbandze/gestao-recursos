package cliente;

import javafx.animation.PauseTransition;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.util.Duration;
import shared.Livro;
import shared.Protocolo;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

public class ControladorPrincipal {

    // ── Tabela ──────────────────────────────────────────
    @FXML private TableView<Livro>          tabelaLivros;
    @FXML private TableColumn<Livro,String> colTitulo;
    @FXML private TableColumn<Livro,String> colAutor;
    @FXML private TableColumn<Livro,String> colCategoria;
    @FXML private TableColumn<Livro,String> colEstado;

    // ── Topo ────────────────────────────────────────────
    @FXML private TextField        campoPesquisa;
    @FXML private ComboBox<String> filtroEstado;
    @FXML private Circle           statusDot;
    @FXML private Label            labelStatus;
    @FXML private Button           btnAdmin;
    @FXML private Button           btnReconectar;

    // ── Stats bar ───────────────────────────────────────
    @FXML private Label labelTotalLivros;
    @FXML private Label labelDisponiveis;
    @FXML private Label labelRequisitados;

    // ── Painel lateral — card de detalhes ───────────────
    @FXML private VBox   cardDetalhes;
    @FXML private Label  cardPlaceholder;
    @FXML private Label  detTitulo;
    @FXML private Label  detAutor;
    @FXML private Label  detCategoria;
    @FXML private Circle detEstadoCirculo;
    @FXML private Label  detEstado;
    @FXML private Label  detCom;
    @FXML private Label  detFila;
    @FXML private TextArea painelDetalhes;   // oculto, mantido por compatibilidade

    // ── Notificações ────────────────────────────────────
    @FXML private TextArea painelNotificacoes;

    // ── Status bar ──────────────────────────────────────
    @FXML private Label statusBarDot;
    @FXML private Label statusBarServer;

    // ── Estado interno ──────────────────────────────────
    private Cliente            cliente;
    private String             nomeEstudante;
    private AdminPanel         adminPanel;
    private NotificacaoService servicoNotificacoes;
    private final ObservableList<Livro> livros = FXCollections.observableArrayList();
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ── Inicialização ───────────────────────────────────

    @FXML
    public void initialize() {
        configurarTabela();
        configurarBuscaTempoReal();
        filtroEstado.setItems(FXCollections.observableArrayList("Todos", "Disponíveis", "Requisitados"));
        filtroEstado.setValue("Todos");
        btnAdmin.setVisible(false);
        btnReconectar.setVisible(false);
        limparCardDetalhes();
        configurarAtalhos();
        pedirLogin();
    }

    private void configurarTabela() {
        colTitulo.setCellValueFactory(   d -> new SimpleStringProperty(d.getValue().getTitulo()));
        colAutor.setCellValueFactory(    d -> new SimpleStringProperty(d.getValue().getAutor()));
        colCategoria.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getCategoria()));
        colEstado.setCellValueFactory(   d -> new SimpleStringProperty(d.getValue().getEstado().name()));

        // Badge colorido na coluna Estado
        colEstado.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String estado, boolean empty) {
                super.updateItem(estado, empty);
                setText(null);
                if (empty || estado == null) { setGraphic(null); return; }
                boolean disp = estado.toUpperCase().contains("DISP");
                Label badge = new Label(disp ? "● Disponível" : "● Emprestado");
                badge.getStyleClass().add(disp ? "badge-available" : "badge-borrowed");
                setGraphic(badge);
            }
        });

        tabelaLivros.setItems(livros);
        tabelaLivros.getSelectionModel().selectedItemProperty().addListener(
            (obs, ant, novo) -> { if (novo != null) carregarDetalhes(novo); });
    }

    private void configurarBuscaTempoReal() {
        PauseTransition debounce = new PauseTransition(Duration.millis(320));
        debounce.setOnFinished(e -> recarregarLivros());
        campoPesquisa.textProperty().addListener((obs, old, novo) -> debounce.playFromStart());
    }

    private void configurarAtalhos() {
        // Aplica atalhos assim que a scene estiver disponível
        campoPesquisa.sceneProperty().addListener((obs, old, scene) -> {
            if (scene == null) return;
            scene.setOnKeyPressed(e -> {
                switch (e.getCode()) {
                    case F5     -> recarregarLivros();
                    case ESCAPE -> campoPesquisa.clear();
                    case N      -> { if (e.isControlDown()) inserirLivro(); }
                }
            });
        });
    }

    // ── Login e ligação ─────────────────────────────────

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
        if (servicoNotificacoes != null) servicoNotificacoes.parar();
        if (cliente != null) cliente.fechar();

        btnReconectar.setVisible(false);
        btnAdmin.setVisible(false);
        definirStatusConectando();

        try {
            cliente = new Cliente();
            cliente.conectar();

            servicoNotificacoes = new NotificacaoService(cliente, this);
            Thread t = new Thread(servicoNotificacoes, "notificacoes");
            t.setDaemon(true);
            t.start();

            cliente.enviar(Protocolo.LOGIN + "|" + nomeEstudante);

            if (nomeEstudante.equalsIgnoreCase("admin")) {
                btnAdmin.setVisible(true);
                definirStatusAdmin();
            } else {
                definirStatusOnline("Ligado: " + nomeEstudante);
            }
            recarregarLivros();
        } catch (IOException e) {
            mostrarErroConexao();
        }
    }

    @FXML
    public void reconectar() {
        if (nomeEstudante == null || nomeEstudante.isEmpty()) pedirLogin();
        else conectar();
    }

    // ── Status dot ──────────────────────────────────────

    private void definirStatusConectando() {
        statusDot.getStyleClass().setAll("status-dot-connecting");
        labelStatus.getStyleClass().setAll("status-label");
        labelStatus.setText("A ligar...");
        statusBarDot.setStyle("-fx-text-fill: #FB8640;");
    }

    private void definirStatusOnline(String msg) {
        statusDot.getStyleClass().setAll("status-dot-online");
        labelStatus.getStyleClass().setAll("status-label-online");
        labelStatus.setText(msg);
        statusBarDot.setStyle("-fx-text-fill: #3DDB7E;");
    }

    private void definirStatusAdmin() {
        statusDot.getStyleClass().setAll("status-dot-online");
        labelStatus.getStyleClass().setAll("status-label-admin");
        labelStatus.setText("Admin");
        statusBarDot.setStyle("-fx-text-fill: #F06060;");
    }

    // ── Carregamento de dados ────────────────────────────

    public void notificarAtualizacao() {
        recarregarLivros();
        if (adminPanel != null) adminPanel.notificarAtualizacao();
    }

    public void recarregarLivros() {
        if (cliente == null || !cliente.isConectado()) return;
        String termo = campoPesquisa.getText().trim();
        // Sempre LISTAR para ter o catálogo completo (stats globais);
        // PESQUISAR quando há texto — filtro do ComboBox aplicado client-side em ambos os casos.
        String resposta = termo.isEmpty()
            ? cliente.enviar(Protocolo.LISTAR)
            : cliente.enviar(Protocolo.PESQUISAR + "|" + termo);
        parsearLivros(resposta);
    }

    private void parsearLivros(String resposta) {
        if (resposta == null || !resposta.startsWith(Protocolo.LIVROS + "|")) return;
        livros.clear();
        String dados = resposta.substring(Protocolo.LIVROS.length() + 1);
        if (!dados.isEmpty()) {
            for (String ls : dados.split(";")) {
                String[] c = ls.split(",", 5);
                if (c.length == 5) livros.add(new Livro(c[0], c[1], c[2], c[3], c[4]));
            }
        }
        // Stats calculadas antes do filtro → reflectem sempre o catálogo completo
        atualizarStats();
        // Filtro do ComboBox aplicado client-side (funciona combinado com pesquisa)
        String filtro = filtroEstado.getValue();
        if ("Disponíveis".equals(filtro))  livros.removeIf(l -> !l.isDisponivel());
        if ("Requisitados".equals(filtro)) livros.removeIf(Livro::isDisponivel);
    }

    private void atualizarStats() {
        long total = livros.size();
        long disp  = livros.stream().filter(Livro::isDisponivel).count();
        long emp   = total - disp;
        labelTotalLivros.setText(total + " livro" + (total != 1 ? "s" : ""));
        labelDisponiveis.setText(disp  + " disponíve" + (disp != 1 ? "is" : "l"));
        labelRequisitados.setText(emp  + " emprestado" + (emp != 1 ? "s" : ""));
    }

    // ── Card de detalhes ─────────────────────────────────

    private void carregarDetalhes(Livro livro) {
        if (cliente == null) return;
        String r = cliente.enviar(Protocolo.DETALHES + "|" + livro.getId());
        if (r == null || !r.startsWith("DETALHES|")) return;
        String[] p = r.split("\\|", -1);
        if (p.length < 8) return;

        String titulo    = p[2];
        String autor     = p[3];
        String categoria = p[4];
        String estado    = p[5];
        String com       = p[6];
        String fila      = p[7];

        cardPlaceholder.setVisible(false);
        cardPlaceholder.setManaged(false);

        detTitulo.setText(titulo);
        detAutor.setText(autor);
        detCategoria.setText(categoria);

        boolean disp = estado.toUpperCase().contains("DISP");
        detEstadoCirculo.getStyleClass().setAll(disp ? "det-dot-available" : "det-dot-borrowed");
        detEstado.setText(disp ? "Disponível" : "Emprestado");

        String comTxt = (com == null || com.isBlank() || "-".equals(com)) ? "" : "Com:   " + com;
        detCom.setText(comTxt);
        detCom.setVisible(!comTxt.isEmpty());
        detCom.setManaged(!comTxt.isEmpty());

        String filaTxt = (fila == null || fila.isBlank() || "-".equals(fila)) ? "" : "Fila:  " + fila;
        detFila.setText(filaTxt);
        detFila.setVisible(!filaTxt.isEmpty());
        detFila.setManaged(!filaTxt.isEmpty());
    }

    private void limparCardDetalhes() {
        cardPlaceholder.setVisible(true);
        cardPlaceholder.setManaged(true);
        detTitulo.setText("");
        detAutor.setText("");
        detCategoria.setText("");
        detEstado.setText("");
        detCom.setText("");   detCom.setVisible(false);   detCom.setManaged(false);
        detFila.setText("");  detFila.setVisible(false);  detFila.setManaged(false);
        detEstadoCirculo.getStyleClass().clear();
    }

    // ── Acções ───────────────────────────────────────────

    @FXML
    public void inserirLivro() {
        Dialog<String[]> dialogo = new Dialog<>();
        dialogo.setTitle("Inserir Livro");
        dialogo.setHeaderText("Adicionar novo livro ao catálogo");

        GridPane grid = new GridPane();
        grid.setHgap(12); grid.setVgap(10);
        grid.setPadding(new Insets(20));

        TextField campoTitulo    = new TextField();
        TextField campoAutor     = new TextField();
        TextField campoCategoria = new TextField();
        campoTitulo.setPromptText("ex: Sistemas Distribuídos");
        campoAutor.setPromptText("ex: Andrew Tanenbaum");
        campoCategoria.setPromptText("ex: Redes");
        campoTitulo.setPrefWidth(240);
        campoAutor.setPrefWidth(240);
        campoCategoria.setPrefWidth(240);

        grid.add(new Label("Título:"),    0, 0); grid.add(campoTitulo,    1, 0);
        grid.add(new Label("Autor:"),     0, 1); grid.add(campoAutor,     1, 1);
        grid.add(new Label("Categoria:"), 0, 2); grid.add(campoCategoria, 1, 2);

        dialogo.getDialogPane().setContent(grid);
        dialogo.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialogo.setResultConverter(bt -> bt == ButtonType.OK
            ? new String[]{campoTitulo.getText().trim(),
                           campoAutor.getText().trim(),
                           campoCategoria.getText().trim()}
            : null);

        dialogo.showAndWait().ifPresent(campos -> {
            if (campos[0].isEmpty() || campos[1].isEmpty() || campos[2].isEmpty()) {
                mostrarNotificacao("⚠  Todos os campos são obrigatórios.");
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
        if (sel == null) { mostrarNotificacao("⚠  Selecciona um livro primeiro."); return; }
        String r = cliente.enviar(Protocolo.REQUISITAR + "|" + sel.getId());
        mostrarNotificacao(extrairMensagem(r));
        recarregarLivros();
    }

    @FXML
    public void devolverLivro() {
        Livro sel = tabelaLivros.getSelectionModel().getSelectedItem();
        if (sel == null) { mostrarNotificacao("⚠  Selecciona um livro primeiro."); return; }
        String r = cliente.enviar(Protocolo.DEVOLVER + "|" + sel.getId());
        mostrarNotificacao(extrairMensagem(r));
        recarregarLivros();
    }

    @FXML
    public void verRelatorio() {
        String r = cliente.enviar(Protocolo.RELATORIO);
        if (r != null && r.startsWith(Protocolo.STATS + "|")) {
            String dados = r.substring(Protocolo.STATS.length() + 1).replace("|", "\n");
            mostrarNotificacao("── RELATÓRIO ──\n" + dados + "\n───────────────");
        }
    }

    @FXML
    public void verHistorico() {
        String r = cliente.enviar(Protocolo.HISTORICO);
        if (r == null || !r.startsWith(Protocolo.LOG + "|")) {
            mostrarNotificacao("⚠  Sem histórico disponível.");
            return;
        }
        String conteudo = r.substring(Protocolo.LOG.length() + 1);

        TextArea area = new TextArea(conteudo);
        area.setEditable(false);
        area.setWrapText(false);
        area.setPrefSize(680, 420);

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

    // ── Notificações e utilitários ───────────────────────

    public void mostrarNotificacao(String texto) {
        String hora = LocalTime.now().format(HORA);
        painelNotificacoes.appendText("[" + hora + "]  " + texto + "\n");
    }

    public void mostrarErroConexao() {
        statusDot.getStyleClass().setAll("status-dot-offline");
        labelStatus.getStyleClass().setAll("status-label");
        labelStatus.setText("Sem ligação");
        statusBarDot.setStyle("-fx-text-fill: #F06060;");
        btnReconectar.setVisible(true);
        mostrarNotificacao("✖  Ligação perdida. Clica em Reconectar.");
    }

    private String extrairMensagem(String resposta) {
        if (resposta == null) return "✖  Sem resposta do servidor";
        int idx = resposta.indexOf('|');
        return idx >= 0 ? resposta.substring(idx + 1) : resposta;
    }
}
