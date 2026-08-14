package br.com.jcard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import br.com.jcard.model.Usuario;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * As parcelas que o mês em aberto <b>já sabe</b> que vai receber.
 *
 * <p>"Parcelamento gruda" sempre valeu, mas só aparecia quando o arquivo do
 * banco chegava. Quem assumiu a 1/10 da geladeira deve nove parcelas e não tinha
 * onde ver isso: a prévia começava vazia e ia enchendo com o CSV, como se o mês
 * não tivesse compromisso nenhum — e o mês tem.
 *
 * <p>É o que estes testes defendem, dos dois lados: a previsão aparece antes de
 * qualquer arquivo, e some no instante em que o arquivo traz a parcela de
 * verdade. Somar as duas cobraria a mesma parcela duas vezes.
 */
@QuarkusTest
class ParcelasPrevistasTest {

    private static final String CABECALHO = "pagina;coluna;data;estabelecimento;parcela;valor";

    private static final LocalDate JULHO = LocalDate.parse("2026-07-01");
    private static final LocalDate AGOSTO = LocalDate.parse("2026-08-01");
    private static final LocalDate SETEMBRO = LocalDate.parse("2026-09-01");

    @Inject
    ParcelasPrevistasService previstas;

    @Inject
    PreviaService previas;

    @Inject
    FaturaImportService importacao;

    @Inject
    ReivindicacaoService reivindicacoes;

    @Inject
    UsuarioService usuarios;

