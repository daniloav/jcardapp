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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

    /**
     * Decisão do admin sobre quem fica com o lançamento.
     *
     * <p>Serve para os dois casos: desempatar uma disputa e apontar o dono de um
     * lançamento que ninguém reivindicou ("isso foi do João"). Sem o segundo, a
     * única saída para o lançamento parado no pool era conciliar e deixá-lo cair
     * no titular.
     *
     * <p>Nos dois casos a pessoa é <b>avisada por e-mail</b> e pode contestar com
     * "não foi minha" enquanto a fatura estiver em avaliação. Atribuir sem
     * disputa não é um desempate: é o admin dizendo de quem é a conta, e a regra
     * §3.3 — "quem chegou primeiro nunca decide uma cobrança contestada" — vale
     * igual para "o admin decidiu".
     */
    @Transactional
    public Lancamento arbitrar(Long lancamentoId, Long vencedorId, Usuario admin) {
        Lancamento l = Lancamento.findById(lancamentoId);
        if (l == null) {
            throw new WebApplicationException("Lançamento não encontrado.", 404);
        }
        exigirAvaliacaoAberta(l);
        if (!l.tipo.reivindicavel()) {
            throw new WebApplicationException(
                    "Encargos são rateados entre todos que usaram o cartão — "
                    + "não há como atribuí-los a uma pessoa.", 409);
        }
        Usuario vencedor = exigirVencedor(vencedorId);

        int disputantes = aplicarArbitragem(l, vencedor, admin);
        conciliacao.recalcularAcertos(l.fatura.getId());
        notificacao.atribuidoPeloAdmin(l, vencedor, disputantes > 1);
        auditoria.registrar(admin, AcaoAuditoria.ARBITRAR, "Lancamento", l.id,
                "atribuído a " + vencedor.nome
                + (disputantes > 1 ? " (disputa entre " + disputantes + ")" : " (sem disputa)"));
        return l;
    }

    /**
     * A mesma decisão do {@link #arbitrar}, aplicada de uma vez a vários
     * lançamentos — o resultado de uma busca na tela de conciliação.
     *
     * <p>Existe porque a alternativa é clicar 40 vezes: filtrar por "UBER" e
     * dizer "isso tudo é da Maria" é uma decisão só, e repeti-la linha a linha
     * só aumenta a chance de o admin errar uma delas.
     *
     * <p>Três cuidados que o caminho de um lançamento não precisa ter:
     * <ul>
     *   <li><b>Um e-mail, não N.</b> Quarenta avisos separados sobre a mesma
     *       decisão seriam ignorados — e é justamente o aviso que torna a
     *       atribuição contestável.</li>
     *   <li><b>Um recálculo, não N.</b> Com o banco no Neon, recalcular os
     *       acertos a cada linha multiplicaria a latência de rede sem mudar o
     *       resultado: só o estado final importa.</li>
     *   <li><b>Encargo não entra.</b> Ele é de todo mundo que usou o cartão, e
     *       o lote pula em silêncio em vez de falhar inteiro — quem filtrou por
     *       texto não escolheu o encargo, ele só caiu na busca.</li>
     * </ul>
     */
    @Transactional
    public ResultadoLote arbitrarEmLote(List<Long> lancamentoIds, Long vencedorId, Usuario admin) {
        if (lancamentoIds == null || lancamentoIds.isEmpty()) {
            throw new WebApplicationException("Nenhum lançamento selecionado.", 400);
        }
        Usuario vencedor = exigirVencedor(vencedorId);

        List<Long> ids = lancamentoIds.stream().distinct().toList();
        List<Lancamento> lancamentos = Lancamento.list("id in ?1 order by dataCompra", ids);
        if (lancamentos.isEmpty()) {
            throw new WebApplicationException("Lançamento não encontrado.", 404);
        }
        // Uma fatura por vez: o recálculo dos acertos é por fatura, e misturar
        // competências numa ação só esconderia do admin o que ele está mexendo.
        if (lancamentos.stream().map(l -> l.fatura.getId()).distinct().count() > 1) {
            throw new WebApplicationException(
                    "A atribuição em massa é de uma fatura por vez.", 400);
        }
        exigirAvaliacaoAberta(lancamentos.get(0));

        List<Lancamento> atribuidos = new ArrayList<>();
        BigDecimal valor = BigDecimal.ZERO;
        int encargos = 0;
        int jaEram = 0;

        for (Lancamento l : lancamentos) {
            if (!l.tipo.reivindicavel()) {
                encargos++;
                continue;
            }
            // Já é dele e inteiro: refazer não muda nada e só geraria linha de
            // auditoria e mais uma reivindicação resolvida.
            if (l.responsavel != null && l.responsavel.getId().equals(vencedorId)
                    && DivisaoLancamento.count("lancamento.id", l.id) == 0) {
                jaEram++;
                continue;
            }
            aplicarArbitragem(l, vencedor, admin);
            atribuidos.add(l);
            valor = valor.add(l.valor);
        }

        if (!atribuidos.isEmpty()) {
            Long faturaId = atribuidos.get(0).fatura.getId();
            conciliacao.recalcularAcertos(faturaId);
            if (atribuidos.size() == 1) {
                notificacao.atribuidoPeloAdmin(atribuidos.get(0), vencedor, false);
            } else {
                notificacao.atribuidosEmLotePeloAdmin(atribuidos, vencedor, valor);
            }
            auditoria.registrar(admin, AcaoAuditoria.ARBITRAR, "Fatura", faturaId,
                    atribuidos.size() + " lançamento(s) atribuídos a " + vencedor.nome
                    + " em massa, somando R$ " + valor.toPlainString());
        }

        LOG.infof("Atribuição em massa para %s: %d atribuídos, %d já eram dele, %d encargo(s).",
                vencedor.nome, atribuidos.size(), jaEram, encargos);
        return new ResultadoLote(atribuidos.size(), valor, jaEram, encargos, vencedor.nome);
    }

    /**
     * O que a atribuição em massa fez, para a tela poder dizê-lo em vez de só
     * recarregar: o que foi pulado é a parte que o admin não vê acontecer.
     *
     * @param atribuidos quantos lançamentos mudaram de dono
     * @param valor      quanto eles somam
     * @param jaEram     quantos já eram da pessoa e ficaram como estavam
     * @param encargos   quantos caíram na busca mas são rateados entre todos
     */
    public record ResultadoLote(int atribuidos, BigDecimal valor, int jaEram,
                                int encargos, String usuarioNome) {
    }

    /** Lançamentos da fatura com dois ou mais pretendentes: a fila do admin. */
    public List<Lancamento> conflitos(Long faturaId) {
        List<Long> ids = Reivindicacao.lancamentosEmConflito(faturaId);
        return ids.isEmpty() ? List.of() : Lancamento.list("id in ?1 order by dataCompra", ids);
    }

    // ------------------------------------------------------------ internos --

    /**
     * Põe o lançamento no nome do vencedor e fecha a disputa, sem recalcular
     * acertos nem avisar ninguém — isso é do chamador, que sabe se está
     * decidindo um lançamento ou quarenta.
     *
     * @return quantos pretendentes havia, para o e-mail distinguir desempate de
     *         indicação
     */
    private int aplicarArbitragem(Lancamento l, Usuario vencedor, Usuario admin) {
        List<Reivindicacao> disputa = Reivindicacao.list("lancamento.id", l.id);
        for (Reivindicacao r : disputa) {
            r.status = r.usuario.getId().equals(vencedor.id)
                    ? StatusReivindicacao.ACEITA
                    : StatusReivindicacao.REJEITADA;
            r.resolvidoEm = LocalDateTime.now();
            r.resolvidoPor = admin;
            r.persist();
        }

        // A divisão anterior era de quem perdeu a disputa; quem ganhou refaz a sua.
        DivisaoLancamento.apagarDo(l.id);
        l.atribuirA(vencedor, OrigemAtribuicao.ADMIN);
        l.persist();
        atribuicao.registrarCompromisso(l, vencedor);
        return disputa.size();
    }

    private Usuario exigirVencedor(Long vencedorId) {
        Usuario vencedor = Usuario.findById(vencedorId);
        if (vencedor == null || !vencedor.ativo) {
            throw new WebApplicationException("Utilizador inválido.", 400);
        }
        return vencedor;
    }

    private void exigirAvaliacaoAberta(Lancamento l) {
        if (!l.fatura.aberta()) {
            throw new WebApplicationException("A fatura não está mais em avaliação.", 409);
        }
    }

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
