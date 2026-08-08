package br.com.jcard.parser;

import br.com.jcard.model.TipoLancamento;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Uma linha de lançamento extraída do PDF, antes de virar entidade.
 *
 * @param linhaOriginal linha crua — o que permite depurar o parser depois, sem o PDF
 */
public record LancamentoLido(
        LocalDate dataCompra,
        String descricao,
        String descricaoNormalizada,
        BigDecimal valor,
        String portadorNome,
        String final4,
        Integer parcelaAtual,
        Integer parcelaTotal,
        TipoLancamento tipo,
        String linhaOriginal) {
}
