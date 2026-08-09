package br.com.jcard.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jcard.model.TipoLancamento;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Leitura da fatura em CSV.
 *
 * <p>O fixture é anonimizado mas reproduz os casos-limite que apareceram numa
 * fatura real: parcelada, à vista com o campo de parcela vazio, crédito
 * negativo, desconto por antecipação, anuidade e o estorno dela.
 */
class ItauCsvParserTest {

    private static ItauCsvParser parser;
    private static String csv;
    private static final LocalDate COMPETENCIA = LocalDate.of(2026, 8, 1);

    @BeforeAll
    static void carregar() throws IOException {
        parser = new ItauCsvParser();
        try (InputStream in = ItauCsvParserTest.class
                .getResourceAsStream("/fatura-itau-exemplo.csv")) {
            csv = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("lê todas as linhas, sem sobrar nenhuma")
    void leTudo() {
        FaturaLida f = parser.ler(csv, COMPETENCIA);
        assertEquals(10, f.lancamentos().size());
        assertTrue(f.linhasIgnoradas().isEmpty(), "ignoradas: " + f.linhasIgnoradas());
    }

    /** A soma é a base da invariante — o total vem de quem importa. */
    @Test
    @DisplayName("soma reproduz o líquido, com os créditos abatendo")
    void somaComCreditos() {
        FaturaLida f = parser.ler(csv, COMPETENCIA);
        assertEquals(new BigDecimal("581.62"), f.somaLancamentos());
    }

    @Test
    @DisplayName("campo de parcela vazio significa compra à vista")
    void parcelaVazia() {
        FaturaLida f = parser.ler(csv, COMPETENCIA);
        LancamentoLido aVista = achar(f, "PADARIA");
        assertNull(aVista.parcelaAtual());
        assertNull(aVista.parcelaTotal());

        LancamentoLido parcelada = achar(f, "LOJA PARCELADA");
        assertEquals(11, parcelada.parcelaAtual());
        assertEquals(12, parcelada.parcelaTotal());
    }

    @Test
    @DisplayName("crédito mantém o sinal negativo que já vem no arquivo")
    void creditoNegativo() {
        FaturaLida f = parser.ler(csv, COMPETENCIA);
        assertEquals(new BigDecimal("-180.49"), achar(f, "LOJA DE ROUPAS").valor());
        assertEquals(TipoLancamento.ESTORNO, achar(f, "LOJA DE ROUPAS").tipo());
    }

    @Test
    @DisplayName("desconto por antecipação e estorno de anuidade são créditos")
    void descontosSaoEstorno() {
        FaturaLida f = parser.ler(csv, COMPETENCIA);
        assertEquals(TipoLancamento.ESTORNO, achar(f, "DESC ANTECIPA").tipo());
        assertEquals(TipoLancamento.ESTORNO, achar(f, "ESTORNO DE ANUIDADE").tipo());
    }

    @Test
    @DisplayName("anuidade e seguro não são reivindicáveis")
    void encargosDoTitular() {
        FaturaLida f = parser.ler(csv, COMPETENCIA);
        assertEquals(TipoLancamento.ANUIDADE, achar(f, "ANUIDADE DIFERENCI").tipo());
        assertEquals(TipoLancamento.ENCARGO, achar(f, "SEGURO").tipo());
        assertTrue(!achar(f, "ANUIDADE DIFERENCI").tipo().reivindicavel());
    }

    @Test
    @DisplayName("compra de setembro numa fatura de agosto é do ano anterior")
    void anoDaVirada() {
        FaturaLida f = parser.ler(csv, COMPETENCIA);
        assertEquals(LocalDate.of(2025, 9, 8), achar(f, "LOJA PARCELADA").dataCompra());
        assertEquals(LocalDate.of(2026, 7, 7), achar(f, "PADARIA").dataCompra());
    }

    /**
     * O CSV não traz o cartão, então tudo nasce no pool — que é o fluxo normal:
     * cada pessoa reivindica o que reconhece.
     */
    @Test
    @DisplayName("sem cartão no arquivo, os lançamentos vão para o pool")
    void semCartao() {
        FaturaLida f = parser.ler(csv, COMPETENCIA);
        assertTrue(f.lancamentos().stream().allMatch(l -> l.final4() == null));
    }

    @Test
    @DisplayName("o CSV não traz o total — quem importa informa")
    void semTotal() {
        assertNull(parser.ler(csv, COMPETENCIA).valorTotal());
    }

    @Test
    @DisplayName("cabeçalho sem as colunas obrigatórias é recusado com instrução")
    void cabecalhoInvalido() {
        var e = assertThrows(jakarta.ws.rs.WebApplicationException.class,
                () -> parser.ler("a;b;c\n1;2;3\n", COMPETENCIA));
        assertEquals(422, e.getResponse().getStatus());
        assertTrue(e.getMessage().contains("pagina;coluna;data"));
    }

    @Test
    @DisplayName("a ordem das colunas não importa — o cabeçalho manda")
    void ordemLivre() {
        String outro = """
                valor;data;estabelecimento;parcela
                190,00;08/09;LOJA PARCELADA;11/12
                """;
        FaturaLida f = parser.ler(outro, COMPETENCIA);
        assertEquals(1, f.lancamentos().size());
        assertEquals(new BigDecimal("190.00"), f.lancamentos().get(0).valor());
        assertEquals(11, f.lancamentos().get(0).parcelaAtual());
    }

    private LancamentoLido achar(FaturaLida f, String trecho) {
        LancamentoLido l = f.lancamentos().stream()
                .filter(x -> x.descricaoNormalizada().contains(trecho))
                .findFirst().orElse(null);
        assertNotNull(l, "não achei '" + trecho + "'");
        return l;
    }
}
