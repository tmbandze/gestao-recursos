package servidor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedList;

public class Logger {
    private static final String FICHEIRO = "data/log.txt";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public synchronized void registar(String tipo, String estudante, String livro) {
        String timestamp = LocalDateTime.now().format(FMT);
        String linha = String.format("[%s] %-12s | %-15s | %s%n", timestamp, tipo, estudante, livro);
        try (Writer fw = new OutputStreamWriter(new FileOutputStream(FICHEIRO, true), StandardCharsets.UTF_8)) {
            fw.write(linha);
        } catch (IOException e) {
            System.err.println("[ERRO] Falha ao escrever log: " + e.getMessage());
        }
        System.out.print(linha);
    }

    public String lerUltimas(int n) {
        File f = new File(FICHEIRO);
        if (!f.exists()) return "(log vazio)";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            LinkedList<String> linhas = new LinkedList<>();
            String linha;
            while ((linha = br.readLine()) != null) linhas.addLast(linha);
            if (linhas.size() > n) linhas = new LinkedList<>(linhas.subList(linhas.size() - n, linhas.size()));
            return String.join("\n", linhas);
        } catch (IOException e) {
            return "(erro ao ler log)";
        }
    }
}
