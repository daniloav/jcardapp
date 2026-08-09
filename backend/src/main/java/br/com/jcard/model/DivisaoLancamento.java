package br.com.jcard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A parte de uma pessoa num lançamento dividido — o jantar rachado entre três.
 *
 * <p>Quando existe divisão, ela é a <b>verdade</b> do rateio daquele lançamento:
 * o {@code responsavel} deixa de responder pelo valor inteiro e passa a valer
 * apenas como "quem organizou a divisão" (é dele que o compromisso parcelado
 * tira o dono das parcelas seguintes).
 *
 * <p>A soma das partes tem de reproduzir o valor do lançamento — sem isso a
 * primeira invariante da conciliação cairia. Quem garante é o
 * {@code DivisaoService}, porque é uma regra entre linhas e não cabe num CHECK.
 */
@Entity
@Table(name = "divisao_lancamento")
public class DivisaoLancamento extends EntidadeBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lancamento_id", nullable = false)
    public Lancamento lancamento;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    public Usuario usuario;

    @Column(nullable = false, precision = 12, scale = 2)
    public BigDecimal valor;

    @Column(name = "criado_em", nullable = false)
    public LocalDateTime criadoEm = LocalDateTime.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "criado_por")
    public Usuario criadoPor;

    public static List<DivisaoLancamento> doLancamento(Long lancamentoId) {
        return list("lancamento.id = ?1 order by usuario.nome", lancamentoId);
    }

    /** Todas as partes da fatura de uma vez — evita N+1 ao montar o rateio. */
    public static List<DivisaoLancamento> daFatura(Long faturaId) {
        return list("lancamento.fatura.id = ?1", faturaId);
    }

    public static void apagarDo(Long lancamentoId) {
        delete("lancamento.id", lancamentoId);
    }
}
