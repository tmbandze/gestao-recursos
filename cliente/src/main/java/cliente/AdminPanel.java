package cliente;

import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import shared.Protocolo;

public class AdminPanel {

    private final Cliente cliente;
    private final Stage   stage;

    private final ListView<String>    listaUtilizadores = new ListView<>();
    private final TableView<String[]> tabelaLivros      = new TableView<>();
    private final TextArea            areaLog           = new TextArea();
    private final Label               labelStats        = new Label();

    public AdminPanel(Cliente cliente, Stage dono) {
        this.cliente = cliente;
        this.stage   = new Stage();
        stage.initOwner(dono);
        stage.initModality(Modality.NONE);
        stage.setTitle("Painel de Administração");

        Scene scene = new Scene(construirLayout(), 820, 560);
        scene.getStylesheets().add(
            getClass().getResource("/dark-theme.css").toExternalForm()
        );
        stage.setScene(scene);
    }

    private VBox construirLayout() {
        // ── Cabeçalho ──
        labelStats.getStyleClass().add("admin-stats-label");

        Button btnAtualizar = new Button("↻  Atualizar");
        btnAtualizar.getStyleClass().add("btn-ghost");
        btnAtualizar.setOnAction(e -> atualizar());

        HBox cabecalho = new HBox(16, labelStats, new Region(), btnAtualizar);
        HBox.setHgrow(cabecalho.getChildren().get(1), Priority.ALWAYS);
        cabecalho.getStyleClass().add("admin-header");
        cabecalho.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        // ── Tab: Utilizadores ──
        Tab tabUsers = new Tab("Utilizadores Online");
        tabUsers.setClosable(false);
        listaUtilizadores.setPlaceholder(new Label("Nenhum utilizador conectado."));
        tabUsers.setContent(listaUtilizadores);

        // ── Tab: Livros ──
        Tab tabLivros = new Tab("Livros do Sistema");
        tabLivros.setClosable(false);
        configurarTabelaLivros();
        tabLivros.setContent(tabelaLivros);

        // ── Tab: Log ──
        Tab tabLog = new Tab("Log de Operações");
        tabLog.setClosable(false);
        areaLog.setEditable(false);
        areaLog.setWrapText(false);
        tabLog.setContent(areaLog);

        TabPane tabs = new TabPane(tabUsers, tabLivros, tabLog);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        VBox raiz = new VBox(cabecalho, tabs);
        return raiz;
    }

    @SuppressWarnings("unchecked")
    private void configurarTabelaLivros() {
        tabelaLivros.getStyleClass().add("book-table");

        TableColumn<String[], String> colTitulo = new TableColumn<>("TÍTULO");
        colTitulo.setCellValueFactory(d -> new SimpleStringProperty(d.getValue()[0]));
        colTitulo.setPrefWidth(230);

        TableColumn<String[], String> colAutor = new TableColumn<>("AUTOR");
        colAutor.setCellValueFactory(d -> new SimpleStringProperty(d.getValue()[1]));
        colAutor.setPrefWidth(150);

        TableColumn<String[], String> colEstado = new TableColumn<>("ESTADO");
        colEstado.setCellValueFactory(d -> new SimpleStringProperty(d.getValue()[2]));
        colEstado.setPrefWidth(110);
        colEstado.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String estado, boolean empty) {
                super.updateItem(estado, empty);
                setText(null);
                if (empty || estado == null) { setGraphic(null); return; }
                boolean disp = estado.toUpperCase().contains("DISP");
                Label badge = new Label(disp ? "● Disponível" : "● Emprestado");
                badge.getStyleClass().add(disp ? "badge-available" : "badge-borrowed");
                setGraphic(badge);
            }
        });

        TableColumn<String[], String> colCom = new TableColumn<>("REQUISITADO POR");
        colCom.setCellValueFactory(d -> new SimpleStringProperty(d.getValue()[3]));
        colCom.setPrefWidth(150);

        TableColumn<String[], String> colFila = new TableColumn<>("FILA DE ESPERA");
        colFila.setCellValueFactory(d -> new SimpleStringProperty(d.getValue()[4]));
        colFila.setPrefWidth(150);

        tabelaLivros.getColumns().addAll(colTitulo, colAutor, colEstado, colCom, colFila);
        tabelaLivros.setPlaceholder(new Label("Sem livros no sistema."));
    }

    private void atualizar() {
        cliente.drenaFila();
        carregarUtilizadores();
        carregarLivros();
        carregarLog();
    }

    public void notificarAtualizacao() {
        if (stage.isShowing()) {
            javafx.application.Platform.runLater(this::atualizar);
        }
    }

    private void carregarUtilizadores() {
        String resp = cliente.enviar(Protocolo.ADMIN_USUARIOS);
        listaUtilizadores.getItems().clear();
        if (resp == null || !resp.startsWith(Protocolo.USUARIOS + "|")) return;
        String dados = resp.substring(Protocolo.USUARIOS.length() + 1);
        if (dados.isEmpty()) return;
        for (String u : dados.split(";")) {
            String[] partes = u.split(",", 2);
            String nome = partes[0];
            String ip   = partes.length > 1 ? partes[1] : "?";
            listaUtilizadores.getItems().add(nome + "     (" + ip + ")");
        }
    }

    private void carregarLivros() {
        String resp = cliente.enviar(Protocolo.ADMIN_SISTEMA);
        tabelaLivros.getItems().clear();
        if (resp == null || !resp.startsWith(Protocolo.SISTEMA + "|")) return;

        String[] secoes = resp.split("\\|LIVROS\\|", 2);
        String stats = secoes[0].replace(Protocolo.SISTEMA + "|", "").replace("|", "   ");
        labelStats.setText("Sistema:  " + stats);

        if (secoes.length < 2 || secoes[1].isEmpty()) return;
        for (String entrada : secoes[1].split("\\|")) {
            String[] c = entrada.split("~", 5);
            if (c.length == 5) tabelaLivros.getItems().add(c);
        }
    }

    private void carregarLog() {
        String resp = cliente.enviar(Protocolo.HISTORICO);
        if (resp != null && resp.startsWith(Protocolo.LOG + "|")) {
            areaLog.setText(resp.substring(Protocolo.LOG.length() + 1));
        }
    }

    public void mostrar() {
        stage.show();
        stage.toFront();
        javafx.application.Platform.runLater(this::atualizar);
    }
}
