package br.com.jcard.service;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.common.annotation.Blocking;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Envio de e-mail fora do caminho da requisição.
 *
 * <p>Importar uma fatura dispara e-mail para todos os utilizadores. Enviar isso
 * em série dentro da transação faria o upload esperar o SMTP responder N vezes —
 * e prenderia a conexão do banco enquanto isso. Aqui o serviço só publica no
 * EventBus (retorno imediato) e o consumo acontece numa worker thread.
 */
@ApplicationScoped
public class EmailDispatcher {

    static final String ENDERECO = "email-out";

    private static final Logger LOG = Logger.getLogger(EmailDispatcher.class);

    @Inject
    EventBus bus;

    @Inject
    Mailer mailer;

    /** Enfileira para envio assíncrono. Não bloqueia quem chamou. */
    public void enfileirar(Mail mail) {
        bus.publish(ENDERECO, mail);
    }

    @ConsumeEvent(ENDERECO)
    @Blocking
    void enviar(Mail mail) {
        try {
            mailer.send(mail);
        } catch (Exception e) {
            LOG.warnf("Falha ao enviar e-mail para %s: %s", mail.getTo(), e.getMessage());
        }
    }
}
