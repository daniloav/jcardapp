package br.com.jcard.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Conversões de texto do PDF — onde um erro vira centavo errado na conta de alguém. */
class TextoFaturaTest {

    @Test
    @DisplayName("dinheiro em pt-BR vira BigDecimal, com sinal antes ou depois")
    void valor() {
        assertEquals(new BigDecimal("1234.56"), TextoFatura.valor("1.234,56"));
        assertEquals(new BigDecimal("89.90"), TextoFatura.valor("89,90"));
        assertEquals(new BigDecimal("-1500.00"), TextoFatura.valor("-1.500,00"));
        assertEquals(new BigDecimal("-45.60"), TextoFatura.valor("45,60-"));
        assertEquals(new BigDecimal("250.00"), TextoFatura.valor(" R$ 250,00 "));
    }

    @Test
    @DisplayName("normalizar tira acento, caixa, sufixo de parcela e ruído volátil")
    void normalizar() {
        assertEquals("POSTO SHELL CENTRO", TextoFatura.normalizar("Posto Shell Centro 03/10"));
        assertEquals("PADARIA SAO JOSE", TextoFatura.normalizar("Padaria São José"));
        assertEquals("IFOOD RESTAURANTE", TextoFatura.normalizar("IFOOD *RESTAURANTE"));
        // Números longos (autorização/NSU) mudam a cada compra e quebrariam o
        // casamento entre parcelas de meses diferentes.
        assertEquals("LOJA XYZ", TextoFatura.normalizar("LOJA XYZ 998877665"));
    }

    @Test
    @DisplayName("parcela só é reconhecida em faixa plausível")
    void parcela() {
        assertEquals(3, TextoFatura.parcela("POSTO SHELL 03/10")[0]);
        assertEquals(10, TextoFatura.parcela("POSTO SHELL 03/10")[1]);
        assertNull(TextoFatura.parcela("COMPRA SEM PARCELA"));
        // 01/01 é compra à vista impressa com sufixo, não parcelamento.
        assertNull(TextoFatura.parcela("LOJA 01/01"));
        assertNull(TextoFatura.parcela("LOJA 05/03"));
    }

    @Test
    @DisplayName("compra de dezembro numa fatura de janeiro é do ano anterior")
    void anoVirada() {
        LocalDate janeiro = LocalDate.of(2026, 1, 1);
        assertEquals(LocalDate.of(2025, 12, 28), TextoFatura.comAno(28, 12, janeiro));
        assertEquals(LocalDate.of(2026, 1, 3), TextoFatura.comAno(3, 1, janeiro));

        LocalDate agosto = LocalDate.of(2026, 8, 1);
        assertEquals(LocalDate.of(2026, 7, 5), TextoFatura.comAno(5, 7, agosto));
    }

    @Test
    @DisplayName("mesma compra parcelada gera a mesma chave, valor não entra na conta")
    void chaveEstavel() {
        String a = ChaveParcelamento.de("POSTO SHELL CENTRO", "1234", 10);
        String b = ChaveParcelamento.de("POSTO SHELL CENTRO", "1234", 10);
        String outroCartao = ChaveParcelamento.de("POSTO SHELL CENTRO", "5678", 10);
        assertEquals(a, b);
        org.junit.jupiter.api.Assertions.assertNotEquals(a, outroCartao);
    }
}
