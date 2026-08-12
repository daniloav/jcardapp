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
import br.com.jcard.model.StatusFatura;
import br.com.jcard.model.Usuario;
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
 * A prévia da fatura: subir o CSV do mês em aberto quantas vezes quiser, sem
 * perder o que as pessoas já assumiram.
 *
 * <p>O recurso inteiro se apoia numa promessa, e é ela que os testes daqui
 * defendem: <b>sobrescrever não custa o trabalho de ninguém</b>. Se a segunda
 * subida desfizesse o que a primeira rendeu, a família aprenderia em uma semana
 * a não mexer na prévia — e o mutirão do dia do vencimento voltaria.
 *
 * <p>O outro lado da promessa é não herdar demais: uma compra que mudou de
 * valor no arquivo novo não é a mesma compra, e ela volta ao pool em vez de
 * cobrar de alguém um número que essa pessoa não conferiu.
 */
@QuarkusTest
class PreviaDeFaturaTest {

    private static final String CABECALHO = "pagina;coluna;data;estabelecimento;parcela;valor";

    private static final String PADARIA = "2;1;05/08;PADARIA DO BAIRRO;;120,00";
    private static final String POSTO = "2;1;07/08;POSTO SHELL;;80,00";
    private static final String UBER = "3;1;09/08;APP DE TRANSPORTE;;7,35";

    @Inject
    PreviaService previas;

    @Inject
    FaturaImportService importacao;

    @Inject
    ConciliacaoService conciliacao;

    @Inject
    ReivindicacaoService reivindicacoes;

    @Inject
    DivisaoService divisoes;

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

    // ==================================================== o que a prévia é ===

    @Test
    @DisplayName("a prévia nasce com o total igual à soma lida e não cobra nada de ninguém")
    void previaNaoTemTotalImpressoNemAcerto() {
        PreviaService.Resultado r = subir(PADARIA, POSTO);

        Fatura p = recarregar(r.fatura());
        assertEquals(StatusFatura.PREVIA, p.status);
        assertEquals(new BigDecimal("200.00"), p.valorTotal,
                "sem total impresso, o total da prévia é o que foi lido");
        assertEquals(p.valorTotal, p.valorLancado, "e por isso ela nunca diverge");
        assertEquals(2, r.lancamentos());
        assertEquals(2, r.noPool());

        reivindicacoes.reivindicar(buscar(p, "PADARIA DO BAIRRO").id, joao, null);

        assertTrue(acertos(p).isEmpty(),
                "ninguém deve nada por uma parcial: acerto só existe na fatura de verdade");
    }

    @Test
    @DisplayName("a prévia não concilia, não fecha e não se reprocessa")
    void previaNaoAvancaNoCiclo() {
        Fatura p = subir(PADARIA).fatura();

        assertEquals(409, assertThrows(WebApplicationException.class,
                () -> conciliacao.conciliar(p.id, titular)).getResponse().getStatus());
        assertEquals(409, assertThrows(WebApplicationException.class,
                () -> conciliacao.fechar(p.id, titular)).getResponse().getStatus());
        assertEquals(409, assertThrows(WebApplicationException.class,
                () -> conciliacao.reabrirAvaliacao(p.id, titular, null)).getResponse().getStatus());
        assertEquals(409, assertThrows(WebApplicationException.class,
                () -> importacao.reprocessar(recarregar(p), titular)).getResponse().getStatus());

        assertEquals(StatusFatura.PREVIA, recarregar(p).status, "e continua sendo a prévia");
    }

    @Test
    @DisplayName("prévia é só CSV, e só do mês que ainda não fechou")
    void previaRecusaPdfEMesJaImportado() {
        WebApplicationException pdf = assertThrows(WebApplicationException.class,
                () -> previas.subir("%PDF-1.4 fingindo ser fatura".getBytes(StandardCharsets.UTF_8),
                        "fatura.pdf", LocalDate.parse("2026-08-01"), titular));
        assertEquals(422, pdf.getResponse().getStatus());

        importacao.importar(csv(PADARIA), "fatura.csv", LocalDate.parse("2026-08-01"),
                new BigDecimal("120.00"), titular);

        WebApplicationException jaTem = assertThrows(WebApplicationException.class,
                () -> subir(PADARIA, POSTO));
        assertEquals(409, jaTem.getResponse().getStatus(),
                "com a fatura de agosto importada, a prévia de agosto perdeu a razão de existir");
    }

    // ============================================ sobrescrever sem perder ===

