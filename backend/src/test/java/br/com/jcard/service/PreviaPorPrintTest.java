package br.com.jcard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jcard.model.Acerto;
import br.com.jcard.model.ApelidoEstabelecimento;
import br.com.jcard.model.Cartao;
import br.com.jcard.model.ComprovantePagamento;
import br.com.jcard.model.CompromissoParcelado;
import br.com.jcard.model.DivisaoLancamento;
import br.com.jcard.model.Fatura;
import br.com.jcard.model.Lancamento;
import br.com.jcard.model.OrigemAtribuicao;
import br.com.jcard.model.Reivindicacao;
import br.com.jcard.model.StatusFatura;
import br.com.jcard.model.TipoLancamento;
import br.com.jcard.model.Usuario;
import br.com.jcard.parser.FaturaLida;
import br.com.jcard.parser.ItauPrintParser;
import br.com.jcard.parser.LancamentoLido;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A prévia montada a print, para quem não consegue o CSV.
 *
 * <p>Duas promessas sustentam o recurso, e é o que estes testes defendem:
 *
 * <ol>
 *   <li><b>Print soma, não substitui.</b> O CSV é o mês inteiro e por isso
 *       sobrescreve; um print é um pedaço, e cinco prints são cinco pedaços. Se
 *       o segundo apagasse o primeiro, anexar seria trabalho jogado fora.</li>
 *   <li><b>O que o OCR lê é proposta.</b> Nada entra sem uma pessoa confirmar —
 *       OCR troca dígito, e numa prévia não existe total impresso para
 *       denunciar. Por isso o leitor devolve linhas e a gravação recebe linhas
 *       <i>confirmadas</i>, que podem ser diferentes do que a máquina entendeu.</li>
 * </ol>
 */
@QuarkusTest
class PreviaPorPrintTest {

    private static final LocalDate AGOSTO = LocalDate.parse("2026-08-01");

    /** Como a tela do app costuma sair no OCR: cabeçalho, blocos e rodapé. */
    private static final String PRINT = """
            Fatura em aberto
            Total parcial
            R$ 1.079,55

            05 ago
            SUPERMERCADO ANGELONI
            R$ 312,45

            07/08 POSTO IPIRANGA R$ 180,00

            08 ago
            LOJA DE MOVEIS
            Parcela 2 de 10
            R$ 190,00

            Ver mais
            """;

    @Inject
    ItauPrintParser parser;

    @Inject
    PreviaService previas;

    @Inject
    FaturaImportService importacao;

    @Inject
    ReivindicacaoService reivindicacoes;

    @Inject
    ParcelasPrevistasService parcelasPrevistas;

    private Usuario titular;
    private Usuario joao;

    @BeforeEach
    @Transactional
    void limpar() {
        ComprovantePagamento.deleteAll();
        Acerto.deleteAll();
        Reivindicacao.deleteAll();
        DivisaoLancamento.deleteAll();
        CompromissoParcelado.deleteAll();
        ApelidoEstabelecimento.deleteAll();
        Lancamento.deleteAll();
        Fatura.deleteAll();
        Cartao.deleteAll();
        Usuario.deleteAll();

        titular = novoUsuario("Jose Titular", "jose@teste.local", true);
        joao = novoUsuario("Joao Filho", "joao@teste.local", false);
    }

    // ------------------------------------------------------------ leitura --

