package servidor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import shared.Livro;

import java.io.*;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class BaseDados {
    private static final String PASTA    = "data";
    private static final String FICHEIRO = PASTA + "/livros.json";
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public BaseDados() {
        new File(PASTA).mkdirs();
    }

    public synchronized List<Livro> carregar() {
        File f = new File(FICHEIRO);
        if (!f.exists()) return new ArrayList<>();
        try (Reader r = new FileReader(f)) {
            Type tipo = new TypeToken<List<Livro>>() {}.getType();
            List<Livro> lista = gson.fromJson(r, tipo);
            return lista != null ? lista : new ArrayList<>();
        } catch (IOException e) {
            System.err.println("[ERRO] Falha ao carregar base de dados: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public synchronized void guardar(List<Livro> livros) {
        try (Writer w = new FileWriter(FICHEIRO)) {
            gson.toJson(livros, w);
        } catch (IOException e) {
            System.err.println("[ERRO] Falha ao guardar base de dados: " + e.getMessage());
        }
    }
}
