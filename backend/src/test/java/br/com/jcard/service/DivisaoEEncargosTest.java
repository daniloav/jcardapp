package br.com.jcard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jcard.model.Acerto;
import br.com.jcard.model.Cartao;
import br.com.jcard.model.ComprovantePagamento;
import br.com.jcard.model.CompromissoParcelado;
import br.com.jcard.model.DivisaoLancamento;
import br.com.jcard.model.Fatura;
import br.com.jcard.model.Lancamento;
import br.com.jcard.model.Reivindicacao;
import br.com.jcard.model.StatusAcerto;
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
 * Conta rachada, encargo rateado e o ciclo de pagamento.
 *
 * <p>Todos aqui defendem a mesma coisa da suíte principal: <b>as contas sempre
 * batem</b>. Dividir e ratear são exatamente os pontos onde um arredondamento
 * solto faria a soma dos acertos deixar de reproduzir o total da fatura — e aí
 * alguém pagaria a mais ou a menos sem ninguém perceber.
 */
@QuarkusTest
class DivisaoEEncargosTest {

    @Inject
    ConciliacaoService conciliacao;

    @Inject
    ReivindicacaoService reivindicacoes;

    @Inject
    DivisaoService divisoes;

    @Inject
    RateioService rateio;

    @Inject
    AcertoService acertos;

    @Inject
    FaturaImportService importacao;

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
        Lancamento.deleteAll();
        Fatura.deleteAll();
        Cartao.deleteAll();
        Usuario.deleteAll();