    @Test
    @DisplayName("lê o print em bloco e em linha única, e não confunde total com compra")
    void leOPrintDaTelaDoApp() {
        FaturaLida lida = parser.ler(PRINT, AGOSTO);

        assertEquals(3, lida.lancamentos().size(),
                "três compras; o total parcial e o rodapé não são lançamento");

        LancamentoLido mercado = lida.lancamentos().get(0);
        assertEquals(LocalDate.parse("2026-08-05"), mercado.dataCompra());
        assertEquals("SUPERMERCADO ANGELONI", mercado.descricao());
        assertEquals(0, new BigDecimal("312.45").compareTo(mercado.valor()));

        LancamentoLido posto = lida.lancamentos().get(1);
        assertEquals(LocalDate.parse("2026-08-07"), posto.dataCompra(),
                "a mesma linha traz data, loja e valor — e isso também tem de sair");
        assertEquals(0, new BigDecimal("180.00").compareTo(posto.valor()));

        LancamentoLido moveis = lida.lancamentos().get(2);
        assertEquals(2, moveis.parcelaAtual());
        assertEquals(10, moveis.parcelaTotal());
        assertEquals("LOJA DE MOVEIS", moveis.descricao(),
                "a linha da parcela descreve a compra, não é parte do nome da loja");

        assertTrue(lida.lancamentos().stream().allMatch(l -> l.tipo() == TipoLancamento.COMPRA));
    }

    @Test
    @DisplayName("o que não vira linha inteira aparece como não reconhecido, não some")
    void oQueNaoFechaVoltaParaOAdmin() {
        FaturaLida lida = parser.ler("""
                12 ago
                LOJA SEM VALOR NENHUM

                13 ago
                OUTRA LOJA
                R$ 50,00
                """, AGOSTO);

        assertEquals(1, lida.lancamentos().size(), "só a que tem data, loja e valor");
        assertTrue(lida.linhasIgnoradas().stream().anyMatch(s -> s.contains("SEM VALOR")),
                "sem valor não vira lançamento pela metade — e some da tela seria pior: "
                        + "o admin precisa ver o que faltou para digitar à mão");
        assertTrue(lida.lancamentos().get(0).descricao().contains("OUTRA LOJA"));
    }

    @Test
    @DisplayName("o print de verdade: 'O5 ago' e a parcela que vem depois do valor")
    void oQueOOcrRealDevolve() {
        // Texto copiado de uma leitura de verdade. Dois defeitos aparecem só aqui:
        // o zero da data lido como letra O, e a linha da parcela chegando depois
        // do valor — porque o app põe o valor à direita, na altura do nome da loja.
        FaturaLida lida = parser.ler("""
                Fatura em aberto
                Total parcial
                R$ 1.079,55
                O5 ago
                SUPERMERCADO ANGELONI R$ 312,45
                08 ago
                LOJA DE MOVEIS R$ 190,00
                Parcela 2 de 10
                Ver mais
                """, AGOSTO);

        assertEquals(2, lida.lancamentos().size(),
                "a compra de 'O5 ago' não pode se perder por causa de uma letra");

        LancamentoLido mercado = lida.lancamentos().get(0);
        assertEquals(LocalDate.parse("2026-08-05"), mercado.dataCompra(),
                "data não é dinheiro: corrigir O por 0 aqui não cobra nada de ninguém, "
                        + "e o admin ainda confere a data antes de somar");
        assertEquals(0, new BigDecimal("312.45").compareTo(mercado.valor()));

        LancamentoLido moveis = lida.lancamentos().get(1);
        assertEquals(2, moveis.parcelaAtual(),
                "sem a parcela a compra entraria solta, e parcela sem número não gruda "
                        + "com quem a assumiu — a regra que sustenta o app deixaria de valer");
        assertEquals(10, moveis.parcelaTotal());
    }

    @Test
    @DisplayName("o leitor não conserta dígito: valor ilegível fica de fora")
    void naoAdivinhaDinheiro() {
        FaturaLida lida = parser.ler("""
                14 ago
                PADARIA DO BAIRRO
                R$ 18O,0O
                """, AGOSTO);

        assertTrue(lida.lancamentos().isEmpty(),
                "trocar O por 0 para fazer o valor casar é adivinhar dinheiro dos outros "
                        + "em silêncio — a linha volta para o admin digitar");
        assertTrue(lida.linhasIgnoradas().stream().anyMatch(s -> s.contains("PADARIA")));
    }

    // -------------------------------------------------------------- soma --

