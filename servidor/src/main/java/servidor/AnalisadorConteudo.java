package servidor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Analisa ficheiros PDF para detectar vírus e conteúdo impróprio para a biblioteca universitária.
 * Usa três camadas de análise:
 *   1. Validação do formato PDF
 *   2. Detecção heurística (assinaturas de malware, scripts suspeitos)
 *   3. Filtragem de palavras impróprias no texto extraído
 *   4. Integração opcional com ClamAV (clamscan) se instalado
 */
public class AnalisadorConteudo {

    /** Termos cuja presença marcam o livro como suspeito em contexto universitário */
    private static final List<String> PALAVRAS_PROIBIDAS = List.of(
        // Conteúdo adulto explícito
        "pornography", "pornografia", "explicit sexual content", "adult content",
        // Discurso de ódio
        "hate speech", "racial slur", "white supremacy",
        // Actividade ilegal / pirataria
        "crack software", "serial key generator", "license keygen",
        "pirated ebook", "warez",
        // Phishing
        "click here to claim", "bank account details", "enter your password",
        // Drogas
        "drug trafficking", "como fazer drogas", "metanfetamina caseira"
    );

    /** Assinaturas binárias de conteúdo malicioso conhecido */
    private static final List<byte[]> ASSINATURAS_MALWARE = List.of(
        // EICAR test file signature (usado em testes de antivírus)
        "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
            .getBytes(StandardCharsets.UTF_8),
        // Padrão de eval() ofuscado em JavaScript embutido em PDFs
        "/JavaScript /JS".getBytes(StandardCharsets.UTF_8),
        // AutoOpen em PDFs (execução automática)
        "/AA <<".getBytes(StandardCharsets.UTF_8)
    );

    public record ResultadoAnalise(boolean aprovado, String motivo) {}

    /**
     * Analisa um ficheiro PDF e devolve o resultado da análise.
     */
    public ResultadoAnalise analisar(File ficheiro) {
        if (ficheiro == null || !ficheiro.exists()) {
            return new ResultadoAnalise(false, "Ficheiro não encontrado");
        }

        long tamanho = ficheiro.length();
        if (tamanho == 0) return new ResultadoAnalise(false, "Ficheiro vazio");
        if (tamanho > 50_000_000L) return new ResultadoAnalise(false, "Ficheiro excede 50 MB");

        byte[] conteudo;
        try {
            conteudo = Files.readAllBytes(ficheiro.toPath());
        } catch (IOException e) {
            return new ResultadoAnalise(false, "Erro ao ler ficheiro: " + e.getMessage());
        }

        // ── Passo 1: Cabeçalho PDF válido ────────────────────────────────
        if (!isPDF(conteudo)) {
            return new ResultadoAnalise(false, "Ficheiro não é um PDF válido (cabeçalho inválido)");
        }

        // ── Passo 2: Assinaturas de malware ──────────────────────────────
        String resultadoMalware = verificarMalware(conteudo);
        if (resultadoMalware != null) {
            return new ResultadoAnalise(false, resultadoMalware);
        }

        // ── Passo 3: Conteúdo de texto ───────────────────────────────────
        String resultadoConteudo = verificarConteudo(conteudo);
        if (resultadoConteudo != null) {
            return new ResultadoAnalise(false, resultadoConteudo);
        }

        // ── Passo 4: ClamAV (se disponível) ──────────────────────────────
        String resultadoClamav = verificarClamAV(ficheiro);
        if (resultadoClamav != null) {
            return new ResultadoAnalise(false, resultadoClamav);
        }

        return new ResultadoAnalise(true, "Conteúdo aprovado");
    }

    // ── Métodos privados ─────────────────────────────────────────────────

    private boolean isPDF(byte[] b) {
        return b.length >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F';
    }

    private String verificarMalware(byte[] conteudo) {
        for (byte[] assinatura : ASSINATURAS_MALWARE) {
            if (contemSequencia(conteudo, assinatura)) {
                // Verificar se o contexto é genuinamente suspeito
                String txt = extrairTexto(conteudo);
                if (assinatura == ASSINATURAS_MALWARE.get(1)) { // /JavaScript
                    // JavaScript em PDFs pode ser legítimo — só flaggar se tiver eval/unescape
                    if (txt.contains("eval(") || txt.contains("unescape(") || txt.contains("fromCharCode")) {
                        return "JavaScript ofuscado suspeito detectado no PDF";
                    }
                } else if (assinatura == ASSINATURAS_MALWARE.get(2)) { // /AA
                    // Acção automática com Launch ou URI externo é suspeito
                    if (txt.contains("/Launch") || txt.contains("/SubmitForm")) {
                        return "Acção automática suspeita (AutoOpen com Launch/Submit)";
                    }
                } else {
                    return "Assinatura EICAR de teste de malware detectada";
                }
            }
        }
        return null;
    }

    private String verificarConteudo(byte[] conteudo) {
        String texto = extrairTexto(conteudo).toLowerCase();
        for (String palavra : PALAVRAS_PROIBIDAS) {
            if (texto.contains(palavra.toLowerCase())) {
                return "Conteúdo impróprio detectado: \"" + palavra + "\"";
            }
        }
        return null;
    }

    private String verificarClamAV(File ficheiro) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "clamscan", "--no-summary", "--stdout", ficheiro.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String saida = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int codigo = proc.waitFor();
            if (codigo == 1) { // ClamAV: 0=limpo, 1=infectado, 2=erro
                String virus = saida.contains("FOUND") ? saida.split("FOUND")[0].trim() : saida;
                return "Vírus detectado pelo ClamAV: " + virus;
            }
        } catch (IOException e) {
            // ClamAV não está instalado — ignorar silenciosamente
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /**
     * Extrai texto ASCII/UTF-8 legível do binário do PDF sem dependências externas.
     * Não é um parser PDF completo, mas suficiente para filtrar palavras-chave em texto em claro.
     */
    private String extrairTexto(byte[] conteudo) {
        StringBuilder sb = new StringBuilder(conteudo.length / 2);
        for (int i = 0; i < conteudo.length; i++) {
            int b = conteudo[i] & 0xFF;
            if ((b >= 32 && b < 127) || b == '\n' || b == '\r' || b == '\t') {
                sb.append((char) b);
            } else {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    private boolean contemSequencia(byte[] dados, byte[] seq) {
        outer:
        for (int i = 0; i <= dados.length - seq.length; i++) {
            for (int j = 0; j < seq.length; j++) {
                if (dados[i + j] != seq[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
