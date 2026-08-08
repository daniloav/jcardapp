package br.com.jcard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Trilha de auditoria. Num app onde as pessoas assumem dívidas umas das outras,
 * "quem marcou o quê e quando" precisa ser incontestável.
 *
 * <p>{@link #usuarioNome} é desnormalizado de propósito: o registro sobrevive à
 * exclusão do usuário.
 */
@Entity
@Table(name = "auditoria")
public class Auditoria extends EntidadeBase {

    @Column(name = "usuario_id")
    public Long usuarioId;

    @Column(name = "usuario_nome", length = 120)
    public String usuarioNome;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    public AcaoAuditoria acao;

    @Column(nullable = false, length = 40)
    public String entidade;

    @Column(name = "entidade_id")
    public Long entidadeId;

    @Column(length = 600)
    public String detalhe;

    @Column(name = "criado_em", nullable = false)
    public LocalDateTime criadoEm = LocalDateTime.now();
}
