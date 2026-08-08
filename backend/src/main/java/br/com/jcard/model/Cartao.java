package br.com.jcard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Cartão titular ou adicional, identificado pelos 4 últimos dígitos.
 *
 * <p>A fatura do Itaú vem seccionada por portador. Quando um adicional é sempre
 * da mesma pessoa, {@link #donoPadrao} faz o app atribuir os lançamentos daquele
 * cartão automaticamente na importação — o utilizador nem precisa reivindicar.
 */
@Entity
@Table(name = "cartao")
public class Cartao extends EntidadeBase {

    @Column(nullable = false, length = 80)
    public String apelido;

    @Column(name = "final_4", nullable = false, unique = true, length = 4)
    public String final4;

    /** Nome como aparece impresso na fatura (ajuda a casar a seção do PDF). */
    @Column(name = "portador_nome", length = 120)
    public String portadorNome;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dono_padrao_id")
    public Usuario donoPadrao;

    /** É o cartão do titular (o dono da conta), que absorve a sobra não reclamada. */
    @Column(nullable = false)
    public boolean titular = false;

    @Column(nullable = false)
    public boolean ativo = true;

    @Column(name = "criado_em", nullable = false)
    public LocalDateTime criadoEm = LocalDateTime.now();

    public static Cartao porFinal(String final4) {
        return find("final4", final4).firstResult();
    }
}