    @Test
    @DisplayName("o primeiro print inaugura a prévia do mês")
    void primeiroPrintCriaAPrevia() {
        assertNull(Fatura.previaDa(AGOSTO));

        PreviaService.ResultadoSoma r = somar(
                linha("2026-08-05", "SUPERMERCADO ANGELONI", "312.45"),
                linha("2026-08-07", "POSTO IPIRANGA", "180.00"));

        assertEquals(2, r.somados());
        assertEquals(0, r.repetidos());
        assertEquals(StatusFatura.PREVIA, recarregar(r.fatura()).status);
        assertEquals(0, new BigDecimal("492.45").compareTo(recarregar(r.fatura()).valorTotal),
                "o total da prévia é a soma do que ela tem — não há total impresso");
        assertEquals(2, r.noPool(), "nascem no pool, como qualquer lançamento");
    }

    @Test
    @DisplayName("o segundo print soma ao primeiro, em vez de substituí-lo")
    void printsSeSomam() {
        somar(linha("2026-08-05", "SUPERMERCADO ANGELONI", "312.45"));
        PreviaService.ResultadoSoma r = somar(linha("2026-08-08", "LOJA DE MOVEIS", "190.00"));

        assertEquals(1, r.somados());
        assertEquals(2, r.total(),
                "anexar o segundo print não pode desfazer o primeiro — é o recurso inteiro");
        assertEquals(0, new BigDecimal("502.45").compareTo(recarregar(r.fatura()).valorTotal));
        assertEquals(1, Fatura.count("status", StatusFatura.PREVIA), "uma prévia por mês");
    }

    @Test
    @DisplayName("a linha que dois prints pegam entra uma vez só")
    void linhaRepetidaEDescartada() {
        somar(linha("2026-08-05", "SUPERMERCADO ANGELONI", "312.45"));

        // O segundo print pegou a mesma compra na sobreposição da rolagem.
        PreviaService.ResultadoSoma r = somar(
                linha("2026-08-05", "SUPERMERCADO ANGELONI", "312.45"),
                linha("2026-08-06", "FARMACIA PANVEL", "62.90"));

        assertEquals(1, r.somados());
        assertEquals(1, r.repetidos(), "descartada, mas contada: sumir sem dizer esconderia "
                + "a compra que se repete de verdade no mesmo dia");
        assertEquals(2, r.total());
    }

    @Test
    @DisplayName("o que a pessoa já assumiu sobrevive ao print seguinte")
    void oQueFoiAssumidoNaoSePerde() {
        PreviaService.ResultadoSoma primeiro = somar(
                linha("2026-08-05", "SUPERMERCADO ANGELONI", "312.45"));
        reivindicacoes.reivindicar(
                buscar(primeiro.fatura(), "SUPERMERCADO ANGELONI").id, joao, null);

        somar(linha("2026-08-06", "FARMACIA PANVEL", "62.90"));

        Lancamento mercado = buscar(primeiro.fatura(), "SUPERMERCADO ANGELONI");
        assertEquals(joao.id, mercado.responsavel.getId(),
                "somar é somar: o print novo não toca no que já foi assumido");
        assertEquals(OrigemAtribuicao.MANUAL, mercado.origemAtribuicao);
    }

    @Test
    @DisplayName("parcelamento gruda também no print, e a parcela sai da previsão")
    void parcelaAssumidaNasceComDonoNoPrint() {
        // Julho fechou com o João assumindo a 1/10.
        Fatura julho = importacao.importar(
                csv("2;1;08/07;LOJA DE MOVEIS;01/10;190,00"), "julho.csv",
                LocalDate.parse("2026-07-01"), new BigDecimal("190.00"), titular);
        reivindicacoes.reivindicar(buscar(julho, "LOJA DE MOVEIS").id, joao, null);
        assertEquals(1, previstas().size(), "agosto ainda espera a 2/10");

        PreviaService.ResultadoSoma r = somar(
                new PreviaService.Linha(LocalDate.parse("2026-08-08"), "LOJA DE MOVEIS",
                        new BigDecimal("190.00"), 2, 10));

        Lancamento parcela = buscar(r.fatura(), "LOJA DE MOVEIS");
        assertEquals(joao.id, parcela.responsavel.getId(),
                "quem assumiu a primeira parcela segue dono das seguintes — inclusive "
                        + "quando a parcela entra por print");
        assertEquals(OrigemAtribuicao.HERDADA_PARCELA, parcela.origemAtribuicao);
        assertEquals(1, r.batimento().conferidas().size(), "a parcela esperada chegou");
        assertTrue(previstas().isEmpty(), "e deixou de ser previsão");
        assertTrue(compromissos().get(0).ativo,
                "a prévia lê o compromisso, mas não o encerra — a fatura de verdade "
                        + "ainda precisa herdar dele");
    }

