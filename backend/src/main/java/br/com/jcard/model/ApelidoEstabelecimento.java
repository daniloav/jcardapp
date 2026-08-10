package br.com.jcard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * O nome que a família usa para um estabelecimento, no lugar do que o banco
 * imprime ({@code DL*UberRides}, {@code PARTMED E ODONTOCO}).
 *
 * <p>É chaveado pela {@code descricaoNormalizada} do lançamento — a mesma chave
 * que o parcelamento usa para casar a mesma compra entre faturas —, então o
 * apelido dado uma vez vale para todos os meses seguintes.
 *
 * <p>Vale para todo mundo, não por pessoa: quem apelida "DL*UberRides" de
 * "Uber" está descrevendo a loja, não registrando uma preferência.
 */
@Entity
@Table(name = "apelido_estabelecimento")
public class ApelidoEstabelecimento extends EntidadeBase {

    @Column(name = "descricao_normalizada", nullable = false, unique = true, length = 255)
    public String descricaoNormalizada;

    @Column(nullable = false, length = 120)
    public String apelido;

    @Column(name = "criado_em", nullable = false)
    public LocalDateTime criadoEm = LocalDateTime.now();

    @Column(name = "atualizado_em", nullable = false)
    public LocalDateTime atualizadoEm = LocalDateTime.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "atualizado_por")
    public Usuario atualizadoPor;

    public static ApelidoEstabelecimento porDescricao(String descricaoNormalizada) {
        return find("descricaoNormalizada", descricaoNormalizada).firstResult();
    }

    /**
     * Todos os apelidos num mapa. A tabela tem uma linha por estabelecimento
     * que alguém se deu ao trabalho de nomear — dezenas, não milhares —, então
     * carregar tudo de uma vez sai mais barato que consultar por lançamento
     * numa fatura de 514 linhas.
     */
    public static Map<String, String> mapa() {
        Map<String, String> m = new HashMap<>();
        for (ApelidoEstabelecimento a : ApelidoEstabelecimento.<ApelidoEstabelecimento>listAll()) {
            m.put(a.descricaoNormalizada, a.apelido);
        }
        return m;
    }
}
