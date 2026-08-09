package br.com.jcard.model;

/**
 * Natureza do lançamento, que decide quem paga o quê:
 *
 * <ul>
 *   <li>{@link #COMPRA} e {@link #ESTORNO} são <b>reivindicáveis</b> — alguém
 *       assume, e dá para dividir entre várias pessoas;</li>
 *   <li>encargos ({@link #ENCARGO}, {@link #IOF}, {@link #ANUIDADE},
 *       {@link #AJUSTE}) são <b>rateados</b> entre todo mundo que usou o cartão
 *       no mês — ninguém reivindica, e não há como um só ser o culpado do IOF
 *       de uma fatura que várias pessoas movimentaram;</li>
 *   <li>{@link #PAGAMENTO} é a quitação da fatura anterior, feita pelo titular:
 *       não é gasto de ninguém e fica com ele.</li>
 * </ul>
 */
public enum TipoLancamento {
    COMPRA,
    ESTORNO,
    ENCARGO,
    PAGAMENTO,
    IOF,
    ANUIDADE,
    AJUSTE;

    /** Só compras e estornos podem ser assumidos ou divididos por um utilizador. */
    public boolean reivindicavel() {
        return this == COMPRA || this == ESTORNO;
    }

    /**
     * Encargo: dividido entre todos os participantes da fatura, sempre.
     * O pagamento da fatura anterior fica de fora — é do titular.
     */
    public boolean rateavel() {
        return this != COMPRA && this != ESTORNO && this != PAGAMENTO;
    }
}