        titular = novoUsuario("Jose Titular", "jose@teste.local", true);
        joao = novoUsuario("Joao Filho", "joao@teste.local", false);
        maria = novoUsuario("Maria Filha", "maria@teste.local", false);
    }

    // ==================================================== conta dividida ===

    @Test
    @DisplayName("conta rachada entre três: cada um deve a sua parte e a soma fecha o total")
    void contaDivididaEntreTres() {
        Fatura f = novaFatura("2026-08", "120.00");
        Lancamento jantar = criarLancamento(f, "RESTAURANTE", "120.00", TipoLancamento.COMPRA);
        conciliacao.validarLeitura(f.id);
        reivindicacoes.reivindicar(jantar.id, joao, null);

        divisoes.dividir(jantar.id, joao, false, List.of(
                new DivisaoService.Parte(joao.id, new BigDecimal("60.00")),
                new DivisaoService.Parte(maria.id, new BigDecimal("40.00")),
                new DivisaoService.Parte(titular.id, new BigDecimal("20.00"))));

        conciliacao.conciliar(f.id, titular);

        assertEquals(0, new BigDecimal("60.00").compareTo(acerto(f, joao).valorDevido));
        assertEquals(0, new BigDecimal("40.00").compareTo(acerto(f, maria).valorDevido));
        assertEquals(0, new BigDecimal("20.00").compareTo(acerto(f, titular).valorDevido));
        assertEquals(0, new BigDecimal("120.00").compareTo(somaDosAcertos(f)),
                "a soma dos acertos tem de reproduzir o total da fatura");
    }

    @Test
    @DisplayName("divisão que não fecha com o valor do lançamento é recusada")
    void divisaoQueNaoFechaERecusada() {
        Fatura f = novaFatura("2026-08", "120.00");
        Lancamento jantar = criarLancamento(f, "RESTAURANTE", "120.00", TipoLancamento.COMPRA);
        conciliacao.validarLeitura(f.id);
        reivindicacoes.reivindicar(jantar.id, joao, null);

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> divisoes.dividir(jantar.id, joao, false, List.of(
                        new DivisaoService.Parte(joao.id, new BigDecimal("60.00")),
                        new DivisaoService.Parte(maria.id, new BigDecimal("50.00")))));

        assertEquals(422, e.getResponse().getStatus(),
                "aceitar uma divisão que não soma criaria dinheiro que a fatura não tem");
        assertTrue(DivisaoLancamento.doLancamento(jantar.id).isEmpty(),
                "nada pode ter sido gravado");
    }

    @Test
    @DisplayName("dividir exige pelo menos duas pessoas")
    void divisaoDeUmaPessoaSoERecusada() {
        Fatura f = novaFatura("2026-08", "120.00");
        Lancamento jantar = criarLancamento(f, "RESTAURANTE", "120.00", TipoLancamento.COMPRA);
        conciliacao.validarLeitura(f.id);
        reivindicacoes.reivindicar(jantar.id, joao, null);

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> divisoes.dividir(jantar.id, joao, false, List.of(
                        new DivisaoService.Parte(maria.id, new BigDecimal("120.00")))));
        assertEquals(400, e.getResponse().getStatus());
    }

    @Test
    @DisplayName("quem não assumiu o lançamento não pode dividi-lo")
    void soQuemAssumiuDivide() {
        Fatura f = novaFatura("2026-08", "120.00");
        Lancamento jantar = criarLancamento(f, "RESTAURANTE", "120.00", TipoLancamento.COMPRA);
        conciliacao.validarLeitura(f.id);
        reivindicacoes.reivindicar(jantar.id, joao, null);

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> divisoes.dividir(jantar.id, maria, false, List.of(
                        new DivisaoService.Parte(maria.id, new BigDecimal("60.00")),
                        new DivisaoService.Parte(joao.id, new BigDecimal("60.00")))));
        assertEquals(403, e.getResponse().getStatus());
    }

    @Test
    @DisplayName("encargo não se divide à mão: ele já é de todo mundo")
    void encargoNaoSeDivide() {
        Fatura f = novaFatura("2026-08", "30.00");
        Lancamento anuidade = criarLancamento(f, "ANUIDADE", "30.00", TipoLancamento.ANUIDADE);
        conciliacao.validarLeitura(f.id);

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> divisoes.dividir(anuidade.id, titular, true, List.of(
                        new DivisaoService.Parte(joao.id, new BigDecimal("15.00")),
                        new DivisaoService.Parte(maria.id, new BigDecimal("15.00")))));
        assertEquals(409, e.getResponse().getStatus());
    }

    @Test
    @DisplayName("recusar a parte derruba a divisão e a conta volta inteira para quem assumiu")
    void recusarParteDesfazADivisao() {
        Fatura f = novaFatura("2026-08", "120.00");
        Lancamento jantar = criarLancamento(f, "RESTAURANTE", "120.00", TipoLancamento.COMPRA);
        conciliacao.validarLeitura(f.id);
        reivindicacoes.reivindicar(jantar.id, joao, null);
        divisoes.dividir(jantar.id, joao, false, List.of(
                new DivisaoService.Parte(joao.id, new BigDecimal("60.00")),
                new DivisaoService.Parte(maria.id, new BigDecimal("60.00"))));

        reivindicacoes.desistir(jantar.id, maria);

        assertTrue(DivisaoLancamento.doLancamento(jantar.id).isEmpty());
        assertEquals(joao.id, responsavelDe(jantar),
                "quem assumiu não perde o lançamento porque outra pessoa recusou a parte");
        conciliacao.conciliar(f.id, titular);
        assertEquals(0, new BigDecimal("120.00").compareTo(acerto(f, joao).valorDevido));
        assertNull(Acerto.de(f.id, maria.id), "Maria não deve mais nada nesta fatura");
    }

    @Test
    @DisplayName("desfazer a divisão devolve a conta inteira ao responsável")
    void juntarDesfazADivisao() {
        Fatura f = novaFatura("2026-08", "120.00");
        Lancamento jantar = criarLancamento(f, "RESTAURANTE", "120.00", TipoLancamento.COMPRA);
        conciliacao.validarLeitura(f.id);
        reivindicacoes.reivindicar(jantar.id, joao, null);
        divisoes.dividir(jantar.id, joao, false, List.of(
                new DivisaoService.Parte(joao.id, new BigDecimal("60.00")),
                new DivisaoService.Parte(maria.id, new BigDecimal("60.00"))));

        divisoes.juntar(jantar.id, joao, false);

        assertTrue(DivisaoLancamento.doLancamento(jantar.id).isEmpty());
        conciliacao.conciliar(f.id, titular);
        assertEquals(0, new BigDecimal("120.00").compareTo(acerto(f, joao).valorDevido));
    }

    // =================================================== encargo rateado ===

    @Test
    @DisplayName("encargo é dividido entre todos que usaram o cartão, e não fica com o titular")
    void encargoERateadoEntreQuemUsou() {
        Fatura f = novaFatura("2026-08", "330.00");
        Lancamento mercado = criarLancamento(f, "SUPERMERCADO", "200.00", TipoLancamento.COMPRA);
        Lancamento farmacia = criarLancamento(f, "FARMACIA", "100.00", TipoLancamento.COMPRA);
        criarLancamento(f, "ANUIDADE", "30.00", TipoLancamento.ANUIDADE);
        conciliacao.validarLeitura(f.id);

        reivindicacoes.reivindicar(mercado.id, joao, null);
        reivindicacoes.reivindicar(farmacia.id, maria, null);
        conciliacao.conciliar(f.id, titular);

        assertEquals(0, new BigDecimal("215.00").compareTo(acerto(f, joao).valorDevido),
                "200 da compra + 15 de anuidade");
        assertEquals(0, new BigDecimal("115.00").compareTo(acerto(f, maria).valorDevido),
                "100 da compra + 15 de anuidade");
        assertNull(Acerto.de(f.id, titular.id),
                "o titular não usou o cartão neste mês: a anuidade não é dele");
        assertEquals(0, new BigDecimal("330.00").compareTo(somaDosAcertos(f)));
    }

    @Test
    @DisplayName("o encargo continua sem responsável depois da conciliação — é o que mantém o rateio")
    void encargoNaoVaiParaOTitularNaConciliacao() {
        Fatura f = novaFatura("2026-08", "230.00");
        Lancamento mercado = criarLancamento(f, "SUPERMERCADO", "200.00", TipoLancamento.COMPRA);
        Lancamento iof = criarLancamento(f, "IOF COMPRA EXTERIOR", "30.00", TipoLancamento.IOF);
        conciliacao.validarLeitura(f.id);
        reivindicacoes.reivindicar(mercado.id, joao, null);

        conciliacao.conciliar(f.id, titular);

        assertNull(responsavelDe(iof),
                "se o encargo ganhasse dono na conciliação, o rateio deixaria de valer");
    }

    @Test
    @DisplayName("encargo que não divide exato: a sobra de centavos fica com o titular")
    void sobraDeCentavosVaiParaOTitular() {
        Fatura f = novaFatura("2026-08", "310.00");
        Lancamento a = criarLancamento(f, "COMPRA A", "100.00", TipoLancamento.COMPRA);
        Lancamento b = criarLancamento(f, "COMPRA B", "100.00", TipoLancamento.COMPRA);
        Lancamento c = criarLancamento(f, "COMPRA C", "100.00", TipoLancamento.COMPRA);
        criarLancamento(f, "JUROS ROTATIVO", "10.00", TipoLancamento.ENCARGO);
        conciliacao.validarLeitura(f.id);

        reivindicacoes.reivindicar(a.id, titular, null);
        reivindicacoes.reivindicar(b.id, joao, null);
        reivindicacoes.reivindicar(c.id, maria, null);
        conciliacao.conciliar(f.id, titular);

        // R$ 10,00 entre três = 3,34 + 3,33 + 3,33. Perder o centavo faria a
        // fatura não fechar; dá-lo a quem o app achasse primeiro seria arbitrário.
        assertEquals(0, new BigDecimal("103.34").compareTo(acerto(f, titular).valorDevido));
        assertEquals(0, new BigDecimal("103.33").compareTo(acerto(f, joao).valorDevido));
        assertEquals(0, new BigDecimal("103.33").compareTo(acerto(f, maria).valorDevido));
        assertEquals(0, new BigDecimal("310.00").compareTo(somaDosAcertos(f)));
    }

    @Test
    @DisplayName("participar só de uma conta dividida já conta como ter usado o cartão")
    void parteDeContaDivididaContaComoUso() {
        Fatura f = novaFatura("2026-08", "130.00");
        Lancamento jantar = criarLancamento(f, "RESTAURANTE", "120.00", TipoLancamento.COMPRA);
        criarLancamento(f, "IOF", "10.00", TipoLancamento.IOF);
        conciliacao.validarLeitura(f.id);
        reivindicacoes.reivindicar(jantar.id, joao, null);
        divisoes.dividir(jantar.id, joao, false, List.of(
                new DivisaoService.Parte(joao.id, new BigDecimal("60.00")),
                new DivisaoService.Parte(maria.id, new BigDecimal("60.00"))));

        conciliacao.conciliar(f.id, titular);

        assertEquals(0, new BigDecimal("65.00").compareTo(acerto(f, joao).valorDevido));
        assertEquals(0, new BigDecimal("65.00").compareTo(acerto(f, maria).valorDevido),
                "Maria entrou só pela parte do jantar, mas usou o cartão — paga metade do IOF");
        assertEquals(0, new BigDecimal("130.00").compareTo(somaDosAcertos(f)));
    }

    @Test
    @DisplayName("sem ninguém assumindo nada, o titular fica com tudo, encargo incluído")
    void ninguemAssumiuTitularLevaTudo() {
        Fatura f = novaFatura("2026-08", "230.00");
        criarLancamento(f, "SUPERMERCADO", "200.00", TipoLancamento.COMPRA);
        criarLancamento(f, "ANUIDADE", "30.00", TipoLancamento.ANUIDADE);
        conciliacao.validarLeitura(f.id);

        conciliacao.conciliar(f.id, titular);

        assertEquals(0, new BigDecimal("230.00").compareTo(acerto(f, titular).valorDevido));
        assertEquals(0, new BigDecimal("230.00").compareTo(somaDosAcertos(f)));
    }

    @Test
    @DisplayName("o pagamento da fatura anterior não é rateado: é do titular")
    void pagamentoAnteriorFicaComOTitular() {
        Fatura f = novaFatura("2026-08", "100.00");
        Lancamento compra = criarLancamento(f, "SUPERMERCADO", "300.00", TipoLancamento.COMPRA);
        criarLancamento(f, "PAGAMENTO EFETUADO", "-200.00", TipoLancamento.PAGAMENTO);
        conciliacao.validarLeitura(f.id);
        reivindicacoes.reivindicar(compra.id, joao, null);

        conciliacao.conciliar(f.id, titular);

        assertEquals(0, new BigDecimal("300.00").compareTo(acerto(f, joao).valorDevido));
        assertEquals(0, new BigDecimal("-200.00").compareTo(acerto(f, titular).valorDevido),
                "quitar a fatura anterior é ato do titular, não gasto de ninguém");
        assertEquals(0, new BigDecimal("100.00").compareTo(somaDosAcertos(f)));
    }

    @Test
    @DisplayName("a divisão em partes nunca perde nem inventa centavo")
    void divisaoEmPartesFechaSempre() {
        for (String valor : List.of("10.00", "0.01", "100.00", "-10.00", "33.33", "0.02")) {
            for (int n = 1; n <= 7; n++) {
                BigDecimal total = new BigDecimal(valor);
                BigDecimal soma = rateio.dividir(total, n).stream()
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                assertEquals(0, total.compareTo(soma),
                        "R$ " + valor + " em " + n + " parte(s) somou " + soma);
            }
        }
    }

    // ================================================ aceite e pagamento ===

    @Test
    @DisplayName("não dá para aceitar o valor enquanto a fatura está em avaliação")
    void aceiteExigeFaturaConciliada() {
        Fatura f = novaFatura("2026-08", "100.00");
        Lancamento l = criarLancamento(f, "SUPERMERCADO", "100.00", TipoLancamento.COMPRA);
        conciliacao.validarLeitura(f.id);
        reivindicacoes.reivindicar(l.id, joao, null);

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> acertos.aceitar(f.id, joao));
        assertEquals(409, e.getResponse().getStatus(),
                "aceitar um valor que ainda muda não significa nada");
    }

    @Test
    @DisplayName("pagar sem ter aceitado o valor é recusado")
    void pagamentoExigeAceite() {
        Fatura f = faturaConciliadaComJoao();

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> acertos.informarPagamento(f.id, joao, LocalDate.now(), null,
                        new byte[] { 1, 2, 3 }, "pix.png", "image/png"));
        assertEquals(409, e.getResponse().getStatus());
    }

    @Test
    @DisplayName("o comprovante é obrigatório para declarar o pagamento")
    void comprovanteEObrigatorio() {
        Fatura f = faturaConciliadaComJoao();
        acertos.aceitar(f.id, joao);

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> acertos.informarPagamento(f.id, joao, LocalDate.now(), null,
                        new byte[0], "vazio.png", "image/png"));
        assertEquals(400, e.getResponse().getStatus());
        assertEquals(StatusAcerto.ACEITO, acerto(f, joao).status,
                "sem comprovante o acerto não avança");
    }

    @Test
    @DisplayName("arquivo que não é imagem nem PDF é recusado")
    void tipoDeComprovanteValidado() {
        Fatura f = faturaConciliadaComJoao();
        acertos.aceitar(f.id, joao);

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> acertos.informarPagamento(f.id, joao, LocalDate.now(), null,
                        new byte[] { 1 }, "planilha.xlsx", "application/vnd.ms-excel"));
        assertEquals(415, e.getResponse().getStatus());
    }

    @Test
    @DisplayName("ciclo completo: aceite, pagamento com comprovante e confirmação do admin")
    void cicloDePagamento() {
        Fatura f = faturaConciliadaComJoao();

        acertos.aceitar(f.id, joao);
        assertEquals(StatusAcerto.ACEITO, acerto(f, joao).status);

        acertos.informarPagamento(f.id, joao, LocalDate.parse("2026-08-20"), "pix feito",
                new byte[] { 8, 9, 10 }, "comprovante.png", "image/png");

        Acerto a = acerto(f, joao);
        assertEquals(StatusAcerto.INFORMADO, a.status);
        assertEquals(LocalDate.parse("2026-08-20"), a.pagoEm);
        assertNotNull(ComprovantePagamento.doAcerto(a.id));

        acertos.confirmarPagamento(a.id, titular);
        assertEquals(StatusAcerto.CONFIRMADO, acerto(f, joao).status);
    }

    @Test
    @DisplayName("o comprovante de um não é visível para outro utilizador")
    void comprovanteEPrivado() {
        Fatura f = faturaConciliadaComJoao();
        acertos.aceitar(f.id, joao);
        acertos.informarPagamento(f.id, joao, LocalDate.now(), null,
                new byte[] { 1 }, "pix.png", "image/png");
        Long acertoId = acerto(f, joao).id;

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> acertos.comprovante(acertoId, maria, false));
        assertEquals(403, e.getResponse().getStatus());
        assertNotNull(acertos.comprovante(acertoId, titular, true),
                "o admin precisa ver para poder confirmar");
    }

    // ==================================================== excluir fatura ===

    @Test
    @DisplayName("excluir a fatura leva junto lançamentos, acertos e o compromisso parcelado")
    void excluirFaturaLimpaTudo() {
        Fatura f = novaFatura("2026-08", "100.00");
        Lancamento parcela = criarLancamento(f, "CURSO ONLINE", "100.00", 1, 5);
        conciliacao.validarLeitura(f.id);
        reivindicacoes.reivindicar(parcela.id, joao, null);
        assertFalse(CompromissoParcelado.listAll().isEmpty());

        importacao.excluir(f.id, titular);

        assertNull(Fatura.findById(f.id));
        assertEquals(0, Lancamento.count("fatura.id", f.id));
        assertEquals(0, Acerto.count("fatura.id", f.id));
        assertTrue(CompromissoParcelado.listAll().isEmpty(),
                "um compromisso órfão seguiria atribuindo parcelas a partir de uma fatura "
                + "que não existe mais");
    }

    @Test
    @DisplayName("fatura fechada não pode ser excluída — ela é o histórico do acerto")
    void faturaFechadaNaoEExcluida() {
        Fatura f = faturaConciliadaComJoao();
        acertos.aceitar(f.id, joao);
        acertos.informarPagamento(f.id, joao, LocalDate.now(), null,
                new byte[] { 1 }, "pix.png", "image/png");
        acertos.confirmarPagamento(acerto(f, joao).id, titular);
        conciliacao.fechar(f.id, titular);

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> importacao.excluir(f.id, titular));
        assertEquals(409, e.getResponse().getStatus());
        assertNotNull(Fatura.findById(f.id));
    }

    // ============================================================ apoio ===

    /** Uma fatura conciliada em que o João deve o total — base dos testes de pagamento. */
    private Fatura faturaConciliadaComJoao() {
        Fatura f = novaFatura("2026-08", "100.00");
        Lancamento l = criarLancamento(f, "SUPERMERCADO", "100.00", TipoLancamento.COMPRA);
        conciliacao.validarLeitura(f.id);
        reivindicacoes.reivindicar(l.id, joao, null);
        conciliacao.conciliar(f.id, titular);
        assertEquals(StatusFatura.CONCILIADA, recarregar(f).status);
        return f;
    }

    private BigDecimal somaDosAcertos(Fatura f) {
        return Acerto.<Acerto>list("fatura.id", f.id).stream()
                .map(a -> a.valorDevido)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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

    @Transactional
    Fatura novaFatura(String competencia, String total) {
        Fatura f = new Fatura();
        f.competencia = LocalDate.parse(competencia + "-01");
        f.valorTotal = new BigDecimal(total);
        f.hashPdf = String.format("%064d", Math.abs(System.nanoTime() % 1_000_000_000L));
        f.emissor = "ITAU";
        f.persist();
        return f;
    }

    Lancamento criarLancamento(Fatura f, String descricao, String valor, TipoLancamento tipo) {
        return criarLancamento(f, descricao, valor, null, null, tipo);
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
        l.dataCompra = f.competencia.plusDays(5);
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

    /** Lê do banco de verdade: sem o clear o cache de 1º nível esconderia a mudança. */
    @Transactional
    Fatura recarregar(Fatura f) {
        Fatura.getEntityManager().clear();
        return Fatura.findById(f.id);
    }

    @Transactional
    Long responsavelDe(Lancamento l) {
        Lancamento.getEntityManager().clear();
        Lancamento fresco = Lancamento.findById(l.id);
        return fresco.responsavel == null ? null : fresco.responsavel.getId();
    }

    @Transactional
    Acerto acerto(Fatura f, Usuario u) {
        Acerto.getEntityManager().clear();
        Acerto a = Acerto.de(f.id, u.id);
        assertNotNull(a, "esperava acerto de " + u.nome);
        return a;
    }
}
