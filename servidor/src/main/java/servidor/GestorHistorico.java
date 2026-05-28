package servidor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import shared.Emprestimo;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class GestorHistorico {
    private static final String FICHEIRO = "data/emprestimos.json";
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private List<Emprestimo> emprestimos;

    public GestorHistorico() {
        new File("data").mkdirs();
        this.emprestimos = carregar();
    }

    public synchronized void registarInicio(String idLivro, String titulo,
                                             String estudante, String dataInicio, String prazo) {
        emprestimos.add(new Emprestimo(
            UUID.randomUUID().toString(), idLivro, titulo, estudante, dataInicio, prazo));
        guardar();
    }

    public synchronized void registarFim(String idLivro, String estudante, String dataFim) {
        emprestimos.stream()
            .filter(e -> e.getIdLivro().equals(idLivro)
                      && e.getEstudante().equals(estudante)
                      && e.getDataFim() == null)
            .findFirst()
            .ifPresent(e -> e.setDataFim(dataFim));
        guardar();
    }

    public synchronized List<Emprestimo> porEstudante(String estudante) {
        return emprestimos.stream()
            .filter(e -> e.getEstudante().equals(estudante))
            .sorted(Comparator.comparing(Emprestimo::getDataInicio).reversed())
            .collect(Collectors.toList());
    }

    /** Devolve todos os empréstimos ainda activos (não devolvidos). Usado pelo MonitorPrazos. */
    public synchronized List<Emprestimo> emprestimosActivos() {
        return emprestimos.stream()
            .filter(e -> e.getDataFim() == null)
            .collect(Collectors.toList());
    }

    private List<Emprestimo> carregar() {
        File f = new File(FICHEIRO);
        if (!f.exists()) return new ArrayList<>();
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            Type tipo = new TypeToken<List<Emprestimo>>() {}.getType();
            List<Emprestimo> lista = gson.fromJson(r, tipo);
            return lista != null ? lista : new ArrayList<>();
        } catch (IOException e) {
            System.err.println("[ERRO] Falha ao carregar histórico: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private void guardar() {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(FICHEIRO), StandardCharsets.UTF_8)) {
            gson.toJson(emprestimos, w);
        } catch (IOException e) {
            System.err.println("[ERRO] Falha ao guardar histórico: " + e.getMessage());
        }
    }
}
