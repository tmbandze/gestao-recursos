package servidor;

import shared.EstadoLivro;
import shared.Livro;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

public class GestorLivros {
    private static final String PDF_DIR = "data/pdfs";

    private final BaseDados baseDados;
    private final Servidor servidor;
    private List<Livro> livros;
    private final Map<String, Queue<String>> filasEspera = new HashMap<>();

    public GestorLivros(BaseDados baseDados, Servidor servidor) {
        this.baseDados = baseDados;
        this.servidor  = servidor;
        this.livros    = baseDados.carregar();
        new File(PDF_DIR).mkdirs();
    }

    public synchronized List<Livro> listarTodos() {
        return new ArrayList<>(livros);
    }

    public synchronized List<Livro> pesquisar(String termo) {
        if (termo.isBlank()) return new ArrayList<>(livros);
        String t = termo.toLowerCase();
        return livros.stream()
                .filter(l -> l.getTitulo().toLowerCase().contains(t)
                          || l.getAutor().toLowerCase().contains(t)
                          || l.getCategoria().toLowerCase().contains(t))
                .collect(Collectors.toList());
    }

    public synchronized Map<String, Object> detalhes(String id) {
        Livro l = buscar(id);
        if (l == null) return Map.of("erro", "Livro não encontrado");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",               l.getId());
        m.put("titulo",           l.getTitulo());
        m.put("autor",            l.getAutor());
        m.put("categoria",        l.getCategoria());
        m.put("estado",           l.getEstado().name());
        m.put("estudanteActual",  l.getEstudanteActual());
        m.put("filaEspera",       l.getFilaEspera());
        m.put("temPdf",           l.isTemPdf());
        return m;
    }

    public synchronized Map<String, Object> inserir(String titulo, String autor, String categoria, String nome, InputStream pdfStream) {
        if (titulo == null || titulo.isBlank()) return Map.of("erro", "Título em falta");
        if (autor  == null || autor.isBlank())  return Map.of("erro", "Autor em falta");
        String id  = UUID.randomUUID().toString();
        String cat = (categoria != null && !categoria.isBlank()) ? categoria.trim() : "Geral";
        Livro livro = new Livro(id, titulo.trim(), autor.trim(), cat);

        if (pdfStream != null) {
            try {
                Files.copy(pdfStream, new File(PDF_DIR, id + ".pdf").toPath());
                livro.setTemPdf(true);
                livro.setUploadPor(nome);
            } catch (IOException e) {
                System.err.println("[AVISO] Falha ao guardar PDF: " + e.getMessage());
            }
        }

        livros.add(livro);
        baseDados.guardar(livros);
        servidor.notificarTodos("atualizacao", "novo_livro", nome);
        return Map.of("ok", true, "id", id, "mensagem", "Livro inserido com sucesso");
    }

    public File getPdfFile(String id) {
        File f = new File(PDF_DIR, id + ".pdf");
        return f.exists() ? f : null;
    }

    public synchronized Map<String, Object> requisitar(String id, String nome) {
        Livro livro = buscar(id);
        if (livro == null) return Map.of("erro", "Livro não encontrado");
        if (nome.equals(livro.getEstudanteActual()))
            return Map.of("erro", "Já tens este livro requisitado");

        if (livro.isDisponivel()) {
            livro.setEstado(EstadoLivro.REQUISITADO);
            livro.setEstudanteActual(nome);
            baseDados.guardar(livros);
            servidor.notificarTodos("atualizacao", "requisitado", nome);
            return Map.of("ok", true, "mensagem", "Livro requisitado com sucesso");
        } else {
            Queue<String> fila = filasEspera.computeIfAbsent(id, k -> new LinkedList<>());
            if (fila.contains(nome)) return Map.of("erro", "Já estás na fila de espera deste livro");
            fila.add(nome);
            livro.getFilaEspera().add(nome);
            baseDados.guardar(livros);
            return Map.of("ok", true, "mensagem", "Adicionado à fila de espera (posição " + fila.size() + ")");
        }
    }

    public synchronized Map<String, Object> devolver(String id, String nome) {
        Livro livro = buscar(id);
        if (livro == null) return Map.of("erro", "Livro não encontrado");
        if (!nome.equals(livro.getEstudanteActual()))
            return Map.of("erro", "Não tens este livro requisitado");

        Queue<String> fila = filasEspera.get(id);
        if (fila != null && !fila.isEmpty()) {
            String proximo = fila.poll();
            livro.setEstudanteActual(proximo);
            if (!livro.getFilaEspera().isEmpty()) livro.getFilaEspera().remove(0);
            servidor.notificarUsuario(proximo, "O livro '" + livro.getTitulo() + "' foi reservado para si!");
        } else {
            livro.setEstado(EstadoLivro.DISPONIVEL);
            livro.setEstudanteActual(null);
        }
        baseDados.guardar(livros);
        servidor.notificarTodos("atualizacao", "devolvido", nome);
        return Map.of("ok", true, "mensagem", "Livro devolvido com sucesso");
    }

    public synchronized Map<String, Object> relatorio() {
        long total = livros.size();
        long req   = livros.stream().filter(l -> !l.isDisponivel()).count();
        return Map.of("total", total, "disponiveis", total - req, "requisitados", req);
    }

    public synchronized Map<String, Object> relatorioAdmin() {
        long total = livros.size();
        long req   = livros.stream().filter(l -> !l.isDisponivel()).count();
        List<Map<String, Object>> lista = livros.stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("titulo",          l.getTitulo());
            m.put("autor",           l.getAutor());
            m.put("estado",          l.getEstado().name());
            m.put("estudanteActual", l.getEstudanteActual());
            m.put("filaEspera",      l.getFilaEspera());
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("total", total);
        r.put("disponiveis", total - req);
        r.put("requisitados", req);
        r.put("livros", lista);
        return r;
    }

    private Livro buscar(String id) {
        return livros.stream().filter(l -> l.getId().equals(id)).findFirst().orElse(null);
    }
}
