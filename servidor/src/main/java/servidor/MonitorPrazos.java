package servidor;

import shared.Emprestimo;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Monitoriza prazos de devolução e envia notificações push (SSE/TCP) e por email:
 *  - 1 dia antes do prazo: lembrete
 *  - No próprio dia do prazo: aviso urgente
 *  - Após o prazo: notificação de atraso (repete a cada hora enquanto o livro não for devolvido)
 */
public class MonitorPrazos {

    private final GestorHistorico        gestorHistorico;
    private final GestorTCP              gestorTCP;
    private final Logger                 logger;
    private final GestorEmail            gestorEmail;
    private final GestorUtilizadores     gestorUtilizadores;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "monitor-prazos");
        t.setDaemon(true);
        return t;
    });

    public MonitorPrazos(GestorHistorico gestorHistorico, GestorTCP gestorTCP,
                         Logger logger, GestorEmail gestorEmail,
                         GestorUtilizadores gestorUtilizadores) {
        this.gestorHistorico    = gestorHistorico;
        this.gestorTCP          = gestorTCP;
        this.logger             = logger;
        this.gestorEmail        = gestorEmail;
        this.gestorUtilizadores = gestorUtilizadores;
    }

    /** Inicia o monitor: verifica imediatamente e depois de hora em hora. */
    public void iniciar() {
        scheduler.scheduleAtFixedRate(this::verificar, 30, 3600, TimeUnit.SECONDS);
        System.out.println("[MONITOR] Monitor de prazos iniciado (verifica de hora em hora).");
    }

    public void parar() { scheduler.shutdownNow(); }

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
            try { processarEmprestimo(emp, hoje); }
            catch (Exception e) {
                System.err.println("[MONITOR] Erro ao processar " + emp.getId() + ": " + e.getMessage());
            }
        }
    }

    private void processarEmprestimo(Emprestimo emp, LocalDate hoje) {
        LocalDate prazo;
        try { prazo = LocalDate.parse(emp.getPrazo()); }
        catch (Exception e) { return; }

        long diasAtraso    = ChronoUnit.DAYS.between(prazo, hoje);   // positivo = atrasado
        long diasRestantes = ChronoUnit.DAYS.between(hoje, prazo);   // positivo = dias que faltam

        String estudante  = emp.getEstudante();
        String titulo     = emp.getTituloLivro();
        String prazoFmt   = formatarData(emp.getPrazo());
        String emailUser  = gestorEmail != null ? gestorUtilizadores.getEmailPorNome(estudante) : null;

        if (diasAtraso > 0) {
            // ── Livro em atraso ──────────────────────────────────────────
            double multaEstimada = diasAtraso * GestorLivros.MULTA_POR_DIA;
            String msgEstudante = String.format(
                "⚠  ATRASO: O livro \"%s\" deveria ter sido devolvido há %d dia(s)! " +
                "Multa estimada: %.2f€. Prazo era: %s. Por favor devolva imediatamente.",
                titulo, diasAtraso, multaEstimada, prazoFmt);

            gestorTCP.notificarUtilizador(estudante, msgEstudante);
            gestorTCP.notificarUtilizador("admin", String.format(
                "📋 Atraso: %s — \"%s\" (%d dia(s), multa est. %.2f€)",
                estudante, titulo, diasAtraso, multaEstimada));
            logger.registar("ATRASO", estudante,
                titulo + " (" + diasAtraso + "d, " + String.format("%.2f", multaEstimada) + "€)");

            // Email de atraso
            if (emailUser != null && gestorEmail.isConfigurado()) {
                gestorEmail.enviarAsync(emailUser,
                    "⚠️ Livro em atraso — \"" + titulo + "\"",
                    gestorEmail.htmlAtraso(estudante, titulo, diasAtraso, multaEstimada));
            }

        } else if (diasRestantes == 0) {
            // ── Devolução hoje ───────────────────────────────────────────
            gestorTCP.notificarUtilizador(estudante, String.format(
                "📅  URGENTE: O livro \"%s\" deve ser devolvido HOJE! Prazo: %s.",
                titulo, prazoFmt));

            if (emailUser != null && gestorEmail.isConfigurado()) {
                gestorEmail.enviarAsync(emailUser,
                    "📅 Devolução hoje: \"" + titulo + "\"",
                    gestorEmail.htmlPrazoHoje(estudante, titulo, prazoFmt));
            }

        } else if (diasRestantes == 1) {
            // ── Lembrete 1 dia antes ─────────────────────────────────────
            gestorTCP.notificarUtilizador(estudante, String.format(
                "⏰  Lembrete: O livro \"%s\" deve ser devolvido amanhã (%s).",
                titulo, prazoFmt));

            if (emailUser != null && gestorEmail.isConfigurado()) {
                gestorEmail.enviarAsync(emailUser,
                    "⏰ Lembrete: devolução amanhã — \"" + titulo + "\"",
                    gestorEmail.htmlLembrete(estudante, titulo, prazoFmt));
            }
        }
    }

    private static String formatarData(String iso) {
        try {
            LocalDate d = LocalDate.parse(iso);
            return d.getDayOfMonth() + "/" + d.getMonthValue() + "/" + d.getYear();
        } catch (Exception e) { return iso; }
    }
}
