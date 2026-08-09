package br.com.jcard.service;

import br.com.jcard.model.Acerto;
import br.com.jcard.model.Cartao;
import br.com.jcard.model.Fatura;
import br.com.jcard.model.Lancamento;
import br.com.jcard.model.OrigemAtribuicao;
import br.com.jcard.model.StatusAcerto;
import br.com.jcard.model.StatusFatura;
import br.com.jcard.model.Usuario;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * O guardião da regra "as contas sempre batem".
 *
 * <p>Duas invariantes, checadas aqui e cobertas por teste:
 * <pre>
 *   (1) Σ lancamento.valor                        == fatura.valorTotal
 *   (2) Σ acerto.valorDevido                      == fatura.valorTotal
 * </pre>
 *
 * <p>A (1) valida a <b>leitura</b> do PDF: se não bate, o parser perdeu ou
 * duplicou linha e a fatura vai para {@link StatusFatura#DIVERGENTE} — melhor
 * travar do que ratear em cima de dado errado. A (2) valida o <b>rateio</b>: o
 * que ninguém reivindicou é do titular, e tanto a conta dividida quanto o
 * encargo rateado distribuem o valor cheio, sem perder centavo — então a soma
 * sempre reproduz o total. Quem monta esse rateio é o {@link RateioService}.
 */
@ApplicationScoped
public class ConciliacaoService {

    private static final Logger LOG = Logger.getLogger(ConciliacaoService.class);

    @Inject
    AuditoriaService auditoria;

    @Inject
    RateioService rateio;

    /**
     * Recalcula {@code valorLancado} e decide se a fatura pode seguir.
     * Idempotente: pode ser chamado a cada mudança.
     */
    @Transactional
    public Fatura validarLeitura(Long faturaId) {
        Fatura fatura = carregar(faturaId);
        BigDecimal soma = somaLancamentos(fatura.id);
        fatura.valorLancado = soma;

        if (fatura.valorTotal.compareTo(soma) != 0) {
            fatura.status = StatusFatura.DIVERGENTE;
            LOG.warnf("Fatura %d DIVERGENTE: total impresso %s, lido %s (diferença %s).",
                    fatura.id, fatura.valorTotal, soma, fatura.divergencia());
        } else if (fatura.status == StatusFatura.IMPORTADA
                || fatura.status == StatusFatura.DIVERGENTE) {
            fatura.status = StatusFatura.EM_AVALIACAO;
        }
        fatura.persist();
        return fatura;
    }

    /**
     * Reprojeta os acertos a partir do rateio.
     *
     * <p>Roda a cada reivindicação para que o utilizador veja o "quanto devo"
     * atualizado antes do fechamento. A sobra sem dono é sempre do titular, e os
     * encargos são divididos entre quem usou o cartão — ver {@link RateioService}.
     */
    @Transactional
    public List<Acerto> recalcularAcertos(Long faturaId) {
        Fatura fatura = carregar(faturaId);
        Usuario titular = titular();

        Map<Long, BigDecimal> porUsuario = rateio.totaisPorUsuario(fatura, titular);

        List<Acerto> acertos = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> e : porUsuario.entrySet()) {
            Acerto a = Acerto.de(fatura.id, e.getKey());
            if (a == null) {
                a = new Acerto();
                a.fatura = fatura;
                a.usuario = Usuario.findById(e.getKey());
            }
            // Não mexe em quem já pagou: reabrir um acerto confirmado apagaria a
            // confirmação do admin. Divergência aqui é caso de arbitragem manual.
            if (a.status != StatusAcerto.CONFIRMADO) {
                boolean mudou = a.valorDevido.compareTo(e.getValue()) != 0;
                a.valorDevido = e.getValue();
                // Aceitar é concordar com UM valor. Se ele mudou (o admin arbitrou,
                // alguém dividiu uma conta), o aceite anterior não vale mais e a
                // pessoa precisa conferir de novo antes de pagar. Quem já declarou
                // o pagamento fica como está: o dinheiro saiu, e a diferença é
                // assunto do admin, não motivo para apagar o comprovante.
                if (mudou && a.status == StatusAcerto.ACEITO) {
                    a.status = StatusAcerto.ABERTO;
                    a.aceitoEm = null;
                }
            }
            a.persist();
            acertos.add(a);
        }

        // Quem deixou de ter lançamento nesta fatura não deve mais nada.
        for (Acerto antigo : Acerto.daFatura(fatura.id)) {
            if (!porUsuario.containsKey(antigo.usuario.getId())
                    && antigo.status != StatusAcerto.CONFIRMADO) {
                antigo.delete();
            }
        }
        return acertos;
    }

    /**
     * Fecha o rateio: joga a sobra no titular, grava os acertos e valida a
     * invariante (2). Só o admin chama.
     */
    @Transactional
    public Fatura conciliar(Long faturaId, Usuario porQuem) {
        Fatura fatura = carregar(faturaId);
        if (fatura.status == StatusFatura.DIVERGENTE) {
            throw new WebApplicationException(
                    "A fatura está divergente (diferença de R$ " + fatura.divergencia()
                    + "). Corrija a leitura antes de conciliar.", 409);
        }
        if (fatura.status == StatusFatura.FECHADA) {
            throw new WebApplicationException("Fatura já fechada.", 409);
        }

        Usuario titular = titular();
        // Só o que era reivindicável e ninguém quis. Encargo NÃO entra aqui:
        // ele não tem dono, é rateado entre quem usou o cartão — deixá-lo sem
        // responsável é justamente o que mantém o rateio valendo.
        List<Lancamento> orfaos = Lancamento.semResponsavel(fatura.id);
        for (Lancamento l : orfaos) {
            l.atribuirA(titular, OrigemAtribuicao.ADMIN);
            l.persist();
        }

        List<Acerto> acertos = recalcularAcertos(fatura.id);

        BigDecimal somaAcertos = acertos.stream()
                .map(a -> a.valorDevido)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (somaAcertos.compareTo(fatura.valorTotal) != 0) {
            // Invariante violada: não deve acontecer. Se acontecer, abortar a
            // transação é o único desfecho seguro — cobrar valor errado é pior.
            throw new WebApplicationException(
                    "Erro de conciliação: os acertos somam R$ " + somaAcertos
                    + " mas a fatura é R$ " + fatura.valorTotal + ". Nada foi gravado.", 500);
        }

        fatura.status = StatusFatura.CONCILIADA;
        fatura.persist();
        auditoria.registrar(porQuem, br.com.jcard.model.AcaoAuditoria.CONCILIAR, "Fatura", fatura.id,
                orfaos.size() + " lançamento(s) sem dono atribuídos ao titular");
        return fatura;
    }

    /** Encerra a fatura quando todo mundo pagou e o admin confirmou. */
    @Transactional
    public Fatura fechar(Long faturaId, Usuario porQuem) {
        Fatura fatura = carregar(faturaId);
        if (fatura.status != StatusFatura.CONCILIADA) {
            throw new WebApplicationException("Concilie a fatura antes de fechar.", 409);
        }
        List<Acerto> pendentes = Acerto.list(
                "fatura.id = ?1 and status <> ?2", fatura.id, StatusAcerto.CONFIRMADO);
        if (!pendentes.isEmpty()) {
            throw new WebApplicationException(
                    pendentes.size() + " acerto(s) ainda não confirmados.", 409);
        }
        fatura.status = StatusFatura.FECHADA;
        fatura.fechadaEm = LocalDateTime.now();
        fatura.persist();
        auditoria.registrar(porQuem, br.com.jcard.model.AcaoAuditoria.FECHAR_FATURA,
                "Fatura", fatura.id, null);
        return fatura;
    }

    // ------------------------------------------------------------- apoio --

    /**
     * Recarrega a fatura dentro da transação corrente. Receber o id em vez da
     * entidade evita que um objeto destacado (vindo de outra transação) chegue
     * até um {@code persist()} — que falha em runtime.
     */
    private Fatura carregar(Long faturaId) {
        Fatura f = Fatura.findById(faturaId);
        if (f == null) {
            throw new WebApplicationException("Fatura não encontrada.", 404);
        }
        return f;
    }

    public BigDecimal somaLancamentos(Long faturaId) {
        BigDecimal s = Lancamento.find("fatura.id", faturaId).stream()
                .map(l -> ((Lancamento) l).valor)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return s.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Quem absorve a sobra: o dono do cartão titular; na falta dele, o primeiro
     * admin. O app não funciona sem alguém nesse papel.
     */
    public Usuario titular() {
        Cartao c = Cartao.find("titular = true and ativo = true").firstResult();
        if (c != null && c.donoPadrao != null) {
            return c.donoPadrao;
        }
        Usuario admin = Usuario.find("admin = true and ativo = true order by id").firstResult();
        if (admin == null) {
            throw new WebApplicationException(
                    "Nenhum titular definido: marque um cartão como titular ou mantenha um admin ativo.", 500);
        }
        return admin;
    }
}
