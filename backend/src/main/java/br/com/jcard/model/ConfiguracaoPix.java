package br.com.jcard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * A chave PIX para onde os acertos são pagos, editável pelo admin.
 *
 * <p>Linha única — garantido pelo banco (ver V5). A variável de ambiente
 * {@code JCARD_PIX_CHAVE} continua sendo o valor inicial: esta tabela vazia
 * significa "ainda ninguém trocou pela tela", e não "não há chave".
 */
@Entity
@Table(name = "configuracao_pix")
public class ConfiguracaoPix extends EntidadeBase {

    @Column(nullable = false, length = 20)
    public String tipo;

    @Column(nullable = false, length = 140)
    public String chave;

    @Column(nullable = false, length = 120)
    public String titular;

    @Column(name = "atualizado_em", nullable = false)
    public LocalDateTime atualizadoEm = LocalDateTime.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "atualizado_por")
    public Usuario atualizadoPor;

    /** Só existe para a restrição de linha única do banco morder. */
    @Column(name = "linha_unica", nullable = false)
    public boolean linhaUnica = true;

    /** A configuração salva, ou {@code null} enquanto ninguém tiver salvado. */
    public static ConfiguracaoPix atual() {
        return find("order by id").firstResult();
    }
}
