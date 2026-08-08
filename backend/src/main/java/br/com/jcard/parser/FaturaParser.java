package br.com.jcard.parser;

import java.time.LocalDate;

/**
 * Leitor de fatura de um emissor. Hoje só existe o {@link ItauFaturaParser};
 * a interface está aqui para que somar Nubank/BB depois não mexa no
 * {@code FaturaImportService}.
 */
public interface FaturaParser {

    /** Identificador do emissor, gravado na fatura (ex.: {@code ITAU}). */
    String emissor();

    /**
     * @param texto       texto já extraído do PDF
     * @param competencia mês de referência informado por quem importou
     */
    FaturaLida ler(String texto, LocalDate competencia);
}
