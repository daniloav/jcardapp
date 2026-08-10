package br.com.jcard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O fluxo de indicação: desfazer o engano, o admin apontar o dono, apelidar a
 * loja e reconhecer onde a pessoa já comprou antes.
 *
 * <p>O fio comum é o erro mais fácil de cometer no app — marcar "foi minha" no
 * lançamento errado, numa lista de 514 linhas, no celular. Os testes daqui
 * defendem que <b>sempre existe uma saída</b>, e que nenhuma delas cobra alguém
 * em silêncio: reabrir a avaliação anula os aceites e avisa, e a atribuição do
 * admin continua contestável enquanto a fatura estiver aberta.
 */
@QuarkusTest
class FluxoDeIndicacaoTest {

    @Inject
    ConciliacaoService conciliacao;

    @Inject
    ReivindicacaoService reivindicacoes;

    @Inject
    AcertoService acertos;

    @Inject
    ApelidoService apelidos;

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

    // ============================================== reabrir a avaliação ===

    @Test
    @DisplayName("reabrir devolve ao pool o que ficou com o titular por falta de dono")
    void reabrirDevolveAsSobrasAoPool() {
        Fatura f = novaFatura("2026-08", "300.00");
        Lancamento mercado = criar(f, "SUPERMERCADO", "200.00");
        Lancamento ninguem = criar(f, "LOJA DESCONHECIDA", "100.00");
        conciliacao.validarLeitura(f.id);
        reivindicacoes.reivindicar(mercado.id, joao, null);
        conciliacao.conciliar(f.id, titular);
        assertEquals(titular.id, responsavelDe(ninguem), "a sobra foi para o titular");

        conciliacao.reabrirAvaliacao(f.id, titular, "o João marcou a compra errada");

        assertEquals(StatusFatura.EM_AVALIACAO, recarregar(f).status);
        assertNull(responsavelDe(ninguem), "o que ninguém assumiu volta para o pool");
        assertEquals(joao.id, responsavelDe(mercado),
                "o que alguém assumiu de propósito continua com quem assumiu");
    }

    @Test
    @DisplayName("reabrir não desfaz o que o admin arbitrou de propósito")
    void reabrirPreservaAArbitragem() {
        Fatura f = novaFatura("2026-08", "100.00");
        Lancamento l = criar(f, "RESTAURANTE", "100.00");
        conciliacao.validarLeitura(f.id);
        reivindicacoes.arbitrar(l.id, maria.id, titular);
        conciliacao.conciliar(f.id, titular);

        conciliacao.reabrirAvaliacao(f.id, titular, null);

        assertEquals(maria.id, responsavelDe(l),
                "devolver ao pool uma decisão do admin apagaria o que ele decidiu");
        assertEquals(OrigemAtribuicao.ADMIN, origemDe(l));
    }

    @Test
    @DisplayName("reabrir anula o aceite: o valor volta a mudar, então concordar com ele não vale mais")
    void reabrirAnulaOAceite() {
        Fatura f = novaFatura("2026-08", "100.00");
        Lancamento l = criar(f, "SUPERMERCADO", "100.00");
        conciliacao.validarLeitura(f.id);
        reivindicacoes.reivindicar(l.id, joao, null);
        conciliacao.conciliar(f.id, titular);
        acertos.aceitar(f.id, joao);
        assertEquals(StatusAcerto.ACEITO, acerto(f, joao).status);

        conciliacao.reabrirAvaliacao(f.id, titular, null);

        Acerto a = acerto(f, joao);
        assertEquals(StatusAcerto.ABERTO, a.status);
        assertNull(a.aceitoEm);
    }

    @Test
    @DisplayName("quem já declarou o pagamento continua INFORMADO — o dinheiro saiu e o comprovante fica")
    void reabrirPreservaOPagamentoInformado() {
        Fatura f = novaFatura("2026-08", "100.00");
        Lancamento l = criar(f, "SUPERMERCADO", "100.00");
        conciliacao.validarLeitura(f.id);
        reivindicacoes.reivindicar(l.id, joao, null);
        conciliacao.conciliar(f.id, titular);
        acertos.aceitar(f.id, joao);
        acertos.informarPagamento(f.id, joao, LocalDate.now(), null,
                new byte[] { 1, 2 }, "pix.png", "image/png");

        conciliacao.reabrirAvaliacao(f.id, titular, null);

        Acerto a = acerto(f, joao);
        assertEquals(StatusAcerto.INFORMADO, a.status,
                "apagar o pagamento declarado seria negar que o dinheiro saiu");
        assertNotNull(ComprovantePagamento.doAcerto(a.id), "o comprovante é a prova do acerto");
    }

