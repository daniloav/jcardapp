package br.com.jcard.model;

/**
 * Ciclo de vida da fatura.
 *
 * <p>{@code IMPORTADA → EM_AVALIACAO → CONCILIADA → FECHADA}, com desvio para
 * {@code DIVERGENTE} quando a soma dos lançamentos não bate com o total impresso
 * na fatura — nesse caso nada avança até um humano resolver.
 */
public enum StatusFatura {
    /** PDF lido e lançamentos gravados; ainda não validada. */
    IMPORTADA,
    /** A soma dos lançamentos ≠ total da fatura. Bloqueia o fluxo. */
    DIVERGENTE,
    /** Utilizadores podem reivindicar lançamentos. */
    EM_AVALIACAO,
    /** Todo lançamento tem dono (ou sobra atribuída ao titular); acertos calculados. */
    CONCILIADA,
    /** Todos os acertos confirmados pelo admin. Encerrada. */
    FECHADA
}
