package servidor;

import shared.EstadoLivro;
import shared.Livro;
import shared.Protocolo;

import java.util.*;
import java.util.stream.Collectors;

public class GestorLivros {
    private final BaseDados baseDados;
    private List<Livro> livros;
    // Filas em memória — separadas do JSON (não persistem entre reinícios do servidor)
    private final Map<String, Queue<GestorClientes>> filasEspera = new HashMap<>();

    public GestorLivros(BaseDados baseDados) {
        this.baseDados = baseDados;
        this.livros = baseDados.carregar();
    }

    public synchronized String inserir(String titulo, String autor, String categoria) {
        String id = UUID.randomUUID().toString();
        livros.add(new Livro(id, titulo, autor, categoria));
        baseDados.guardar(livros);
        return Protocolo.OK + "|Livro inserido com id " + id;
    }

    public synchronized String listar() {
        if (livros.isEmpty()) return Protocolo.LIVROS + "|";
        return Protocolo.LIVROS + "|" + livros.stream()
                .map(Livro::toProtocol)
                .collect(Collectors.joining(";"));
    }

    public synchronized String listarDisponiveis() {
        return Protocolo.LIVROS + "|" + livros.stream()
                .filter(Livro::isDisponivel)
                .map(Livro::toProtocol)
                .collect(Collectors.joining(";"));
    }

    public synchronized String pesquisar(String termo) {
        String t = termo.toLowerCase();
        return Protocolo.LIVROS + "|" + livros.stream()
                .filter(l -> l.getTitulo().toLowerCase().contains(t)
                          || l.getAutor().toLowerCase().contains(t)
                          || l.getCategoria().toLowerCase().contains(t))
                .map(Livro::toProtocol)
                .collect(Collectors.joining(";"));
    }

    public synchronized String requisitar(String id, GestorClientes solicitante) {
        Livro livro = buscar(id);
        if (livro == null) return Protocolo.ERRO + "|Livro não encontrado";
        if (solicitante.getNome().equals(livro.getEstudanteActual()))
            return Protocolo.ERRO + "|Já tens este livro requisitado";

        if (livro.isDisponivel()) {
            livro.setEstado(EstadoLivro.REQUISITADO);
            livro.setEstudanteActual(solicitante.getNome());
            baseDados.guardar(livros);
            return Protocolo.OK + "|Livro requisitado com sucesso";
        } else {
            Queue<GestorClientes> fila = filasEspera.computeIfAbsent(id, k -> new LinkedList<>());
            if (fila.contains(solicitante))
                return Protocolo.ERRO + "|Já estás na fila de espera deste livro";
            fila.add(solicitante);
            livro.getFilaEspera().add(solicitante.getNome());
            baseDados.guardar(livros);
            return Protocolo.OK + "|Adicionado à fila de espera (posição " + fila.size() + ")";
        }
    }

    public synchronized String devolver(String id, String nomeEstudante) {
        Livro livro = buscar(id);
        if (livro == null) return Protocolo.ERRO + "|Livro não encontrado";
        if (!nomeEstudante.equals(livro.getEstudanteActual()))
            return Protocolo.ERRO + "|Não tens este livro requisitado";

        Queue<GestorClientes> fila = filasEspera.get(id);
        if (fila != null && !fila.isEmpty()) {
            GestorClientes proximo = fila.poll();
            livro.setEstudanteActual(proximo.getNome());
            if (!livro.getFilaEspera().isEmpty()) livro.getFilaEspera().remove(0);
            proximo.enviarNotificacao("O livro '" + livro.getTitulo() + "' foi reservado para si!");
        } else {
            livro.setEstado(EstadoLivro.DISPONIVEL);
            livro.setEstudanteActual(null);
        }
        baseDados.guardar(livros);
        return Protocolo.OK + "|Livro devolvido com sucesso";
    }

    public synchronized String detalhes(String id) {
        Livro livro = buscar(id);
        if (livro == null) return Protocolo.ERRO + "|Livro não encontrado";
        String fila = livro.getFilaEspera().isEmpty()
                ? "vazia"
                : String.join(",", livro.getFilaEspera());
        return "DETALHES|" + livro.getId()
                + "|" + livro.getTitulo()
                + "|" + livro.getAutor()
                + "|" + livro.getCategoria()
                + "|" + livro.getEstado().name()
                + "|" + (livro.getEstudanteActual() != null ? livro.getEstudanteActual() : "-")
                + "|" + fila;
    }

    public synchronized String relatorio() {
        long total = livros.size();
        long requisitados = livros.stream().filter(l -> !l.isDisponivel()).count();
        return Protocolo.STATS
                + "|total_livros:" + total
                + "|disponiveis:" + (total - requisitados)
                + "|requisitados:" + requisitados;
    }

    public synchronized String relatorioAdmin() {
        long total = livros.size();
        long req = livros.stream().filter(l -> !l.isDisponivel()).count();

        StringBuilder sb = new StringBuilder(Protocolo.SISTEMA);
        sb.append("|total:").append(total)
          .append("|disponiveis:").append(total - req)
          .append("|requisitados:").append(req)
          .append("|LIVROS");

        for (Livro l : livros) {
            String fila = l.getFilaEspera().isEmpty() ? "vazia" : String.join(",", l.getFilaEspera());
            sb.append("|")
              .append(l.getTitulo()).append("~")
              .append(l.getAutor()).append("~")
              .append(l.getEstado().name()).append("~")
              .append(l.getEstudanteActual() != null ? l.getEstudanteActual() : "-").append("~")
              .append(fila);
        }
        return sb.toString();
    }

    public synchronized void removerDaFila(String id, GestorClientes cliente) {
        Queue<GestorClientes> fila = filasEspera.get(id);
        if (fila != null) fila.remove(cliente);
        Livro livro = buscar(id);
        if (livro != null) livro.getFilaEspera().remove(cliente.getNome());
    }

    private Livro buscar(String id) {
        return livros.stream().filter(l -> l.getId().equals(id)).findFirst().orElse(null);
    }
}
