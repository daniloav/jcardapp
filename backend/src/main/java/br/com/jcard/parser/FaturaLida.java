package br.com.jcard.parser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Resultado da leitura de um PDF de fatura.
 *
 * @param valorTotal       total impresso na fatura (a verdade da conciliação)
 * @param linhasIgnoradas  linhas que pareciam lançamento mas não casaram — a lista
 *                         que revela um layout novo antes de virar erro silencioso
 */
public record FaturaLida(
        LocalDate competencia,
        LocalDate vencimento,
        BigDecimal valorTotal,
        List<LancamentoLido> lancamentos,
        List<String> linhasIgnoradas) {

    /** Soma dos lançamentos lidos — comparada ao total impresso pela conciliação. */
    public BigDecimal somaLancamentos() {
        return lancamentos.stream()
                .map(LancamentoLido::valor)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
