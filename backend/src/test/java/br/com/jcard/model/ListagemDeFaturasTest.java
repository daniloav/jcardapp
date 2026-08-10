package br.com.jcard.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jcard.parser.TextoFatura;
import br.com.jcard.service.ReivindicacaoService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * As consultas que sustentam a listagem de faturas e o gráfico da tela inicial.
 *
 * <p>Elas existem por desempenho: uma consulta agregada no lugar de três por
 * fatura, e uma projeção no lugar da entidade para não arrastar o texto da
 * fatura. O risco de uma otimização assim é justamente ela responder <b>outro
 * número</b> — daí os testes conferirem o agregado contra a contagem feita
 * fatura por fatura, que é a que o resto do app usa.
 */
@QuarkusTest
class ListagemDeFaturasTest {

    @Inject
    ReivindicacaoService reivindicacoes;

    private Usuario joao;
    private Usuario maria;

    @BeforeEach
    @Transactional
    void limpar() {
        Acerto.deleteAll();
        Reivindicacao.deleteAll();
        DivisaoLancamento.deleteAll();
        CompromissoParcelado.deleteAll();
        Lancamento.deleteAll();
        Fatura.deleteAll();
        Usuario.deleteAll();

        // O titular admin não entra nas contagens, mas a reivindicação exige um:
        // é para ele que vai o que ninguém assume.
        novoUsuario("Jose Titular", "jose@teste.local", true);
        joao = novoUsuario("Joao Filho", "joao@teste.local");
        maria = novoUsuario("Maria Filha", "maria@teste.local");
    }

    @Test
    @DisplayName("as contagens agregadas batem com a contagem fatura por fatura")
    void contagensBatemComOCaminhoAntigo() {
        Fatura julho = novaFatura("2026-07", "300.00");
        criar(julho, "SUPERMERCADO", "100.00", TipoLancamento.COMPRA);
        criar(julho, "PADARIA", "50.00", TipoLancamento.COMPRA);
        criar(julho, "IOF", "3.00", TipoLancamento.IOF);
        assumir(criar(julho, "POSTO", "147.00", TipoLancamento.COMPRA), joao);

        Fatura agosto = novaFatura("2026-08", "80.00");
        criar(agosto, "FARMACIA", "80.00", TipoLancamento.COMPRA);

        Map<Long, Lancamento.Contagem> contagens = contagens();

        assertEquals(new Lancamento.Contagem(4, 2), contagens.get(julho.id),
                "quatro lançamentos; no pool só as duas compras sem dono — "
                        + "o IOF é rateado e o posto já tem dono");
        assertEquals(new Lancamento.Contagem(1, 1), contagens.get(agosto.id));

        // O agregado tem de responder o mesmo que o caminho por fatura, que é o
        // que a conciliação e a tela do utilizador continuam usando.
        for (Fatura f : List.of(julho, agosto)) {
            Lancamento.Contagem c = contagens.get(f.id);
            assertEquals(porFatura(f), c.total(), "total da fatura " + f.competencia);
            assertEquals(noPool(f), c.noPool(), "pool da fatura " + f.competencia);
        }
    }

    @Test
    @DisplayName("estorno conta no pool; fatura vazia simplesmente não aparece no mapa")
    void estornoContaNoPoolEFaturaVaziaNaoAparece() {
        Fatura f = novaFatura("2026-08", "100.00");
        criar(f, "SUPERMERCADO", "150.00", TipoLancamento.COMPRA);
        criar(f, "ESTORNO SUPERMERCADO", "-50.00", TipoLancamento.ESTORNO);
        Fatura vazia = novaFatura("2026-09", "0.00");

        Map<Long, Lancamento.Contagem> contagens = contagens();

        assertEquals(new Lancamento.Contagem(2, 2), contagens.get(f.id),
                "o estorno é reivindicável: quem devolveu a compra é quem a fez");
        assertFalse(contagens.containsKey(vazia.id));
        assertEquals(Lancamento.Contagem.VAZIA,
                contagens.getOrDefault(vazia.id, Lancamento.Contagem.VAZIA));
    }

