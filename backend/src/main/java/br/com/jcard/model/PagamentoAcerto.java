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

    public boolean confirmado() {
        return confirmadoEm != null;
    }

    public static List<PagamentoAcerto> doAcerto(Long acertoId) {
        return list("acerto.id = ?1 order by pagoEm, id", acertoId);
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
