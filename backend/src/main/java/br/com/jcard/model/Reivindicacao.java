package br.com.jcard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Um utilizador dizendo "essa compra foi minha".
 *
 * <p>Se só uma pessoa reivindica, o serviço aceita na hora. Se duas ou mais
 * reivindicam o mesmo lançamento, vira <b>conflito</b> e ninguém leva até o admin
 * arbitrar.
 */
@Entity
@Table(name = "reivindicacao")
public class Reivindicacao extends EntidadeBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lancamento_id", nullable = false)
    public Lancamento lancamento;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    public Usuario usuario;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    public StatusReivindicacao status = StatusReivindicacao.PENDENTE;

    @Column(length = 400)
    public String observacao;

    @Column(name = "criado_em", nullable = false)
    public LocalDateTime criadoEm = LocalDateTime.now();

    @Column(name = "resolvido_em")
    public LocalDateTime resolvidoEm;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolvido_por")
    public Usuario resolvidoPor;

    public static List<Reivindicacao> pendentesDo(Long lancamentoId) {
        return list("lancamento.id = ?1 and status = ?2", lancamentoId, StatusReivindicacao.PENDENTE);
    }

    public static Reivindicacao de(Long lancamentoId, Long usuarioId) {
        return find("lancamento.id = ?1 and usuario.id = ?2", lancamentoId, usuarioId).firstResult();
    }

    /** Lançamentos da fatura com 2+ pendentes: a fila de arbitragem do admin. */
    public static List<Long> lancamentosEmConflito(Long faturaId) {
        return getEntityManager()
                .createQuery("""
                        select r.lancamento.id from Reivindicacao r
                        where r.lancamento.fatura.id = :f and r.status = :s
                        group by r.lancamento.id having count(r.id) > 1
                        """, Long.class)
                .setParameter("f", faturaId)
                .setParameter("s", StatusReivindicacao.PENDENTE)
                .getResultList();
    }
}
