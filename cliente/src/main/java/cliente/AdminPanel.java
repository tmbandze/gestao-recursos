package cliente;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import shared.Protocolo;

public class AdminPanel {

    private final Cliente cliente;
    private final Stage stage;

    // Componentes
    private final ListView<String> listaUtilizadores = new ListView<>();
    private final TableView<String[]> tabelaLivros   = new TableView<>();
    private final TextArea areaLog                   = new TextArea();
    private final Label labelStats                   = new Label();

    public AdminPanel(Cliente cliente, Stage dono) {
        this.cliente = cliente;
        this.stage   = new Stage();
        stage.initOwner(dono);
        stage.initModality(Modality.NONE);
        stage.setTitle("Painel de Administração");
        stage.setScene(new Scene(construirLayout(), 750, 520));
    }

    private VBox construirLayout() {
        // ── Cabeçalho ──
        labelStats.setStyle("-fx-font-size: 13; -fx-font-weight: bold;");
        Button btnAtualizar = new Button("Atualizar");
        btnAtualizar.setOnAction(e -> atualizar());
        HBox cabecalho = new HBox(20, labelStats, btnAtualizar);
        cabecalho.setPadding(new Insets(10));
        cabecalho.setStyle("-fx-background-color: #37474F; -fx-border-radius: 4;");
        labelStats.setStyle("-fx-font-size: 13; -fx-font-weight: bold; -fx-text-fill: white;");

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
        areaLog.setStyle("-fx-font-family: monospace; -fx-font-size: 11;");
        tabLog.setContent(areaLog);

        TabPane tabs = new TabPane(tabUsers, tabLivros, tabLog);

        VBox raiz = new VBox(cabecalho, tabs);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        return raiz;
    }

    @SuppressWarnings("unchecked")
    private void configurarTabelaLivros() {
        TableColumn<String[], String> colTitulo = new TableColumn<>("Título");
        colTitulo.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue()[0]));
        colTitulo.setPrefWidth(220);

        TableColumn<String[], String> colAutor = new TableColumn<>("Autor");
        colAutor.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue()[1]));
        colAutor.setPrefWidth(140);

        TableColumn<String[], String> colEstado = new TableColumn<>("Estado");
        colEstado.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue()[2]));
        colEstado.setPrefWidth(100);

        TableColumn<String[], String> colCom = new TableColumn<>("Requisitado por");
        colCom.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue()[3]));
        colCom.setPrefWidth(130);

        TableColumn<String[], String> colFila = new TableColumn<>("Fila de Espera");
        colFila.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue()[4]));
        colFila.setPrefWidth(140);

        tabelaLivros.getColumns().addAll(colTitulo, colAutor, colEstado, colCom, colFila);
        tabelaLivros.setPlaceholder(new Label("Sem livros no sistema."));
    }

    private void atualizar() {
        // Remove respostas residuais que possam ter ficado na fila
        cliente.drenaFila();
        carregarUtilizadores();
        carregarLivros();
        carregarLog();
    }

    // Chamado pelo ControladorPrincipal quando recebe ATUALIZAR do servidor
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
            listaUtilizadores.getItems().add(nome + "   (" + ip + ")");
        }
    }

    private void carregarLivros() {
        String resp = cliente.enviar(Protocolo.ADMIN_SISTEMA);
        tabelaLivros.getItems().clear();
        if (resp == null || !resp.startsWith(Protocolo.SISTEMA + "|")) return;

        String[] secoes = resp.split("\\|LIVROS\\|", 2);

        // Stats no cabeçalho
        String stats = secoes[0].replace(Protocolo.SISTEMA + "|", "").replace("|", "   ");
        labelStats.setText("Sistema: " + stats);

        if (secoes.length < 2 || secoes[1].isEmpty()) return;

        // Cada livro separado por "|"
        for (String entrada : secoes[1].split("\\|")) {
            String[] c = entrada.split("~", 5);
            if (c.length == 5) {
                tabelaLivros.getItems().add(c);
            }
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
        // carregar dados DEPOIS da janela estar visível
        javafx.application.Platform.runLater(this::atualizar);
    }
}
