package br.com.jcard.model;

/** Ciclo de quitação do que um utilizador deve numa fatura. */
public enum StatusAcerto {
    /** Valor apurado, ainda não pago. */
    ABERTO,
    /** O utilizador declarou que pagou; aguarda conferência do admin. */
    INFORMADO,
    /** O admin confirmou o recebimento. */
    CONFIRMADO
}
