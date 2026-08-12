package br.com.jcard.model;

/**
 * Ciclo de vida da fatura.
 *
 * <p>{@code IMPORTADA → EM_AVALIACAO → CONCILIADA → FECHADA}, com desvio para
 * {@code DIVERGENTE} quando a soma dos lançamentos não bate com o total impresso
 * na fatura — nesse caso nada avança até um humano resolver.
 *
 * <p>{@link #PREVIA} fica <b>fora</b> desse ciclo: é a fatura do mês que ainda
 * não fechou, e ela nunca vira nenhum dos outros estados. Quando a fatura de
 * verdade chega, a prévia é consumida (as atribuições passam para ela) e some.
 */
public enum StatusFatura {
    /**
     * Parcial do mês em curso, subida quantas vezes o admin quiser.
     *
     * <p>Não gera acerto, não concilia e não fecha: nela ninguém deve nada
     * ainda. Serve para as pessoas irem assumindo o que é delas ao longo do mês,
     * em vez de encarar 514 linhas de uma vez no dia do vencimento.
     */
    PREVIA,
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
