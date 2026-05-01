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
        palco.setTitle("Gestão de Recursos — Partilha de Livros");
        palco.setScene(new Scene(raiz, 960, 620));
        palco.setMinWidth(800);
        palco.setMinHeight(500);
        palco.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