    @Test
    @DisplayName("os conflitos são contados por fatura, e só com dois pretendentes ou mais")
    void conflitosPorFatura() {
        Fatura julho = novaFatura("2026-07", "300.00");
        Lancamento disputado = criar(julho, "RESTAURANTE", "200.00", TipoLancamento.COMPRA);
        Lancamento tranquilo = criar(julho, "PADARIA", "100.00", TipoLancamento.COMPRA);
        Fatura agosto = novaFatura("2026-08", "100.00");
        Lancamento outro = criar(agosto, "CINEMA", "100.00", TipoLancamento.COMPRA);

        abrirAvaliacao(julho);
        abrirAvaliacao(agosto);
        reivindicar(disputado, joao);
        reivindicar(disputado, maria);
        reivindicar(tranquilo, joao);
        reivindicar(outro, maria);

        Map<Long, Integer> conflitos = conflitos();

        assertEquals(1, conflitos.get(julho.id),
                "um lançamento em conflito, mesmo com dois pretendentes nele");
        assertFalse(conflitos.containsKey(agosto.id),
                "um pretendente só leva na hora — não é conflito");
        assertEquals(Reivindicacao.lancamentosEmConflito(julho.id).size(),
                conflitos.get(julho.id).intValue(),
                "o agregado tem de dizer o mesmo que a fila de arbitragem do admin");
    }

    @Test
    @DisplayName("o resumo traz o cabeçalho e a divergência, sem o texto da fatura")
    void resumoTrazOCabecalhoSemOTexto() {
        Fatura julho = novaFatura("2026-07", "300.00");
        Fatura agosto = novaFatura("2026-08", "100.00");
        lancar(agosto, "90.00");

        List<ResumoFatura> resumos = resumos();

        assertEquals(List.of(agosto.id, julho.id), resumos.stream().map(ResumoFatura::id).toList(),
                "mais recentes primeiro");
        ResumoFatura r = resumos.get(0);
        assertEquals(new BigDecimal("10.00"), r.divergencia(),
                "a divergência é a mesma conta da entidade: impresso menos lido");
        assertEquals(StatusFatura.IMPORTADA, r.status());
        assertEquals("ITAU", r.emissor());
        assertTrue(r.importadaEm() != null && r.competencia() != null);
    }

    // ------------------------------------------------------------- apoio --

    @Transactional
    Map<Long, Lancamento.Contagem> contagens() {
        return Lancamento.contagensPorFatura();
    }

    @Transactional
    Map<Long, Integer> conflitos() {
        return Reivindicacao.conflitosPorFatura();
    }

    @Transactional
    List<ResumoFatura> resumos() {
        return Fatura.resumosRecentes();
    }

    @Transactional
    long porFatura(Fatura f) {
        return Lancamento.count("fatura.id", f.id);
    }

    @Transactional
    long noPool(Fatura f) {
        return Lancamento.poolDaFatura(f.id).size();
    }

    void reivindicar(Lancamento l, Usuario u) {
        reivindicacoes.reivindicar(l.id, u, null);
    }

    /** Reivindicar só vale com a fatura aberta; aqui o assunto é a contagem. */
    @Transactional
    void abrirAvaliacao(Fatura f) {
        ((Fatura) Fatura.findById(f.id)).status = StatusFatura.EM_AVALIACAO;
    }

    Usuario novoUsuario(String nome, String email) {
        return novoUsuario(nome, email, false);
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
        f.textoExtraido = "linha de fatura ".repeat(500);
        f.persist();
        return f;
    }

    @Transactional
    void lancar(Fatura f, String valorLancado) {
        Fatura fresca = Fatura.findById(f.id);
        fresca.valorLancado = new BigDecimal(valorLancado);
    }

    @Transactional
    Lancamento criar(Fatura f, String descricao, String valor, TipoLancamento tipo) {
        Lancamento l = new Lancamento();
        l.fatura = Fatura.findById(f.id);
        l.dataCompra = f.competencia.plusDays(5);
        l.descricao = descricao;
        l.descricaoNormalizada = TextoFatura.normalizar(descricao);
        l.valor = new BigDecimal(valor);
        l.final4 = "1234";
        l.tipo = tipo;
        l.persist();
        return l;
    }

    @Transactional
    void assumir(Lancamento l, Usuario u) {
        Lancamento fresco = Lancamento.findById(l.id);
        fresco.atribuirA(Usuario.findById(u.id), OrigemAtribuicao.MANUAL);
    }
}
