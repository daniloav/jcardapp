package br.com.jcard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * O print do PIX (ou da transferência) que a pessoa anexa ao declarar o
 * pagamento. Obrigatório: sem ele o acerto não sai de ACEITO.
 *
 * <p>Tabela separada do {@link Acerto} porque o {@code byte[]} não pode viajar
 * junto em toda consulta de acerto — a tela do admin lê todos os acertos da
 * fatura, e arrastar os anexos junto seria caro à toa. Aqui o conteúdo só é
 * lido quando alguém abre o comprovante.
 */
@Entity
@Table(name = "comprovante_pagamento")
public class ComprovantePagamento extends EntidadeBase {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "acerto_id", nullable = false, unique = true)
    public Acerto acerto;

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

    public static ComprovantePagamento doAcerto(Long acertoId) {
        return find("acerto.id", acertoId).firstResult();
    }

    /** Só o metadado, para a lista não puxar o conteúdo de todo mundo. */
    public static boolean existePara(Long acertoId) {
        return count("acerto.id", acertoId) > 0;
    }
}