    @Test
    @DisplayName("acerto já confirmado barra a reabertura: ele nunca é recalculado e a fatura não fecharia")
    void reabrirComAcertoConfirmadoERecusado() {
        Fatura f = novaFatura("2026-08", "100.00");
        Lancamento l = criar(f, "SUPERMERCADO", "100.00");
        conciliacao.validarLeitura(f.id);
        reivindicacoes.reivindicar(l.id, joao, null);
        conciliacao.conciliar(f.id, titular);
        acertos.aceitar(f.id, joao);
        acertos.informarPagamento(f.id, joao, LocalDate.now(), null,
                new byte[] { 1 }, "pix.png", "image/png");
        acertos.confirmarPagamento(acerto(f, joao).id, titular);

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> conciliacao.reabrirAvaliacao(f.id, titular, null));
        assertEquals(409, e.getResponse().getStatus());
        assertEquals(StatusFatura.CONCILIADA, recarregar(f).status, "nada pode ter mudado");
    }

    @Test
    @DisplayName("fatura fechada não volta para avaliação — ela é o histórico do acerto")
    void faturaFechadaNaoReabre() {
        Fatura f = novaFatura("2026-08", "100.00");
        Lancamento l = criar(f, "SUPERMERCADO", "100.00");
        conciliacao.validarLeitura(f.id);
        reivindicacoes.reivindicar(l.id, joao, null);
        conciliacao.conciliar(f.id, titular);
        acertos.aceitar(f.id, joao);
        acertos.informarPagamento(f.id, joao, LocalDate.now(), null,
                new byte[] { 1 }, "pix.png", "image/png");
        acertos.confirmarPagamento(acerto(f, joao).id, titular);
        conciliacao.fechar(f.id, titular);

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> conciliacao.reabrirAvaliacao(f.id, titular, null));
        assertEquals(409, e.getResponse().getStatus());
    }

    @Test
    @DisplayName("depois de reabrir, corrigir e conciliar de novo, as contas continuam batendo")
    void reabrirCorrigirEConciliarDeNovo() {
        Fatura f = novaFatura("2026-08", "300.00");
        Lancamento mercado = criar(f, "SUPERMERCADO", "200.00");
        Lancamento farmacia = criar(f, "FARMACIA", "100.00");
        conciliacao.validarLeitura(f.id);
        // O engano: o João marca a farmácia, que era da Maria.
        reivindicacoes.reivindicar(mercado.id, joao, null);
        reivindicacoes.reivindicar(farmacia.id, joao, null);
        conciliacao.conciliar(f.id, titular);
        assertEquals(0, new BigDecimal("300.00").compareTo(acerto(f, joao).valorDevido));

        conciliacao.reabrirAvaliacao(f.id, titular, "a farmácia era da Maria");
        reivindicacoes.desistir(farmacia.id, joao);
        reivindicacoes.reivindicar(farmacia.id, maria, null);
        conciliacao.conciliar(f.id, titular);

        assertEquals(0, new BigDecimal("200.00").compareTo(acerto(f, joao).valorDevido));
        assertEquals(0, new BigDecimal("100.00").compareTo(acerto(f, maria).valorDevido));
        assertEquals(0, new BigDecimal("300.00").compareTo(somaDosAcertos(f)),
                "a soma dos acertos tem de reproduzir o total, também depois da correção");
    }

    // ======================================= admin atribui sem disputa ===

    @Test
    @DisplayName("o admin aponta o dono de um lançamento que ninguém reivindicou")
    void adminAtribuiSemDisputa() {
        Fatura f = novaFatura("2026-08", "100.00");
        Lancamento l = criar(f, "PARTMED E ODONTOCO", "100.00");
        conciliacao.validarLeitura(f.id);
        assertTrue(Reivindicacao.pendentesDo(l.id).isEmpty(), "ninguém disputou nada");

        reivindicacoes.arbitrar(l.id, joao.id, titular);

        assertEquals(joao.id, responsavelDe(l));
        assertEquals(OrigemAtribuicao.ADMIN, origemDe(l));
    }

    @Test
    @DisplayName("atribuição do admin é contestável enquanto a fatura está em avaliação")
    void atribuicaoDoAdminEContestavel() {
        Fatura f = novaFatura("2026-08", "100.00");
        Lancamento l = criar(f, "PARTMED E ODONTOCO", "100.00");
        conciliacao.validarLeitura(f.id);
        reivindicacoes.arbitrar(l.id, joao.id, titular);

        reivindicacoes.desistir(l.id, joao);

        assertNull(responsavelDe(l),
                "atribuir sem disputa não é desempate: quem recebeu tem de poder discordar");
    }

    @Test
    @DisplayName("o admin não atribui depois de a fatura sair da avaliação")
    void adminNaoAtribuiForaDaAvaliacao() {
        Fatura f = novaFatura("2026-08", "100.00");
        Lancamento l = criar(f, "SUPERMERCADO", "100.00");
        conciliacao.validarLeitura(f.id);
        conciliacao.conciliar(f.id, titular);

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> reivindicacoes.arbitrar(l.id, joao.id, titular));
        assertEquals(409, e.getResponse().getStatus());
    }

    // ============================================ apelido e histórico ===

    @Test
    @DisplayName("o apelido é do estabelecimento e vale para as faturas seguintes")
    void apelidoValeParaOsProximosMeses() {
        Fatura agosto = novaFatura("2026-08", "50.00");
        Lancamento uberAgosto = criar(agosto, "DL*UberRides", "50.00");
        apelidos.apelidar(uberAgosto.id, "Uber", joao);

        Fatura setembro = novaFatura("2026-09", "30.00");
        Lancamento uberSetembro = criar(setembro, "DL*UberRides", "30.00");

        assertEquals("Uber", ApelidoEstabelecimento.mapa()
                .get(descricaoNormalizadaDe(uberSetembro)),
                "apelidar uma vez tem de valer para a mesma loja no mês seguinte");
    }

    @Test
    @DisplayName("apelidar de novo troca o nome, não cria uma segunda linha")
    void apelidarDeNovoSubstitui() {
        Fatura f = novaFatura("2026-08", "50.00");
        Lancamento l = criar(f, "DL*UberRides", "50.00");

        apelidos.apelidar(l.id, "Uber", joao);
        apelidos.apelidar(l.id, "Uber (corridas)", maria);

        assertEquals(1, ApelidoEstabelecimento.count());
        assertEquals("Uber (corridas)", ApelidoEstabelecimento.mapa()
                .get(descricaoNormalizadaDe(l)));
    }

    @Test
    @DisplayName("remover o apelido volta a mostrar o que o banco imprime")
    void removerApelido() {
        Fatura f = novaFatura("2026-08", "50.00");
        Lancamento l = criar(f, "DL*UberRides", "50.00");
        apelidos.apelidar(l.id, "Uber", joao);

        apelidos.remover(l.id, joao);

        assertTrue(ApelidoEstabelecimento.mapa().isEmpty());
    }

    @Test
    @DisplayName("o histórico do pool é de quem está olhando, e nunca o de outra pessoa")
    void historicoEPessoal() {
        Fatura julho = novaFatura("2026-07", "100.00");
        Lancamento padariaJulho = criar(julho, "PADARIA DO BAIRRO", "100.00");
        conciliacao.validarLeitura(julho.id);
        reivindicacoes.reivindicar(padariaJulho.id, joao, null);

        Fatura agosto = novaFatura("2026-08", "60.00");
        Lancamento padariaAgosto = criar(agosto, "PADARIA DO BAIRRO", "60.00");

        Set<String> doJoao = estabelecimentosDe(joao, agosto);
        Set<String> daMaria = estabelecimentosDe(maria, agosto);

        assertTrue(doJoao.contains(descricaoNormalizadaDe(padariaAgosto)),
                "o João comprou nessa padaria em julho — reconhecer vira conferir");
        assertFalse(daMaria.contains(descricaoNormalizadaDe(padariaAgosto)),
                "o que o João assumiu não pode aparecer como sugestão para a Maria");
    }

    @Test
    @DisplayName("a própria fatura não conta como histórico: seria sugerir a si mesma")
    void historicoIgnoraAFaturaAtual() {
        Fatura f = novaFatura("2026-08", "160.00");
        Lancamento primeira = criar(f, "PADARIA DO BAIRRO", "100.00");
        criar(f, "PADARIA DO BAIRRO", "60.00");
        conciliacao.validarLeitura(f.id);
        reivindicacoes.reivindicar(primeira.id, joao, null);

        assertTrue(estabelecimentosDe(joao, f).isEmpty(),
                "'você já comprou aqui' tem de falar de outro mês, não da linha de cima");
    }

    // ============================================================ apoio ===

    private BigDecimal somaDosAcertos(Fatura f) {
        return Acerto.<Acerto>list("fatura.id", f.id).stream()
                .map(a -> a.valorDevido)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional
    Set<String> estabelecimentosDe(Usuario u, Fatura fatura) {
        return Lancamento.estabelecimentosDe(u.id, fatura.id);
    }

    @Transactional
    String descricaoNormalizadaDe(Lancamento l) {
        return ((Lancamento) Lancamento.findById(l.id)).descricaoNormalizada;
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

    @Transactional
    Lancamento criar(Fatura f, String descricao, String valor) {
        Lancamento l = new Lancamento();
        l.fatura = Fatura.findById(f.id);
        l.dataCompra = f.competencia.plusDays(5);
        l.descricao = descricao;
        l.descricaoNormalizada = TextoFatura.normalizar(descricao);
        l.valor = new BigDecimal(valor);
        l.final4 = "1234";
        l.tipo = TipoLancamento.COMPRA;
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
    OrigemAtribuicao origemDe(Lancamento l) {
        Lancamento.getEntityManager().clear();
        return ((Lancamento) Lancamento.findById(l.id)).origemAtribuicao;
    }

    @Transactional
    Acerto acerto(Fatura f, Usuario u) {
        Acerto.getEntityManager().clear();
        Acerto a = Acerto.de(f.id, u.id);
        assertNotNull(a, "esperava acerto de " + u.nome);
        return a;
    }
}
