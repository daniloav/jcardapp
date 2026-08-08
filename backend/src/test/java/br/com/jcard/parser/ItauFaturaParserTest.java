package br.com.jcard.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jcard.model.TipoLancamento;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Leitura do layout do Itaú contra um texto de exemplo anonimizado.
 *
 * <p>As regexes vêm do <b>application.properties</b> de verdade, não de cópias no
 * teste: assim, calibrar o parser para uma fatura nova quebra o teste aqui se
 * quebrar o comportamento — que é exatamente o alarme que queremos.
 */
class ItauFaturaParserTest {

    private static ItauFaturaParser parser;
    private static String texto;
    private static final LocalDate COMPETENCIA = LocalDate.of(2026, 8, 1);

    @BeforeAll
    static void carregar() throws IOException {
        Properties p = new Properties();
        try (InputStream in = ItauFaturaParserTest.class
                .getResourceAsStream("/application.properties")) {
            p.load(in);
        }
        parser = new ItauFaturaParser(
                p.getProperty("jcard.parser.itau.portador"),
                p.getProperty("jcard.parser.itau.lancamento"),
                p.getProperty("jcard.parser.itau.total"),
                p.getProperty("jcard.parser.itau.vencimento"));

        try (InputStream in = ItauFaturaParserTest.class
                .getResourceAsStream("/fatura-itau-exemplo.txt")) {
            texto = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("lê todos os lançamentos e ignora cabeçalho/rodapé")
    void leTodasAsLinhas() {
        FaturaLida f = parser.ler(texto, COMPETENCIA);
        assertEquals(10, f.lancamentos().size(),
                "esperava 10 lançamentos; ignoradas: " + f.linhasIgnoradas());
        assertTrue(f.linhasIgnoradas().isEmpty(),
                "nenhuma linha com cara de lançamento deveria sobrar: " + f.linhasIgnoradas());
    }

    @Test
    @DisplayName("total e vencimento saem do cabeçalho")
    void cabecalho() {
        FaturaLida f = parser.ler(texto, COMPETENCIA);
        assertEquals(new BigDecimal("638.96"), f.valorTotal());
        assertEquals(LocalDate.of(2026, 8, 10), f.vencimento());
    }

    /** A invariante nº 1: o que lemos tem de reproduzir o total impresso. */
    @Test
    @DisplayName("soma dos lançamentos bate com o total da fatura")
    void somaFecha() {
        FaturaLida f = parser.ler(texto, COMPETENCIA);
        assertEquals(0, f.valorTotal().compareTo(f.somaLancamentos()),
                "soma lida = " + f.somaLancamentos() + ", total = " + f.valorTotal());
    }

    @Test
    @DisplayName("cada lançamento herda o portador da seção em que está")
    void portadorPorSecao() {
        FaturaLida f = parser.ler(texto, COMPETENCIA);
        LancamentoLido mercado = acharPor(f, "SUPERMERCADO");
        assertEquals("JOAO C OLIVEIRA", mercado.portadorNome());
        assertEquals("1234", mercado.final4());

        LancamentoLido drogaria = acharPor(f, "DROGARIA");
        assertEquals("MARIA S OLIVEIRA", drogaria.portadorNome());
        assertEquals("5678", drogaria.final4());

        LancamentoLido uber = acharPor(f, "UBER");
        assertEquals("9012", uber.final4());
    }

    @Test
    @DisplayName("parcela 03/10 é reconhecida e sai da descrição normalizada")
    void parcelamento() {
        FaturaLida f = parser.ler(texto, COMPETENCIA);
        LancamentoLido posto = acharPor(f, "POSTO SHELL");
        assertEquals(3, posto.parcelaAtual());
        assertEquals(10, posto.parcelaTotal());
        assertEquals("POSTO SHELL CENTRO", posto.descricaoNormalizada());

        LancamentoLido magazine = acharPor(f, "MAGAZINE");
        assertEquals(6, magazine.parcelaAtual());
        assertEquals(12, magazine.parcelaTotal());
    }

    @Test
    @DisplayName("estorno vira crédito e encargos são classificados")
    void classificacao() {
        FaturaLida f = parser.ler(texto, COMPETENCIA);

        LancamentoLido estorno = acharPor(f, "ESTORNO");
        assertEquals(TipoLancamento.ESTORNO, estorno.tipo());
        assertEquals(new BigDecimal("-45.60"), estorno.valor(),
                "estorno precisa entrar negativo para o total fechar");

        assertEquals(TipoLancamento.ANUIDADE, acharPor(f, "ANUIDADE").tipo());
        assertEquals(TipoLancamento.IOF, acharPor(f, "IOF").tipo());
        assertEquals(TipoLancamento.COMPRA, acharPor(f, "NETFLIX").tipo());
    }

    @Test
    @DisplayName("datas dd/MM ganham o ano certo a partir da competência")
    void datas() {
        FaturaLida f = parser.ler(texto, COMPETENCIA);
        assertEquals(LocalDate.of(2026, 7, 5), acharPor(f, "SUPERMERCADO").dataCompra());
    }

    @Test
    @DisplayName("encargos e anuidade não são reivindicáveis pelos utilizadores")
    void naoReivindicavel() {
        FaturaLida f = parser.ler(texto, COMPETENCIA);
        assertTrue(acharPor(f, "SUPERMERCADO").tipo().reivindicavel());
        assertTrue(!acharPor(f, "ANUIDADE").tipo().reivindicavel());
        assertTrue(!acharPor(f, "IOF").tipo().reivindicavel());
    }

    private LancamentoLido acharPor(FaturaLida f, String trecho) {
        LancamentoLido achado = f.lancamentos().stream()
                .filter(l -> l.descricaoNormalizada().contains(trecho))
                .findFirst()
                .orElse(null);
        assertNotNull(achado, "não achei lançamento com '" + trecho + "'");
        return achado;
    }
}
