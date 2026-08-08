package br.com.jcard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jcard.model.Acerto;
import br.com.jcard.model.Cartao;
import br.com.jcard.model.CompromissoParcelado;
import br.com.jcard.model.Fatura;
import br.com.jcard.model.Lancamento;
import br.com.jcard.model.OrigemAtribuicao;
import br.com.jcard.model.StatusFatura;
import br.com.jcard.model.TipoLancamento;
import br.com.jcard.model.Usuario;
import br.com.jcard.parser.TextoFatura;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * As duas regras que sustentam o app: <b>as contas sempre batem</b> e
 * <b>parcelamento gruda</b>. Se algum destes testes cair, alguém vai ser cobrado
 * errado.
 */
@QuarkusTest
class RegrasDeNegocioTest {

    @Inject
    AtribuicaoService atribuicao;

    @Inject
    ConciliacaoService conciliacao;

    @Inject
    ReivindicacaoService reivindicacoes;

    private Usuario titular;
    private Usuario joao;
    private Usuario maria;

    @BeforeEach
    @Transactional
    void limpar() {
        Acerto.deleteAll();
        br.com.jcard.model.Reivindicacao.deleteAll();
        CompromissoParcelado.deleteAll();
        Lancamento.deleteAll();
        Fatura.deleteAll();
        Cartao.deleteAll();
        Usuario.deleteAll();

        titular = novoUsuario("Jose Titular", "jose@teste.local", true);
        joao = novoUsuario("Joao Filho", "joao@teste.local", false);
        maria = novoUsuario("Maria Filha", "maria@teste.local", false);
    }

    // =================================================== as contas batem ===

    @Test
    @DisplayName("soma dos lançamentos == total impresso → fatura segue para avaliação")
    void somaBateLiberaAvaliacao() {
        Fatura f = novaFatura("2026-08", "340.00");
        criarLancamento(f, "SUPERMERCADO", "250.00", null, null);
        criarLancamento(f, "FARMACIA", "90.00", null, null);

        conciliacao.validarLeitura(f.id);

        Fatura salva = recarregar(f);
        assertEquals(StatusFatura.EM_AVALIACAO, salva.status);
        assertEquals(0, new BigDecimal("340.00").compareTo(salva.valorLancado));
        assertEquals(0, BigDecimal.ZERO.compareTo(salva.divergencia()));
    }

    @Test
    @DisplayName("um centavo de diferença trava a fatura como DIVERGENTE")
    void centavoFaltandoTrava() {
        Fatura f = novaFatura("2026-08", "340.00");
        criarLancamento(f, "SUPERMERCADO", "250.00", null, null);
        criarLancamento(f, "FARMACIA", "89.99", null, null);

        conciliacao.validarLeitura(f.id);

        Fatura salva = recarregar(f);
        assertEquals(StatusFatura.DIVERGENTE, salva.status,
                "ler a fatura errado não pode virar rateio: tem que travar");
        assertEquals(0, new BigDecimal("0.01").compareTo(salva.divergencia()));
    }

