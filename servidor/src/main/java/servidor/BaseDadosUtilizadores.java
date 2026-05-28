package servidor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import shared.Utilizador;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class BaseDadosUtilizadores {
    private static final String FICHEIRO = "data/utilizadores.json";
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public BaseDadosUtilizadores() {
        new File("data").mkdirs();
    }

    public synchronized List<Utilizador> carregar() {
        File f = new File(FICHEIRO);
        if (!f.exists()) return new ArrayList<>();
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            Type tipo = new TypeToken<List<Utilizador>>() {}.getType();
            List<Utilizador> lista = gson.fromJson(r, tipo);
            return lista != null ? lista : new ArrayList<>();
        } catch (IOException e) {
            System.err.println("[ERRO] Falha ao carregar utilizadores: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public synchronized void guardar(List<Utilizador> utilizadores) {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(FICHEIRO), StandardCharsets.UTF_8)) {
            gson.toJson(utilizadores, w);
        } catch (IOException e) {
            System.err.println("[ERRO] Falha ao guardar utilizadores: " + e.getMessage());
        }
    }
}
