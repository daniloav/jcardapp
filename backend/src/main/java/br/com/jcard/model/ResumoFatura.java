package br.com.jcard.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * O cabeçalho da fatura, sem o texto extraído.
 *
 * <p>Existe por causa da listagem. {@link Fatura#textoExtraido} guarda a fatura
 * inteira em texto — numa fatura real de 514 lançamentos são dezenas de kB — e
 * um {@code select f from Fatura f} arrastava esse campo de <b>todos</b> os
 * meses só para desenhar uma lista que mostra competência, total e status. Com o
 * banco no Neon, do outro lado da rede, era esse peso que fazia a tela demorar.
 *
 * @see Fatura#resumosRecentes()
 */
public record ResumoFatura(Long id, LocalDate competencia, LocalDate vencimento,
                           BigDecimal valorTotal, BigDecimal valorLancado,
                           StatusFatura status, String emissor, LocalDateTime importadaEm) {

    /** A divergência entre o total impresso e o que conseguimos ler. */
    public BigDecimal divergencia() {
        return valorTotal.subtract(valorLancado);
    }
}
