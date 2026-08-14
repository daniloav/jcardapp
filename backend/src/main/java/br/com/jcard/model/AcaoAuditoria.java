package br.com.jcard.model;

/** Ações registradas na trilha de auditoria. */
public enum AcaoAuditoria {
    CRIAR,
    ATUALIZAR,
    EXCLUIR,
    IMPORTAR_FATURA,
    EXCLUIR_FATURA,
    REIVINDICAR,
    DESISTIR,
    DIVIDIR,
    ARBITRAR,
    CONCILIAR,
    REABRIR_AVALIACAO,
    APELIDAR,
    FECHAR_FATURA,
    ACEITAR_VALOR,
    INFORMAR_PAGAMENTO,
    CONFIRMAR_PAGAMENTO,
    /** Baixa dada pelo admin em nome de quem pagou e não mandou comprovante. */
    REGISTRAR_BAIXA,
    LOGIN
}
