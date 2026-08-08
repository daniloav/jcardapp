package br.com.jcard.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

/**
 * Base das entidades, com id {@code IDENTITY}.
 *
 * <p>Não usamos {@code PanacheEntity} porque o id dele é {@code AUTO}, que no
 * Hibernate 6 vira uma <i>sequence</i> {@code <entidade>_seq} — e o schema
 * (dono do Flyway) declara as chaves como {@code BIGSERIAL}, que é IDENTITY.
 * Declarar a estratégia aqui mantém as duas pontas coerentes.
 */
@MappedSuperclass
public abstract class EntidadeBase extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * Id de forma segura mesmo quando o objeto é um <i>proxy</i> lazy.
     *
     * <p>Ler {@code entidade.associacao.id} direto devolve <b>null</b> num proxy
     * não inicializado — o campo público pertence à instância real, não ao proxy,
     * e acesso a campo não passa pelo interceptador do Hibernate. O getter passa,
     * e ainda por cima resolve o id sem disparar o SELECT.
     *
     * <p>Use sempre este método ao ler o id de uma associação.
     */
    public Long getId() {
        return id;
    }
}