    @Test
    @DisplayName("o CSV, quando aparece, substitui os prints sem perder o que foi assumido")
    void csvDepoisDoPrintSubstitui() {
        PreviaService.ResultadoSoma r = somar(
                linha("2026-08-05", "SUPERMERCADO ANGELONI", "312.45"),
                linha("2026-08-06", "FARMACIA PANVEL", "62.90"));
        reivindicacoes.reivindicar(buscar(r.fatura(), "SUPERMERCADO ANGELONI").id, joao, null);

        PreviaService.Resultado doCsv = previas.subir(
                csv("2;1;05/08;SUPERMERCADO ANGELONI;;312,45", "2;1;09/08;NETFLIX.COM;;44,90"),
                "previa.csv", AGOSTO, titular);

        assertEquals(2, doCsv.lancamentos(), "o CSV é a leitura do banco e vale mais que o print");
        assertEquals(1, doCsv.mantidos(),
                "a compra que o João já tinha assumido continua dele");
        assertEquals(joao.id,
                buscar(doCsv.fatura(), "SUPERMERCADO ANGELONI").responsavel.getId());
    }

    @Test
    @DisplayName("mês já importado não aceita print")
    void mesFechadoRecusaPrint() {
        importacao.importar(csv("2;1;05/08;SUPERMERCADO ANGELONI;;312,45"), "agosto.csv",
                AGOSTO, new BigDecimal("312.45"), titular);

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> somar(linha("2026-08-09", "NETFLIX.COM", "44.90")));
        assertEquals(409, e.getResponse().getStatus());
    }

    // ------------------------------------------------------------- apoio --

    private PreviaService.ResultadoSoma somar(PreviaService.Linha... linhas) {
        return previas.somar(AGOSTO, List.of(linhas), titular);
    }

    private static PreviaService.Linha linha(String data, String descricao, String valor) {
        return new PreviaService.Linha(LocalDate.parse(data), descricao,
                new BigDecimal(valor), null, null);
    }

    private static byte[] csv(String... linhas) {
        StringBuilder sb = new StringBuilder("pagina;coluna;data;estabelecimento;parcela;valor\n");
        for (String l : linhas) {
            sb.append(l).append("\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    List<ParcelasPrevistasService.Prevista> previstas() {
        Fatura.getEntityManager().clear();
        return parcelasPrevistas.doMesEmAberto(AGOSTO);
    }

    @Transactional
    List<CompromissoParcelado> compromissos() {
        CompromissoParcelado.getEntityManager().clear();
        return CompromissoParcelado.listAll();
    }

    @Transactional
    Fatura recarregar(Fatura f) {
        Fatura.getEntityManager().clear();
        return Fatura.findById(f.id);
    }

    @Transactional
    Lancamento buscar(Fatura f, String descricao) {
        Lancamento.getEntityManager().clear();
        Lancamento l = Lancamento.find("fatura.id = ?1 and descricao = ?2", f.id, descricao)
                .firstResult();
        assertNotNull(l, "esperava o lançamento '" + descricao + "' na fatura " + f.competencia);
        return l;
    }

    @Transactional
    Usuario novoUsuario(String nome, String email, boolean admin) {
        Usuario u = new Usuario();
        u.nome = nome;
        u.email = email;
        u.login = email.split("@")[0];
        u.senhaHash = "x";
        u.admin = admin;
        u.utilizador = true;
        u.precisaTrocarSenha = false;
        u.persist();
        return u;
    }
}
