package servidor;

import shared.Emprestimo;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Monitoriza prazos de devolução e envia notificações push aos utilizadores:
 *  - 1 dia antes do prazo: lembrete
 *  - No próprio dia do prazo: aviso urgente
 *  - Após o prazo: notificação de atraso (repete a cada hora enquanto o livro não for devolvido)
 *
 * Também notifica o admin sobre atrasos em curso.
 */
public class MonitorPrazos {

    private final GestorHistorico        gestorHistorico;
    private final GestorTCP              gestorTCP;
    private final Logger                 logger;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "monitor-prazos");
        t.setDaemon(true);
        return t;
    });

    public MonitorPrazos(GestorHistorico gestorHistorico, GestorTCP gestorTCP, Logger logger) {
        this.gestorHistorico = gestorHistorico;
        this.gestorTCP       = gestorTCP;
        this.logger          = logger;
    }

    /** Inicia o monitor: verifica imediatamente e depois de hora em hora. */
    public void iniciar() {
        // Primeira verificação 30 segundos depois do arranque (servidor a estabilizar)
        scheduler.scheduleAtFixedRate(this::verificar, 30, 3600, TimeUnit.SECONDS);
        System.out.println("[MONITOR] Monitor de prazos iniciado (verifica de hora em hora).");
    }

    public void parar() {
        scheduler.shutdownNow();
    }

    // ── Lógica de verificação ─────────────────────────────────────────────

    private void verificar() {
        LocalDate hoje = LocalDate.now();
        List<Emprestimo> activos;
        try {
            activos = gestorHistorico.emprestimosActivos();
        } catch (Exception e) {
            System.err.println("[MONITOR] Erro ao carregar empréstimos: " + e.getMessage());
            return;
        }

        for (Emprestimo emp : activos) {
            try {
                processarEmprestimo(emp, hoje);
            } catch (Exception e) {
                System.err.println("[MONITOR] Erro ao processar " + emp.getId() + ": " + e.getMessage());
            }
        }
    }

    private void processarEmprestimo(Emprestimo emp, LocalDate hoje) {
        LocalDate prazo;
        try {
            prazo = LocalDate.parse(emp.getPrazo());
        } catch (Exception e) {
            return; // prazo inválido
        }

        long diasAtraso    = ChronoUnit.DAYS.between(prazo, hoje);  // positivo = atrasado
        long diasRestantes = ChronoUnit.DAYS.between(hoje, prazo);  // positivo = dias que faltam

        if (diasAtraso > 0) {
            // ── Livro em atraso ──────────────────────────────────────────
            double multaEstimada = diasAtraso * GestorLivros.MULTA_POR_DIA;
            String msgEstudante = String.format(
                "⚠  ATRASO: O livro \"%s\" deveria ter sido devolvido há %d dia(s)! " +
                "Multa estimada: %.2f€. Prazo era: %s. Por favor devolva imediatamente.",
                emp.getTituloLivro(), diasAtraso, multaEstimada, formatarData(emp.getPrazo()));

            gestorTCP.notificarUtilizador(emp.getEstudante(), msgEstudante);

            // Notificar admin (somente se estiver conectado)
            gestorTCP.notificarUtilizador("admin", String.format(
                "📋 Atraso: %s — \"%s\" (%d dia(s), multa est. %.2f€)",
                emp.getEstudante(), emp.getTituloLivro(), diasAtraso, multaEstimada));

            logger.registar("ATRASO", emp.getEstudante(),
                emp.getTituloLivro() + " (" + diasAtraso + "d, " + String.format("%.2f", multaEstimada) + "€)");

        } else if (diasRestantes == 0) {
            // ── Devolução hoje ───────────────────────────────────────────
            gestorTCP.notificarUtilizador(emp.getEstudante(), String.format(
                "📅  URGENTE: O livro \"%s\" deve ser devolvido HOJE! Prazo: %s.",
                emp.getTituloLivro(), formatarData(emp.getPrazo())));

        } else if (diasRestantes == 1) {
            // ── Lembrete 1 dia antes ─────────────────────────────────────
            gestorTCP.notificarUtilizador(emp.getEstudante(), String.format(
                "⏰  Lembrete: O livro \"%s\" deve ser devolvido amanhã (%s).",
                emp.getTituloLivro(), formatarData(emp.getPrazo())));
        }
    }

    private static String formatarData(String iso) {
        try {
            LocalDate d = LocalDate.parse(iso);
            return d.getDayOfMonth() + "/" + d.getMonthValue() + "/" + d.getYear();
        } catch (Exception e) {
            return iso;
        }
    }
}
