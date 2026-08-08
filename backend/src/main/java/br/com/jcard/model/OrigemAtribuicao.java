package br.com.jcard.model;

/**
 * De onde veio a atribuição de um lançamento a um utilizador. Fica visível na UI
 * para que ninguém seja surpreendido por uma conta que "apareceu" no nome dele.
 */
public enum OrigemAtribuicao {
    /** O próprio utilizador reivindicou e foi aceito. */
    MANUAL,
    /** Herdada de um parcelamento já assumido numa fatura anterior. */
    HERDADA_PARCELA,
    /** Cartão adicional com dono padrão configurado. */
    REGRA_CARTAO,
    /** O admin atribuiu na arbitragem. */
    ADMIN
}
