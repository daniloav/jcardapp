package br.com.jcard.service;

import br.com.jcard.model.AcaoAuditoria;
import br.com.jcard.model.DivisaoLancamento;
import br.com.jcard.model.Lancamento;
import br.com.jcard.model.OrigemAtribuicao;
import br.com.jcard.model.Reivindicacao;
import br.com.jcard.model.StatusReivindicacao;
import br.com.jcard.model.Usuario;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import java.time.LocalDateTime;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * "Essa compra foi minha."
 *
 * <p>Regra de disputa: enquanto <b>uma</b> pessoa reivindica, o lançamento é dela
 * na hora — o caso comum não precisa de burocracia. Assim que uma segunda pessoa
 * reivindica o mesmo lançamento, ele <b>volta para o pool</b> e ninguém fica com
 * ele até o admin arbitrar. Nunca deixamos o "quem chegou primeiro" decidir uma
 * cobrança contestada.
 */
@ApplicationScoped
public class ReivindicacaoService {

    private static final Logger LOG = Logger.getLogger(ReivindicacaoService.class);

    @Inject
    AtribuicaoService atribuicao;

    @Inject
    ConciliacaoService conciliacao;

    @Inject
    NotificacaoService notificacao;

    @Inject
    AuditoriaService auditoria;

    @Transactional
    public Lancamento reivindicar(Long lancamentoId, Usuario quem, String observacao) {
        Lancamento l = buscarReivindicavel(lancamentoId);

        if (l.responsavel != null && l.responsavel.getId().equals(quem.id)) {
            throw new WebApplicationException("Este lançamento já está com você.", 409);
        }

        Reivindicacao minha = Reivindicacao.de(lancamentoId, quem.id);
        if (minha == null) {
            minha = new Reivindicacao();
            minha.lancamento = l;
            minha.usuario = quem;
        }
        minha.status = StatusReivindicacao.PENDENTE;
        minha.observacao = observacao;
        minha.criadoEm = LocalDateTime.now();
        minha.resolvidoEm = null;
        minha.resolvidoPor = null;
        minha.persist();

        // Contestar uma atribuição existente devolve o lançamento ao pool: a
        // reivindicação de quem estava com ele volta a ser pendente e disputa.
        if (l.responsavel != null) {
            Reivindicacao anterior = Reivindicacao.de(lancamentoId, l.responsavel.getId());
            if (anterior != null) {
                anterior.status = StatusReivindicacao.PENDENTE;
                anterior.persist();
            }
            atribuicao.encerrarCompromisso(l);
            // A divisão pertencia à atribuição contestada: quem estava na mesa é
            // parte do que se está discutindo. Fica para refazer depois da decisão.
            DivisaoLancamento.apagarDo(l.id);
            l.liberar();
            l.persist();
        }

        resolver(l, quem);
        conciliacao.recalcularAcertos(l.fatura.getId());
        auditoria.registrar(quem, AcaoAuditoria.REIVINDICAR, "Lancamento", l.id, l.descricao);
        return l;
    }

    @Transactional
    public Lancamento desistir(Long lancamentoId, Usuario quem) {
        Lancamento l = buscarReivindicavel(lancamentoId);

        Reivindicacao minha = Reivindicacao.de(lancamentoId, quem.id);
        if (minha != null) {
            minha.delete();
        }
        boolean souResponsavel = l.responsavel != null && l.responsavel.getId().equals(quem.id);
        boolean tenhoParte = DivisaoLancamento.count(
                "lancamento.id = ?1 and usuario.id = ?2", lancamentoId, quem.id) > 0;

        if (souResponsavel) {
            DivisaoLancamento.apagarDo(lancamentoId);
            atribuicao.encerrarCompromisso(l);
            l.liberar();
            l.persist();
        } else if (tenhoParte) {
            // Recusar uma parte derruba a divisão inteira, mas o lançamento
            // continua com quem o assumiu: a conta volta a ser dele por inteiro e
            // ele refaz a divisão com quem concorda. Ninguém carrega uma cobrança
            // que não aceitou, e ninguém perde o lançamento por decisão de outro.
            DivisaoLancamento.apagarDo(lancamentoId);
            notificacao.parteRecusada(l, quem);
        }

        // Se sobrou exatamente um pretendente, ele leva sem precisar do admin.
        resolver(l, null);
        conciliacao.recalcularAcertos(l.fatura.getId());
        auditoria.registrar(quem, AcaoAuditoria.DESISTIR, "Lancamento", l.id, l.descricao);
        return l;
    }

