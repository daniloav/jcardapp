package br.com.jcard.service;

import br.com.jcard.model.AcaoAuditoria;
import br.com.jcard.model.Acerto;
import br.com.jcard.model.StatusAcerto;
import br.com.jcard.model.Usuario;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import java.time.LocalDateTime;

/**
 * Ciclo de quitação: o utilizador declara que pagou, o admin confere e confirma.
 *
 * <p>A confirmação é sempre do admin — o app não tem como saber se o PIX caiu.
 */
@ApplicationScoped
public class AcertoService {

    @Inject
    NotificacaoService notificacao;

    @Inject
    AuditoriaService auditoria;

    /** O utilizador diz que pagou. Não confirma nada sozinho. */
    @Transactional
    public Acerto informarPagamento(Long faturaId, Usuario quem, String observacao) {
        Acerto a = Acerto.de(faturaId, quem.id);
        if (a == null) {
            throw new WebApplicationException("Você não tem acerto nesta fatura.", 404);
        }
        if (a.status == StatusAcerto.CONFIRMADO) {
            throw new WebApplicationException("Este acerto já foi confirmado.", 409);
        }
        a.status = StatusAcerto.INFORMADO;
        a.informadoEm = LocalDateTime.now();
        a.observacao = observacao;
        a.persist();
        auditoria.registrar(quem, AcaoAuditoria.INFORMAR_PAGAMENTO, "Acerto", a.id,
                "R$ " + a.valorDevido);
        return a;
    }

    /** O admin confirma que o dinheiro entrou. */
    @Transactional
    public Acerto confirmarPagamento(Long acertoId, Usuario admin) {
        Acerto a = Acerto.findById(acertoId);
        if (a == null) {
            throw new WebApplicationException("Acerto não encontrado.", 404);
        }
        if (a.status == StatusAcerto.CONFIRMADO) {
            return a;
        }
        a.status = StatusAcerto.CONFIRMADO;
        a.confirmadoEm = LocalDateTime.now();
        a.confirmadoPor = admin;
        a.persist();
        auditoria.registrar(admin, AcaoAuditoria.CONFIRMAR_PAGAMENTO, "Acerto", a.id,
                a.usuario.nome + " · R$ " + a.valorDevido);
        notificacao.pagamentoConfirmado(a);
        return a;
    }

    /** Desfaz uma confirmação feita por engano. */
    @Transactional
    public Acerto reabrir(Long acertoId, Usuario admin) {
        Acerto a = Acerto.findById(acertoId);
        if (a == null) {
            throw new WebApplicationException("Acerto não encontrado.", 404);
        }
        a.status = StatusAcerto.ABERTO;
        a.confirmadoEm = null;
        a.confirmadoPor = null;
        a.informadoEm = null;
        a.persist();
        auditoria.registrar(admin, AcaoAuditoria.ATUALIZAR, "Acerto", a.id, "reaberto");
        return a;
    }
}
