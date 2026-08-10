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
    /** O admin atribuiu: arbitrando um conflito ou apontando o dono direto. */
    ADMIN,
    /**
     * Ninguém assumiu e a conciliação passou ao titular.
     *
     * <p>Separada de {@link #ADMIN} porque é a única que a reabertura da
     * avaliação desfaz: devolver ao pool o que ficou com o titular por falta de
     * dono é reverter um padrão, enquanto desfazer uma arbitragem seria apagar
     * uma decisão que o admin tomou de propósito.
     */
    SOBRA_CONCILIACAO
}