    @Test
    @DisplayName("subir de novo mantém o que foi assumido e traz o que apareceu no mês")
    void subirDeNovoMantemOQueFoiAssumido() {
        Fatura primeira = subir(PADARIA, POSTO).fatura();
        reivindicacoes.reivindicar(buscar(primeira, "PADARIA DO BAIRRO").id, joao, null);

        // Uma semana depois: as duas compras continuam lá e chegou uma terceira.
        PreviaService.Resultado r = subir(PADARIA, POSTO, UBER);

        Fatura p = recarregar(r.fatura());
        assertFalse(p.id.equals(primeira.id), "a prévia é substituída, não remendada");
        assertEquals(1, r.mantidos());
        assertEquals(0, r.devolvidos());
        assertEquals(3, r.lancamentos());
        assertEquals(2, r.noPool(), "só o posto e a corrida continuam sem dono");

        Lancamento padaria = buscar(p, "PADARIA DO BAIRRO");
        assertEquals(joao.id, padaria.responsavel.getId(),
                "a padaria continua do João numa prévia que ele nem abriu de novo");
        assertEquals(OrigemAtribuicao.MANUAL, padaria.origemAtribuicao,
                "e continua sendo uma decisão dele, não um palpite do app");
    }

    @Test
    @DisplayName("compra que mudou de valor volta ao pool em vez de ser herdada")
    void compraQueMudouVoltaAoPool() {
        Fatura primeira = subir(PADARIA, POSTO).fatura();
        reivindicacoes.reivindicar(buscar(primeira, "POSTO SHELL").id, maria, null);

        // O restaurante lançou a gorjeta depois; o posto de R$ 80,00 virou R$ 95,00.
        PreviaService.Resultado r = subir(PADARIA, "2;1;07/08;POSTO SHELL;;95,00");

        assertEquals(0, r.mantidos());
        assertEquals(1, r.devolvidos(),
                "outro valor é outra compra: herdar cobraria da Maria um número que ela não viu");
        assertNull(buscar(recarregar(r.fatura()), "POSTO SHELL").responsavel);
    }

    @Test
    @DisplayName("duas compras iguais de donos diferentes não trocam de dono na sobrescrita")
    void comprasIdenticasNaoTrocamDeDono() {
        Fatura primeira = subir(UBER, UBER).fatura();
        List<Lancamento> corridas = todos(primeira);
        reivindicacoes.reivindicar(corridas.get(0).id, joao, null);
        reivindicacoes.reivindicar(corridas.get(1).id, maria, null);

        PreviaService.Resultado r = subir(UBER, UBER);

        assertEquals(2, r.mantidos());
        assertEquals(List.of(joao.id, maria.id),
                todos(recarregar(r.fatura())).stream().map(l -> l.responsavel.getId()).toList(),
                "a fila por chave casa uma a uma, na ordem; um mapa simples daria "
                        + "as duas corridas ao dono da última");
    }

    @Test
    @DisplayName("a conta rachada continua rachada depois de subir de novo")
    void divisaoSobreviveASobrescrita() {
        Fatura primeira = subir(PADARIA).fatura();
        Lancamento padaria = buscar(primeira, "PADARIA DO BAIRRO");
        reivindicacoes.reivindicar(padaria.id, joao, null);
        divisoes.dividir(padaria.id, joao, false, List.of(
                new DivisaoService.Parte(joao.id, new BigDecimal("70.00")),
                new DivisaoService.Parte(maria.id, new BigDecimal("50.00"))));

        Fatura p = recarregar(subir(PADARIA, POSTO).fatura());

        List<DivisaoLancamento> partes = partesDe(buscar(p, "PADARIA DO BAIRRO"));
        assertEquals(2, partes.size(), "quem estava na mesa continua na mesa");
        assertEquals(new BigDecimal("120.00"),
                partes.stream().map(d -> d.valor).reduce(BigDecimal.ZERO, BigDecimal::add),
                "e a soma das partes continua fechando com o lançamento");
    }

    // ================================== a prévia vira a fatura de verdade ===