    /** Decisão do admin num conflito. Também serve para atribuir à força. */
    @Transactional
    public Lancamento arbitrar(Long lancamentoId, Long vencedorId, Usuario admin) {
        Lancamento l = Lancamento.findById(lancamentoId);
        if (l == null) {
            throw new WebApplicationException("Lançamento não encontrado.", 404);
        }
        if (!l.fatura.aberta()) {
            throw new WebApplicationException(
                    "A fatura não está mais em avaliação.", 409);
        }
        if (!l.tipo.reivindicavel()) {
            throw new WebApplicationException(
                    "Encargos são rateados entre todos que usaram o cartão — "
                    + "não há como atribuí-los a uma pessoa.", 409);
        }
        Usuario vencedor = Usuario.findById(vencedorId);
        if (vencedor == null || !vencedor.ativo) {
            throw new WebApplicationException("Utilizador inválido.", 400);
        }

        for (Reivindicacao r : Reivindicacao.<Reivindicacao>list("lancamento.id", lancamentoId)) {
            r.status = r.usuario.getId().equals(vencedorId)
                    ? StatusReivindicacao.ACEITA
                    : StatusReivindicacao.REJEITADA;
            r.resolvidoEm = LocalDateTime.now();
            r.resolvidoPor = admin;
            r.persist();
        }

        // A divisão anterior era de quem perdeu a disputa; quem ganhou refaz a sua.
        DivisaoLancamento.apagarDo(lancamentoId);
        l.atribuirA(vencedor, OrigemAtribuicao.ADMIN);
        l.persist();
        atribuicao.registrarCompromisso(l, vencedor);
        conciliacao.recalcularAcertos(l.fatura.getId());
        auditoria.registrar(admin, AcaoAuditoria.ARBITRAR, "Lancamento", l.id,
                "atribuído a " + vencedor.nome);
        return l;
    }

    /** Lançamentos da fatura com dois ou mais pretendentes: a fila do admin. */
    public List<Lancamento> conflitos(Long faturaId) {
        List<Long> ids = Reivindicacao.lancamentosEmConflito(faturaId);
        return ids.isEmpty() ? List.of() : Lancamento.list("id in ?1 order by dataCompra", ids);
    }

    // ------------------------------------------------------------ internos --

    /**
     * Um pretendente pendente → leva o lançamento. Dois ou mais → conflito, o
     * lançamento fica no pool e o admin é avisado.
     */
    private void resolver(Lancamento l, Usuario gatilho) {
        List<Reivindicacao> pendentes = Reivindicacao.pendentesDo(l.id);

        if (pendentes.size() == 1) {
            Reivindicacao unica = pendentes.get(0);
            unica.status = StatusReivindicacao.ACEITA;
            unica.resolvidoEm = LocalDateTime.now();
            unica.persist();

            l.atribuirA(unica.usuario, OrigemAtribuicao.MANUAL);
            l.persist();
            atribuicao.registrarCompromisso(l, unica.usuario);
            return;
        }

        if (pendentes.size() > 1) {
            LOG.infof("Conflito no lançamento %d ('%s'): %d pretendentes.",
                    l.id, l.descricao, pendentes.size());
            notificacao.conflito(l, pendentes.stream().map(r -> r.usuario).toList());
        }
    }

    private Lancamento buscarReivindicavel(Long id) {
        Lancamento l = Lancamento.findById(id);
        if (l == null) {
            throw new WebApplicationException("Lançamento não encontrado.", 404);
        }
        if (!l.fatura.aberta()) {
            throw new WebApplicationException(
                    "A fatura de " + l.fatura.competencia
                    + " não está em avaliação — fale com o administrador.", 409);
        }
        if (!l.tipo.reivindicavel()) {
            throw new WebApplicationException(
                    "Lançamentos do tipo " + l.tipo + " são do titular do cartão.", 409);
        }
        return l;
    }
}
