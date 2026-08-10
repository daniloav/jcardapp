package br.com.jcard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * O print do PIX (ou da transferência) de <b>um</b> pagamento. Obrigatório:
 * sem ele não existe registro de que o dinheiro saiu, e a confirmação do admin
 * viraria palavra contra palavra.
 *
 * <p>É de um {@link PagamentoAcerto}, e não do {@link Acerto}: quem paga em
 * duas transferências tem duas provas, e a segunda não pode apagar a primeira.
 *
 * <p>Tabela separada porque o {@code byte[]} não pode viajar junto em toda
 * consulta de acerto — a tela do admin lê todos os acertos da fatura, e
 * arrastar os anexos junto seria caro à toa. Aqui o conteúdo só é lido quando
 * alguém abre o comprovante.
 */
@Entity
@Table(name = "comprovante_pagamento")
public class ComprovantePagamento extends EntidadeBase {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pagamento_id", nullable = false, unique = true)
    public PagamentoAcerto pagamento;

    @Column(nullable = false, length = 255)
    public String nome;

    /** Content-type validado no serviço: só imagem ou PDF. */
    @Column(nullable = false, length = 100)
    public String tipo;

    @Column(nullable = false)
    public int tamanho;

    @Column(nullable = false)
    public byte[] conteudo;

    @Column(name = "enviado_em", nullable = false)
    public LocalDateTime enviadoEm = LocalDateTime.now();

    public static ComprovantePagamento doPagamento(Long pagamentoId) {
        return find("pagamento.id", pagamentoId).firstResult();
    }

    /** Só o metadado, para a lista não puxar o conteúdo de todo mundo. */
    public static boolean existePara(Long pagamentoId) {
        return count("pagamento.id", pagamentoId) > 0;
    }

    /** Quais destes pagamentos têm comprovante, sem trazer um byte do conteúdo. */
    public static java.util.Set<Long> pagamentosComComprovante(
            java.util.Collection<Long> pagamentoIds) {
        if (pagamentoIds == null || pagamentoIds.isEmpty()) {
            return java.util.Set.of();
        }
        return new java.util.HashSet<>(getEntityManager().createQuery(
                "select c.pagamento.id from ComprovantePagamento c where c.pagamento.id in :ids",
                Long.class)
                .setParameter("ids", pagamentoIds)
                .getResultList());
    }

    /**
     * Quais pagamentos da fatura têm comprovante, numa consulta só — sem trazer
     * um byte do conteúdo.
     */
    public static java.util.Set<Long> pagamentosComComprovanteDaFatura(Long faturaId) {
        return new java.util.HashSet<>(getEntityManager().createQuery("""
                select c.pagamento.id from ComprovantePagamento c
                 where c.pagamento.acerto.fatura.id = :fatura
                """, Long.class)
                .setParameter("fatura", faturaId)
                .getResultList());
    }
}
