package br.com.jcard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A regra "parcelamento gruda": quando alguém assume a parcela 1/10, as outras
 * nove já entram no nome dele nas faturas seguintes, sem precisar reivindicar
 * de novo.
 *
 * <p>{@link #chaveParcelamento} é o que casa a mesma compra entre meses — ver
 * {@code ChaveParcelamento}. O compromisso se encerra sozinho quando a última
 * parcela é vista.
 */
@Entity
@Table(name = "compromisso_parcelado")
public class CompromissoParcelado extends EntidadeBase {

    @Column(name = "chave_parcelamento", nullable = false, unique = true, length = 64)
    public String chaveParcelamento;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    public Usuario usuario;

    @Column(name = "descricao_normalizada", nullable = false, length = 255)
    public String descricaoNormalizada;

    @Column(name = "final_4", length = 4)
    public String final4;

    @Column(name = "parcela_total", nullable = false)
    public int parcelaTotal;

    @Column(name = "valor_parcela", nullable = false, precision = 12, scale = 2)
    public BigDecimal valorParcela;

    /** Maior parcela já processada; serve para encerrar o compromisso no fim. */
    @Column(name = "ultima_parcela_vista", nullable = false)
    public int ultimaParcelaVista = 0;

    @Column(nullable = false)
    public boolean ativo = true;

    @Column(name = "criado_em", nullable = false)
    public LocalDateTime criadoEm = LocalDateTime.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "origem_lancamento_id")
    public Lancamento origemLancamento;

    public static CompromissoParcelado ativoPorChave(String chave) {
        return find("chaveParcelamento = ?1 and ativo = true", chave).firstResult();
    }

    /**
     * Registra que esta parcela foi processada e encerra o compromisso quando a
     * última chega — assim ele não fica pegando compras futuras parecidas.
     */
    public void registrarParcela(int parcela) {
        if (parcela > ultimaParcelaVista) {
            ultimaParcelaVista = parcela;
        }
        if (ultimaParcelaVista >= parcelaTotal) {
            ativo = false;
        }
    }
}
