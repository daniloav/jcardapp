package br.com.jcard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Uma linha da fatura. É a unidade que o utilizador assume.
 *
 * <p>{@link #responsavel} nulo significa que o lançamento está no <b>pool</b> —
 * disponível para quem reconhecer a compra. A soma de todos os valores tem de
 * fechar com o total da fatura (ver {@code ConciliacaoService}).
 */
@Entity
@Table(name = "lancamento")
public class Lancamento extends EntidadeBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fatura_id", nullable = false)
    public Fatura fatura;

    @Column(name = "data_compra", nullable = false)
    public LocalDate dataCompra;

    @Column(nullable = false, length = 255)
    public String descricao;

    /** Sem acento, caixa alta, sem o sufixo de parcela — base da chave de parcelamento. */
    @Column(name = "descricao_normalizada", nullable = false, length = 255)
    public String descricaoNormalizada;

    /** Positivo = gasto; negativo = crédito (estorno, pagamento). */
    @Column(nullable = false, precision = 12, scale = 2)
    public BigDecimal valor;

    @Column(nullable = false, length = 3)
    public String moeda = "BRL";

    @Column(name = "portador_nome", length = 120)
    public String portadorNome;

    @Column(name = "final_4", length = 4)
    public String final4;

    @Column(name = "parcela_atual")
    public Integer parcelaAtual;

    @Column(name = "parcela_total")
    public Integer parcelaTotal;

    /** SHA-256 que casa a mesma compra parcelada entre faturas de meses diferentes. */
    @Column(name = "chave_parcelamento", length = 64)
    public String chaveParcelamento;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public TipoLancamento tipo = TipoLancamento.COMPRA;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "responsavel_id")
    public Usuario responsavel;

    @Enumerated(EnumType.STRING)
    @Column(name = "origem_atribuicao", length = 20)
    public OrigemAtribuicao origemAtribuicao;

    @Column(name = "atribuido_em")
    public LocalDateTime atribuidoEm;

    /** Linha crua do PDF — indispensável para depurar o parser sem o arquivo. */
    @Column(name = "linha_original", length = 400)
    public String linhaOriginal;

    // ------------------------------------------------------------ consultas --

    public static List<Lancamento> daFatura(Long faturaId) {
        return list("fatura.id = ?1 order by dataCompra, id", faturaId);
    }

    /** O pool: sem dono e reivindicável. É a tela principal do utilizador. */
    public static List<Lancamento> poolDaFatura(Long faturaId) {
        return list("fatura.id = ?1 and responsavel is null and tipo in ?2 order by dataCompra, id",
                faturaId, List.of(TipoLancamento.COMPRA, TipoLancamento.ESTORNO));
    }

    /**
     * O que é da pessoa: o que ela assumiu sozinha <b>e</b> o que ela divide com
     * outras. Uma conta dividida aparece para todos os participantes, cada um
     * vendo a própria parte.
     */
    public static List<Lancamento> deUsuarioNaFatura(Long faturaId, Long usuarioId) {
        return list("""
                from Lancamento l
                where l.fatura.id = ?1
                  and ( l.responsavel.id = ?2
                        or exists (select 1 from DivisaoLancamento d
                                   where d.lancamento = l and d.usuario.id = ?2) )
                order by l.dataCompra, l.id
                """, faturaId, usuarioId);
    }

    /**
     * O que ainda não tem dono <b>e</b> poderia ter — encargo não entra, porque
     * ele não é de ninguém em particular: é rateado entre quem usou o cartão.
     */
    public static List<Lancamento> semResponsavel(Long faturaId) {
        return list("fatura.id = ?1 and responsavel is null and tipo not in ?2",
                faturaId, tiposRateaveis());
    }

    /** Encargos da fatura: IOF, anuidade, juros, ajustes. Sempre rateados. */
    public static List<Lancamento> encargosDaFatura(Long faturaId) {
        return list("fatura.id = ?1 and tipo in ?2 order by dataCompra, id",
                faturaId, tiposRateaveis());
    }

    private static List<TipoLancamento> tiposRateaveis() {
        return java.util.Arrays.stream(TipoLancamento.values())
                .filter(TipoLancamento::rateavel)
                .toList();
    }

    // ----------------------------------------------------------- comportamento --

    public boolean parcelado() {
        return parcelaTotal != null && parcelaTotal > 1;
    }

    /** A primeira parcela é a que cria o compromisso para as seguintes. */
    public boolean primeiraParcela() {
        return parcelado() && parcelaAtual != null && parcelaAtual == 1;
    }

    public void atribuirA(Usuario u, OrigemAtribuicao origem) {
        this.responsavel = u;
        this.origemAtribuicao = origem;
        this.atribuidoEm = LocalDateTime.now();
    }

    public void liberar() {
        this.responsavel = null;
        this.origemAtribuicao = null;
        this.atribuidoEm = null;
    }

    /** Tem partes gravadas? Então é a divisão que manda no rateio, não o responsável. */
    public boolean dividido() {
        return DivisaoLancamento.count("lancamento.id", id) > 0;
    }
}