    @Test
    @DisplayName("fatura divergente não pode ser conciliada")
    void divergenteNaoConcilia() {
        Fatura f = novaFatura("2026-08", "100.00");
        criarLancamento(f, "LOJA", "99.00", null, null);
        conciliacao.validarLeitura(f.id);

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> conciliacao.conciliar(f.id, titular));
        assertEquals(409, e.getResponse().getStatus());
    }

    @Test
    @DisplayName("na conciliação, a sobra sem dono vai para o titular e os acertos fecham o total")
    void sobraVaiParaOTitular() {
        Fatura f = novaFatura("2026-08", "340.00");
        Lancamento mercado = criarLancamento(f, "SUPERMERCADO", "250.00", null, null);
        criarLancamento(f, "FARMACIA", "90.00", null, null);
        conciliacao.validarLeitura(f.id);

        reivindicacoes.reivindicar(mercado.id, joao, null);
        conciliacao.conciliar(f.id, titular);

        List<Acerto> acertos = Acerto.daFatura(f.id);
        BigDecimal soma = acertos.stream().map(a -> a.valorDevido)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(0, new BigDecimal("340.00").compareTo(soma),
                "a soma dos acertos precisa reproduzir o total da fatura");
        assertEquals(0, new BigDecimal("250.00").compareTo(acerto(f, joao).valorDevido));
        assertEquals(0, new BigDecimal("90.00").compareTo(acerto(f, titular).valorDevido),
                "os R$ 90 que ninguém assumiu são do titular");
    }

    @Test
    @DisplayName("estorno negativo entra na conta e o total continua fechando")
    void estornoAbateDaConta() {
        Fatura f = novaFatura("2026-08", "200.00");
        Lancamento compra = criarLancamento(f, "LOJA ROUPA", "250.00", null, null);
        Lancamento estorno = criarLancamento(f, "ESTORNO LOJA ROUPA", "-50.00", null, null,
                TipoLancamento.ESTORNO);
        conciliacao.validarLeitura(f.id);
        assertEquals(StatusFatura.EM_AVALIACAO, recarregar(f).status);

        reivindicacoes.reivindicar(compra.id, joao, null);
        reivindicacoes.reivindicar(estorno.id, joao, null);
        conciliacao.conciliar(f.id, titular);

        assertEquals(0, new BigDecimal("200.00").compareTo(acerto(f, joao).valorDevido),
                "quem devolveu a compra paga o líquido");
    }

    // ================================================ parcelamento gruda ===

    @Test
    @DisplayName("assumiu a 1/10 → a 2/10 da fatura seguinte já nasce dele")
    void parcelaSeguinteHerdaDono() {
        Fatura julho = novaFatura("2026-07", "89.90");
        Lancamento p1 = criarLancamento(julho, "POSTO SHELL CENTRO", "89.90", 1, 10);
        conciliacao.validarLeitura(julho.id);

        reivindicacoes.reivindicar(p1.id, joao, null);

        CompromissoParcelado c = CompromissoParcelado.ativoPorChave(chaveDe(p1));
        assertNotNull(c, "assumir uma parcela precisa criar o compromisso");
        assertEquals(joao.id, c.usuario.id);

        // Mês seguinte: mesma compra, parcela 2/10.
        Fatura agosto = novaFatura("2026-08", "89.90");
        Lancamento p2 = criarLancamento(agosto, "POSTO SHELL CENTRO", "89.90", 2, 10);

        OrigemAtribuicao origem = aplicarAtribuicao(p2);

        assertEquals(OrigemAtribuicao.HERDADA_PARCELA, origem);
        assertEquals(joao.id, responsavelDe(p2),
                "a 2/10 tinha que cair no João sem ele reivindicar de novo");
    }

    @Test
    @DisplayName("o compromisso se encerra sozinho na última parcela")
    void compromissoEncerraNoFim() {
        Fatura f1 = novaFatura("2026-07", "50.00");
        Lancamento p1 = criarLancamento(f1, "CURSO ONLINE", "50.00", 1, 2);
        conciliacao.validarLeitura(f1.id);
        reivindicacoes.reivindicar(p1.id, maria, null);

        Fatura f2 = novaFatura("2026-08", "50.00");
        Lancamento p2 = criarLancamento(f2, "CURSO ONLINE", "50.00", 2, 2);
        aplicarAtribuicao(p2);

        String chave = chaveDe(p1);
        assertNull(CompromissoParcelado.ativoPorChave(chave),
                "depois da última parcela o compromisso não pode seguir capturando compras");
    }

    @Test
    @DisplayName("parcela com valor muito diferente fica no pool em vez de atribuir errado")
    void valorDestoanteNaoAtribui() {
        Fatura f1 = novaFatura("2026-07", "100.00");
        Lancamento p1 = criarLancamento(f1, "LOJA XYZ", "100.00", 1, 5);
        conciliacao.validarLeitura(f1.id);
        reivindicacoes.reivindicar(p1.id, joao, null);

        // Outra compra na mesma loja, mesmo nº de parcelas, valor bem diferente.
        Fatura f2 = novaFatura("2026-08", "700.00");
        Lancamento outra = criarLancamento(f2, "LOJA XYZ", "700.00", 2, 5);

        assertNull(aplicarAtribuicao(outra),
                "na dúvida o lançamento fica no pool — cobrar errado é pior");
        assertNull(responsavelDe(outra));
    }

    @Test
    @DisplayName("diferença de centavos do parcelamento ainda casa")
    void arredondamentoDeParcelaAindaCasa() {
        Fatura f1 = novaFatura("2026-07", "33.34");
        Lancamento p1 = criarLancamento(f1, "ELETRO SA", "33.34", 1, 3);
        conciliacao.validarLeitura(f1.id);
        reivindicacoes.reivindicar(p1.id, maria, null);

        Fatura f2 = novaFatura("2026-08", "33.33");
        Lancamento p2 = criarLancamento(f2, "ELETRO SA", "33.33", 2, 3);

        assertEquals(OrigemAtribuicao.HERDADA_PARCELA, aplicarAtribuicao(p2),
                "R$ 100 em 3x sai 33,34 + 33,33 + 33,33 — não pode quebrar o casamento");
    }

    // ========================================================= conflito ===

    @Test
    @DisplayName("um pretendente leva na hora; o segundo devolve o lançamento ao pool")
    void segundaReivindicacaoViraConflito() {
        Fatura f = novaFatura("2026-08", "120.00");
        Lancamento l = criarLancamento(f, "RESTAURANTE", "120.00", null, null);
        conciliacao.validarLeitura(f.id);

        reivindicacoes.reivindicar(l.id, joao, null);
        assertEquals(joao.id, responsavelDe(l));

        reivindicacoes.reivindicar(l.id, maria, "também jantei lá");

        assertNull(responsavelDe(l),
                "com dois pretendentes ninguém fica com o lançamento até o admin decidir");
        assertEquals(1, reivindicacoes.conflitos(f.id).size());
    }

    @Test
    @DisplayName("admin arbitra e o vencedor fica com o lançamento")
    void adminArbitra() {
        Fatura f = novaFatura("2026-08", "120.00");
        Lancamento l = criarLancamento(f, "RESTAURANTE", "120.00", null, null);
        conciliacao.validarLeitura(f.id);
        reivindicacoes.reivindicar(l.id, joao, null);
        reivindicacoes.reivindicar(l.id, maria, null);

        reivindicacoes.arbitrar(l.id, maria.id, titular);

        assertEquals(maria.id, responsavelDe(l));
        assertEquals(OrigemAtribuicao.ADMIN, origemDe(l));
        assertTrue(reivindicacoes.conflitos(f.id).isEmpty());
    }

    @Test
    @DisplayName("desistir devolve ao pool e libera o pretendente que sobrou")
    void desistirLiberaOOutro() {
        Fatura f = novaFatura("2026-08", "120.00");
        Lancamento l = criarLancamento(f, "RESTAURANTE", "120.00", null, null);
        conciliacao.validarLeitura(f.id);
        reivindicacoes.reivindicar(l.id, joao, null);
        reivindicacoes.reivindicar(l.id, maria, null);

        reivindicacoes.desistir(l.id, joao);

        assertEquals(maria.id, responsavelDe(l),
                "sobrando um só pretendente, ele leva sem precisar do admin");
    }

    @Test
    @DisplayName("encargo do cartão não é reivindicável")
    void encargoNaoEReivindicavel() {
        Fatura f = novaFatura("2026-08", "30.00");
        Lancamento anuidade = criarLancamento(f, "ANUIDADE", "30.00", null, null,
                TipoLancamento.ANUIDADE);
        conciliacao.validarLeitura(f.id);

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> reivindicacoes.reivindicar(anuidade.id, joao, null));
        assertEquals(409, e.getResponse().getStatus());
    }

    // ============================================================ apoio ===

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

    @Transactional
    Fatura novaFatura(String competencia, String total) {
        Fatura f = new Fatura();
        f.competencia = LocalDate.parse(competencia + "-01");
        f.valorTotal = new BigDecimal(total);
        f.hashPdf = String.format("%064d", Math.abs(competencia.hashCode()) + total.hashCode());
        f.emissor = "ITAU";
        f.persist();
        return f;
    }

    Lancamento criarLancamento(Fatura f, String descricao, String valor,
                               Integer parcelaAtual, Integer parcelaTotal) {
        return criarLancamento(f, descricao, valor, parcelaAtual, parcelaTotal,
                TipoLancamento.COMPRA);
    }

    @Transactional
    Lancamento criarLancamento(Fatura f, String descricao, String valor,
                               Integer parcelaAtual, Integer parcelaTotal,
                               TipoLancamento tipo) {
        Lancamento l = new Lancamento();
        l.fatura = Fatura.findById(f.id);
        l.dataCompra = f.competencia.minusDays(10);
        l.descricao = descricao;
        l.descricaoNormalizada = TextoFatura.normalizar(descricao);
        l.valor = new BigDecimal(valor);
        l.parcelaAtual = parcelaAtual;
        l.parcelaTotal = parcelaTotal;
        l.final4 = "1234";
        l.tipo = tipo;
        l.persist();
        return l;
    }

    @Transactional
    OrigemAtribuicao aplicarAtribuicao(Lancamento l) {
        Lancamento gerenciado = Lancamento.findById(l.id);
        OrigemAtribuicao origem = atribuicao.aplicar(gerenciado);
        gerenciado.persist();
        return origem;
    }

    /**
     * Lê do banco de verdade. Sem o {@code clear()}, o cache de 1º nível devolve a
     * instância antiga e o teste passaria (ou falharia) por engano.
     */
    @Transactional
    Lancamento recarregar(Lancamento l) {
        Lancamento.getEntityManager().clear();
        return Lancamento.findById(l.id);
    }

    @Transactional
    Fatura recarregar(Fatura f) {
        Fatura.getEntityManager().clear();
        return Fatura.findById(f.id);
    }

    /** Id do responsável persistido, ou null se o lançamento está no pool. */
    @Transactional
    Long responsavelDe(Lancamento l) {
        Lancamento.getEntityManager().clear();
        Lancamento fresco = Lancamento.findById(l.id);
        return fresco.responsavel == null ? null : fresco.responsavel.getId();
    }

    @Transactional
    OrigemAtribuicao origemDe(Lancamento l) {
        Lancamento.getEntityManager().clear();
        return ((Lancamento) Lancamento.findById(l.id)).origemAtribuicao;
    }

    @Transactional
    String chaveDe(Lancamento l) {
        Lancamento.getEntityManager().clear();
        return ((Lancamento) Lancamento.findById(l.id)).chaveParcelamento;
    }

    Acerto acerto(Fatura f, Usuario u) {
        Acerto a = Acerto.de(f.id, u.id);
        assertNotNull(a, "esperava acerto de " + u.nome);
        return a;
    }
}
