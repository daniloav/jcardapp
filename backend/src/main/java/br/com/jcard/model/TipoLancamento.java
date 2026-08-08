package br.com.jcard.model;

/**
 * Natureza do lançamento. Só {@link #COMPRA} é reivindicável pelos utilizadores;
 * o resto é responsabilidade do titular por padrão (encargos, anuidade) ou não
 * representa gasto (pagamento da fatura anterior).
 */
public enum TipoLancamento {
    COMPRA,
    ESTORNO,
    ENCARGO,
    PAGAMENTO,
    IOF,
    ANUIDADE,
    AJUSTE;

    /** Só compras e estornos podem ser assumidos por um utilizador. */
    public boolean reivindicavel() {
        return this == COMPRA || this == ESTORNO;
    }
}
