package br.com.jcard.model;

/** Ciclo de quitação do que um utilizador deve numa fatura. */
public enum StatusAcerto {
    /** Valor apurado, ainda não conferido pela pessoa. */
    ABERTO,
    /**
     * A pessoa conferiu o somatório e concordou com ele. É o passo que abre o
     * formulário de pagamento: discutir valor depois do dinheiro sair é pior.
     */
    ACEITO,
    /** O utilizador declarou que pagou e anexou o comprovante; aguarda o admin. */
    INFORMADO,
    /** O admin confirmou o recebimento. */
    CONFIRMADO
}
