package br.com.jcard.parser;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilitários de texto do PDF: dinheiro em pt-BR, normalização de descrição e a
 * inferência do ano (a fatura só imprime dia/mês).
 */
public final class TextoFatura {

    /** Sufixo de parcela no fim da descrição: "POSTO ABC 03/10". */
    private static final Pattern SUFIXO_PARCELA =
            Pattern.compile("\\s+(\\d{1,2})\\s*/\\s*(\\d{1,2})\\s*$");

    private static final Pattern SO_MARCAS = Pattern.compile("\\p{M}+");
    private static final Pattern ESPACOS = Pattern.compile("\\s+");
    /** Ruído que muda a cada mês e atrapalharia o casamento entre faturas. */
    private static final Pattern RUIDO = Pattern.compile("[*#]+|\\b\\d{6,}\\b");

    private TextoFatura() {
    }

    /**
     * Converte dinheiro em pt-BR ({@code 1.234,56}) para {@link BigDecimal}.
     * Aceita o sinal antes ou depois — bancos usam as duas formas para crédito.
     */
    public static BigDecimal valor(String bruto) {
        if (bruto == null) {
            return null;
        }
        String s = bruto.trim().replace(" ", "").replace("R$", "");
        boolean negativo = s.startsWith("-") || s.endsWith("-");
        s = s.replace("-", "").replace(".", "").replace(",", ".");
        if (s.isEmpty()) {
            return null;
        }
        BigDecimal v = new BigDecimal(s);
        return negativo ? v.negate() : v;
    }

    /**
     * Descrição sem acento, em caixa alta, sem o sufixo de parcela e sem ruído.
     * É a base da chave que casa o mesmo parcelamento entre faturas.
     */
    public static String normalizar(String descricao) {
        if (descricao == null) {
            return "";
        }
        String s = SUFIXO_PARCELA.matcher(descricao).replaceAll("");
        s = Normalizer.normalize(s, Normalizer.Form.NFD);
        s = SO_MARCAS.matcher(s).replaceAll("");
        s = s.toUpperCase();
        s = RUIDO.matcher(s).replaceAll(" ");
        s = ESPACOS.matcher(s).replaceAll(" ").trim();
        return s.length() > 255 ? s.substring(0, 255) : s;
    }

    /** {@code [parcelaAtual, parcelaTotal]}, ou {@code null} se não for parcelado. */
    public static int[] parcela(String descricao) {
        if (descricao == null) {
            return null;
        }
        Matcher m = SUFIXO_PARCELA.matcher(descricao);
        if (!m.find()) {
            return null;
        }
        int atual = Integer.parseInt(m.group(1));
        int total = Integer.parseInt(m.group(2));
        // "12/24" é parcela; "01/01" e totais absurdos são falso-positivo (data solta, código).
        if (total < 2 || total > 99 || atual < 1 || atual > total) {
            return null;
        }
        return new int[] { atual, total };
    }

    /**
     * Completa o ano de uma data {@code dd/MM} da fatura.
     *
     * <p>A fatura de janeiro traz compras de dezembro: se o mês do lançamento é
     * maior que o da competência, a compra é do ano anterior.
     */
    public static LocalDate comAno(int dia, int mes, LocalDate competencia) {
        int ano = competencia.getYear();
        if (mes > competencia.getMonthValue()) {
            ano--;
        }
        return LocalDate.of(ano, mes, dia);
    }
}
