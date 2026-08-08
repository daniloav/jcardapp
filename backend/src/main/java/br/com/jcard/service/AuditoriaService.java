package br.com.jcard.service;

import br.com.jcard.model.AcaoAuditoria;
import br.com.jcard.model.Auditoria;
import br.com.jcard.model.Usuario;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * Trilha de auditoria. Nunca lança: perder um registro de log não pode derrubar
 * a operação que o originou.
 */
@ApplicationScoped
public class AuditoriaService {

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void registrar(Usuario quem, AcaoAuditoria acao, String entidade,
                          Long entidadeId, String detalhe) {
        try {
            Auditoria a = new Auditoria();
            a.usuarioId = quem == null ? null : quem.id;
            a.usuarioNome = quem == null ? "sistema" : quem.nome;
            a.acao = acao;
            a.entidade = entidade;
            a.entidadeId = entidadeId;
            a.detalhe = detalhe == null || detalhe.length() <= 600
                    ? detalhe : detalhe.substring(0, 600);
            a.persist();
        } catch (RuntimeException ignored) {
            // auditoria é acessória: falhar aqui não invalida a ação do usuário
        }
    }
}