    private Usuario titular;
    private Usuario joao;
    private Usuario maria;

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
        maria = novoUsuario("Maria Filha", "maria@teste.local", false);
    }

    @Test
    @DisplayName("a parcela do mês que vem já é conhecida antes de qualquer CSV")
    void previsaoExisteSemArquivoNenhum() {
        assumirEmJulho("2;1;08/07;LOJA PARCELADA;01/10;190,00");

        List<ParcelasPrevistasService.Prevista> agosto = doMes(AGOSTO);
        assertEquals(1, agosto.size(),
                "o compromisso promete a próxima parcela; esperar o arquivo para dizer isso "
                        + "faria o mês começar em zero sabendo que não está");

        ParcelasPrevistasService.Prevista p = agosto.get(0);
        assertEquals(joao.id, p.usuarioId(), "é de quem assumiu a primeira parcela");
        assertEquals(2, p.parcela());
        assertEquals(10, p.parcelaTotal());
        assertEquals(0, new BigDecimal("190.00").compareTo(p.valor()));
        assertFalse(p.jaVeio());
        assertEquals(0, new BigDecimal("190.00")
                .compareTo(ParcelasPrevistasService.somar(agosto)));
    }

    @Test
    @DisplayName("o CSV que traz a parcela tira ela da previsão — e ela nasce com dono")
    void batimentoTiraDaPrevisaoOQueOArquivoTrouxe() {
        assumirEmJulho("2;1;08/07;LOJA PARCELADA;01/10;190,00");

        PreviaService.Resultado r = subirPrevia(
                "2;1;08/08;LOJA PARCELADA;02/10;190,00", "2;1;09/08;PADARIA DO BAIRRO;;20,00");

        assertEquals(1, r.batimento().conferidas().size(), "a parcela esperada chegou");
        assertTrue(r.batimento().conferidas().get(0).jaVeio());
        assertTrue(r.batimento().ausentes().isEmpty());

        assertTrue(doMes(AGOSTO).isEmpty(),
                "ela virou lançamento: mantê-la também como previsão somaria a mesma "
                        + "parcela duas vezes no mês da pessoa");

        Lancamento parcela = buscar(r.fatura(), "LOJA PARCELADA");
        assertEquals(joao.id, parcela.responsavel.getId());
        assertEquals(OrigemAtribuicao.HERDADA_PARCELA, parcela.origemAtribuicao);
    }

    @Test
    @DisplayName("a parcela que não veio no arquivo continua prevista, e o admin é avisado")
    void parcelaQueNaoVeioContinuaPrevista() {
        assumirEmJulho("2;1;08/07;LOJA PARCELADA;01/10;190,00");

        PreviaService.Resultado r = subirPrevia("2;1;09/08;PADARIA DO BAIRRO;;20,00");

        assertTrue(r.batimento().conferidas().isEmpty());
        assertEquals(1, r.batimento().ausentes().size(),
                "some da tela sem deixar rastro é o que o admin não tem como perceber "
                        + "sozinho — pode ser mês sem cobrança ou descrição mudada no banco");
        assertEquals(1, doMes(AGOSTO).size(), "continua sendo o que o mês vai receber");
    }

    @Test
    @DisplayName("gruda até quitar: paga a última parcela, a previsão acaba")
    void compromissoQuitadoNaoPreveMais() {
        assumirEmJulho("2;1;08/07;LOJA PARCELADA;01/02;100,00");
        assertEquals(1, doMes(AGOSTO).size(), "falta uma parcela");

        // Agosto fecha trazendo a última: o compromisso se encerra sozinho.
        importacao.importar(csv("2;1;08/08;LOJA PARCELADA;02/02;100,00"), "agosto.csv",
                AGOSTO, new BigDecimal("100.00"), titular);

        assertFalse(compromisso().ativo, "a compra acabou de ser quitada");
        assertTrue(doMes(SETEMBRO).isEmpty(),
                "prever uma 3/2 faria o app cobrar parcela que não existe");
    }

    @Test
    @DisplayName("mês com fatura de verdade importada não tem previsão")
    void mesFechadoNaoPreve() {
        assumirEmJulho("2;1;08/07;LOJA PARCELADA;01/10;190,00");
        importacao.importar(csv("2;1;09/08;PADARIA DO BAIRRO;;20,00"), "agosto.csv",
                AGOSTO, new BigDecimal("20.00"), titular);

        assertTrue(doMes(AGOSTO).isEmpty(),
                "ali o arquivo é a verdade; parcela prometida que não chegou é assunto do "
                        + "mês seguinte, não uma linha a mais numa fatura que vai ser conciliada");
    }

    @Test
    @DisplayName("cada um só vê as próprias parcelas previstas")
    void previsaoRespeitaAPrivacidade() {
        Fatura julho = julhoFechaCom("2;1;08/07;LOJA PARCELADA;01/10;190,00",
                "2;1;09/07;OUTRA LOJA;01/06;60,00");
        assumir(julho, "LOJA PARCELADA", joao);
        assumir(julho, "OUTRA LOJA", maria);

        assertEquals(2, doMes(AGOSTO).size());
        assertEquals(1, doMes(AGOSTO, joao).size());
        assertEquals(joao.id, doMes(AGOSTO, joao).get(0).usuarioId());
        assertEquals(1, doMes(AGOSTO, maria).size(),
                "parcela prevista é conta que alguém assumiu — vale a privacidade do resto "
                        + "do app: cada um vê as suas e o pool, nada além");
    }

    @Test
    @DisplayName("parcela de quem foi desativado não é prevista no nome dele")
    void donoDesativadoNaoEntraNaPrevisao() {
        assumirEmJulho("2;1;08/07;LOJA PARCELADA;01/10;190,00");
        // Ele tem lançamento assumido, então "remover" desativa em vez de apagar.
        usuarios.remover(joao.id, titular);

        assertTrue(doMes(AGOSTO).isEmpty(),
                "quando o arquivo chegar a conta volta para o pool; anunciar a parcela no "
                        + "nome de quem não usa mais o app seria previsão que a importação desmente");
    }

    // ------------------------------------------------------------- apoio --

    /**
     * Julho fecha e o João assume a primeira parcela — é <b>isso</b> que cria o
     * compromisso, e é dele que toda previsão sai.
     */
    private void assumirEmJulho(String linha) {
        Fatura julho = julhoFechaCom(linha);
        assumir(julho, descricaoDe(linha), joao);
    }

    private Fatura julhoFechaCom(String... linhas) {
        BigDecimal total = BigDecimal.ZERO;
        for (String l : linhas) {
            total = total.add(valorDe(l));
        }
        return importacao.importar(csv(linhas), "julho.csv", JULHO, total, titular);
    }

    private void assumir(Fatura f, String descricao, Usuario dono) {
        reivindicacoes.reivindicar(buscar(f, descricao).id, dono, null);
    }

    private static String descricaoDe(String linha) {
        return linha.split(";")[3];
    }

    private static BigDecimal valorDe(String linha) {
        return new BigDecimal(linha.substring(linha.lastIndexOf(';') + 1).replace(',', '.'));
    }

    private PreviaService.Resultado subirPrevia(String... linhas) {
        return previas.subir(csv(linhas), "previa.csv", AGOSTO, titular);
    }

    private static byte[] csv(String... linhas) {
        StringBuilder sb = new StringBuilder(CABECALHO).append("\n");
        for (String l : linhas) {
            sb.append(l).append("\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    List<ParcelasPrevistasService.Prevista> doMes(LocalDate competencia) {
        Fatura.getEntityManager().clear();
        return previstas.doMesEmAberto(competencia);
    }

    @Transactional
    List<ParcelasPrevistasService.Prevista> doMes(LocalDate competencia, Usuario quem) {
        Fatura.getEntityManager().clear();
        return previstas.doMesEmAberto(competencia, quem.id);
    }

    @Transactional
    CompromissoParcelado compromisso() {
        CompromissoParcelado.getEntityManager().clear();
        List<CompromissoParcelado> todos = CompromissoParcelado.listAll();
        assertEquals(1, todos.size(), "esperava um único compromisso no cenário");
        return todos.get(0);
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
