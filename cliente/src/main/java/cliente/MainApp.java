package cliente;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MainApp extends Application {

    @Override
    public void start(Stage palco) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/main.fxml"));
        Parent raiz = loader.load();

        Scene scene = new Scene(raiz, 1100, 680);
        scene.getStylesheets().add(
            getClass().getResource("/dark-theme.css").toExternalForm()
        );

        palco.setTitle("Biblioteca Digital — Gestão de Recursos");
        palco.setScene(scene);
        palco.setMinWidth(860);
        palco.setMinHeight(540);
        palco.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
