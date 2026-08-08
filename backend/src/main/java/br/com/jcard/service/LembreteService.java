package br.com.jcard.service;

import br.com.jcard.model.Fatura;
import br.com.jcard.model.Lancamento;
import br.com.jcard.model.StatusFatura;
import br.com.jcard.model.Usuario;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Cutuca os utilizadores quando ainda há lançamento sem dono numa fatura aberta.
 *
 * <p>Sem isso a fatura fica esperando indefinidamente por quem esqueceu de
 * avaliar, e o titular acaba absorvendo conta que não é dele.
 */
@ApplicationScoped
public class LembreteService {

    private static final Logger LOG = Logger.getLogger(LembreteService.class);

    @Inject
    NotificacaoService notificacao;

    @Scheduled(cron = "{jcard.lembrete.cron}", timeZone = "America/Sao_Paulo",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void lembrarPendencias() {
        List<Fatura> abertas = Fatura.list("status", StatusFatura.EM_AVALIACAO);
        for (Fatura f : abertas) {
            int pool = Lancamento.poolDaFatura(f.id).size();
            if (pool == 0) {
                continue;
            }
            LOG.infof("Lembrete: fatura %s com %d lançamento(s) sem dono.", f.competencia, pool);
            for (Usuario u : Usuario.utilizadoresAtivos()) {
                notificacao.lembretePendencia(u, f, pool);
            }
        }
    }
}
