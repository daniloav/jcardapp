package br.com.jcard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Uma transferência feita para quitar um acerto.
 *
 * <p>São várias por acerto porque o valor devido muda: a pessoa paga R$ 100, o
 * fechamento leva o total dela para R$ 130 (o divisor do encargo mudou, o admin
 * atribuiu mais um lançamento) e ela manda os R$ 30 que faltam. Cada
 * transferência tem o próprio valor, a própria data e o próprio comprovante —
 * juntar tudo num campo só apagaria a prova da primeira.
 *
 * <p>O admin confirma <b>pagamento a pagamento</b>: é assim que ele confere, uma
 * entrada de cada vez, no extrato. O acerto só fica {@code CONFIRMADO} quando
 * todos estão confirmados e não sobra saldo.
 */
@Entity
@Table(name = "pagamento_acerto")
public class PagamentoAcerto extends EntidadeBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "acerto_id", nullable = false)
    public Acerto acerto;

    @Column(nullable = false, precision = 12, scale = 2)
    public BigDecimal valor;

    /** Data do PIX/transferência, informada por quem pagou. */
    @Column(name = "pago_em", nullable = false)
    public LocalDate pagoEm;

    @Column(length = 400)
    public String observacao;

    @Column(name = "informado_em", nullable = false)
    public LocalDateTime informadoEm = LocalDateTime.now();

    @Column(name = "confirmado_em")
    public LocalDateTime confirmadoEm;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confirmado_por")
    public Usuario confirmadoPor;

    /**
     * O admin que deu baixa em nome da pessoa; {@code null} quando foi ela
     * mesma que declarou.
     *
     * <p>Preenchido, é uma transferência <b>sem comprovante</b>: quem paga o PIX
     * e nunca abre o app deixaria o acerto aberto para sempre, e o admin, que vê
     * a entrada no extrato, registra por ela. A coluna existe porque as duas
     * origens não podem ficar indistinguíveis — uma tem prova anexada, a outra
     * tem a palavra de quem registrou, e é essa diferença que a tela mostra.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registrado_por")
    public Usuario registradoPor;

    public boolean confirmado() {
        return confirmadoEm != null;
    }

    /** Baixa dada pelo admin, sem comprovante — ver {@link #registradoPor}. */
    public boolean baixaManual() {
        return registradoPor != null;
    }

    public static List<PagamentoAcerto> doAcerto(Long acertoId) {
        return list("acerto.id = ?1 order by pagoEm, id", acertoId);
    }

    /** Todas as transferências da fatura, numa consulta só. */
    public static List<PagamentoAcerto> daFatura(Long faturaId) {
        return list("acerto.fatura.id = ?1 order by pagoEm, id", faturaId);
    }

    /** Quanto já entrou neste acerto, confirmado ou não. */
    public static BigDecimal totalPago(Long acertoId) {
        return doAcerto(acertoId).stream()
                .map(p -> p.valor)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Quanto já entrou em cada acerto da fatura, numa consulta só.
     *
     * <p>A tela do admin lê todos os acertos de uma vez; perguntar por acerto
     * seria uma ida ao banco por pessoa só para somar duas linhas — e cada ida
     * custa latência de rede com o banco no Neon.
     */
    public static java.util.Map<Long, BigDecimal> totaisPorAcertoDaFatura(Long faturaId) {
        java.util.Map<Long, BigDecimal> mapa = new java.util.HashMap<>();
        getEntityManager().createQuery("""
                select p.acerto.id, sum(p.valor)
                  from PagamentoAcerto p
                 where p.acerto.fatura.id = :fatura
                 group by p.acerto.id
                """, Object[].class)
                .setParameter("fatura", faturaId)
                .getResultList()
                .forEach(l -> mapa.put((Long) l[0], (BigDecimal) l[1]));
        return mapa;
    }
}