    @Test
    @DisplayName("a fatura de verdade nasce com o que a família assumiu na prévia")
    void faturaDefinitivaHerdaDaPrevia() {
        Fatura previa = subir(PADARIA, POSTO).fatura();
        reivindicacoes.reivindicar(buscar(previa, "PADARIA DO BAIRRO").id, joao, null);
        reivindicacoes.reivindicar(buscar(previa, "POSTO SHELL").id, maria, null);

        // O mês fecha: o CSV final tem as mesmas compras mais o IOF, e agora com
        // o total impresso na fatura.
        Fatura f = importacao.importar(
                csv(PADARIA, POSTO, "9;2;31/08;IOF;;3,50"), "fatura-agosto.csv",
                LocalDate.parse("2026-08-01"), new BigDecimal("203.50"), titular);

        assertNull(Fatura.previaDa(LocalDate.parse("2026-08-01")),
                "a prévia foi consumida: ela existia para chegar até aqui");
        assertEquals(StatusFatura.EM_AVALIACAO, recarregar(f).status);
        assertEquals(joao.id, buscar(f, "PADARIA DO BAIRRO").responsavel.getId());
        assertEquals(maria.id, buscar(f, "POSTO SHELL").responsavel.getId());
        assertTrue(Lancamento.poolDaFatura(f.id).isEmpty(),
                "no dia do vencimento não sobrou nada para reconhecer de memória");

        // E o rateio já vale: o IOF é dividido entre os dois que usaram o cartão.
        assertEquals(2, acertos(f).size());
    }

    @Test
    @DisplayName("a parcela assumida na prévia só vira compromisso na fatura de verdade")
    void compromissoNasceNaFaturaEnaoNaPrevia() {
        String parcela = "2;1;08/08;LOJA PARCELADA;01/10;190,00";
        Fatura previa = subir(parcela).fatura();
        reivindicacoes.reivindicar(buscar(previa, "LOJA PARCELADA").id, joao, null);

        assertTrue(compromissos().isEmpty(),
                "a prévia some a cada subida: um compromisso apontando para ela ficaria "
                        + "órfão decidindo o dono das parcelas dos outros meses");

        importacao.importar(csv(parcela), "fatura-agosto.csv",
                LocalDate.parse("2026-08-01"), new BigDecimal("190.00"), titular);

        List<CompromissoParcelado> compromissos = compromissos();
        assertEquals(1, compromissos.size(), "na fatura de verdade, o parcelamento gruda");
        assertEquals(joao.id, compromissos.get(0).usuario.getId());
    }

    @Test
    @DisplayName("a prévia lê o compromisso de parcelamento, mas não o encerra")
    void previaNaoEncerraOCompromisso() {
        // Julho fechou com o João assumindo a penúltima parcela de uma compra em 2x.
        String penultima = "2;1;08/07;LOJA PARCELADA;01/02;100,00";
        Fatura julho = importacao.importar(csv(penultima), "julho.csv",
                LocalDate.parse("2026-07-01"), new BigDecimal("100.00"), titular);
        reivindicacoes.reivindicar(buscar(julho, "LOJA PARCELADA").id, joao, null);

        // Agosto ainda está em aberto, e a última parcela já aparece na prévia.
        Fatura previa = subir("2;1;08/08;LOJA PARCELADA;02/02;100,00").fatura();

        Lancamento ultima = buscar(previa, "LOJA PARCELADA");
        assertEquals(joao.id, ultima.responsavel.getId(),
                "a prévia já mostra a parcela no nome de quem assumiu a primeira");
        assertEquals(OrigemAtribuicao.HERDADA_PARCELA, ultima.origemAtribuicao);
        assertTrue(compromissos().get(0).ativo,
                "o compromisso continua ativo: encerrá-lo numa parcial deixaria a fatura "
                        + "de verdade, que vem depois, sem de onde herdar a mesma parcela");
    }

    // ------------------------------------------------------------- apoio --

    private PreviaService.Resultado subir(String... linhas) {
        return previas.subir(csv(linhas), "previa.csv", LocalDate.parse("2026-08-01"), titular);
    }

    private static byte[] csv(String... linhas) {
        StringBuilder sb = new StringBuilder(CABECALHO).append("\n");
        for (String l : linhas) {
            sb.append(l).append("\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
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
    List<Lancamento> todos(Fatura f) {
        Lancamento.getEntityManager().clear();
        return Lancamento.daFatura(f.id);
    }

    @Transactional
    List<DivisaoLancamento> partesDe(Lancamento l) {
        DivisaoLancamento.getEntityManager().clear();
        return DivisaoLancamento.doLancamento(l.id);
    }

    @Transactional
    List<Acerto> acertos(Fatura f) {
        Acerto.getEntityManager().clear();
        return Acerto.daFatura(f.id);
    }

    @Transactional
    List<CompromissoParcelado> compromissos() {
        CompromissoParcelado.getEntityManager().clear();
        return CompromissoParcelado.listAll();
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
