package br.com.jcard.model;

/** Situação de uma reivindicação de lançamento. */
public enum StatusReivindicacao {
    /** Aguardando: vira conflito se houver 2+ pendentes no mesmo lançamento. */
    PENDENTE,
    ACEITA,
    REJEITADA
}
