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
 * Quanto um utilizador deve numa fatura e em que ponto está a quitação.
 *
 * <p>Um por (fatura, usuário). O somatório dos acertos tem de reproduzir o total
 * da fatura — é a segunda invariante da conciliação.
 */
@Entity
@Table(name = "acerto")
public class Acerto extends EntidadeBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fatura_id", nullable = false)
    public Fatura fatura;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    public Usuario usuario;

    @Column(name = "valor_devido", nullable = false, precision = 12, scale = 2)
    public BigDecimal valorDevido = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    public StatusAcerto status = StatusAcerto.ABERTO;

    @Column(length = 400)
    public String observacao;

    /** Quando a pessoa conferiu o somatório e concordou com ele. */
    @Column(name = "aceito_em")
    public LocalDateTime aceitoEm;

    /** Data do PIX/transferência, informada por quem pagou. */
    @Column(name = "pago_em")
    public LocalDate pagoEm;

    @Column(name = "informado_em")
    public LocalDateTime informadoEm;

    @Column(name = "confirmado_em")
    public LocalDateTime confirmadoEm;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confirmado_por")
    public Usuario confirmadoPor;

    public static List<Acerto> daFatura(Long faturaId) {
        return list("fatura.id = ?1 order by usuario.nome", faturaId);
    }

    public static Acerto de(Long faturaId, Long usuarioId) {
        return find("fatura.id = ?1 and usuario.id = ?2", faturaId, usuarioId).firstResult();
    }

    public static List<Acerto> doUsuario(Long usuarioId) {
        return list("usuario.id = ?1 order by fatura.competencia desc", usuarioId);
    }
}
