package br.com.jcard.bootstrap;

import br.com.jcard.model.Usuario;
import br.com.jcard.service.AuthService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Cria o administrador no primeiro boot — sem ele não há como entrar no app.
 * Nada de dados de exemplo: este app lida com dinheiro real desde o primeiro uso.
 */
@Singleton
public class DataInitializer {

    private static final Logger LOG = Logger.getLogger(DataInitializer.class);

    @Inject
    AuthService auth;

    @ConfigProperty(name = "jcard.admin.nome")
    String nome;

    @ConfigProperty(name = "jcard.admin.login")
    String login;

    @ConfigProperty(name = "jcard.admin.email")
    String email;

    @ConfigProperty(name = "jcard.admin.senha")
    String senha;

    @Transactional
    void aoIniciar(@Observes StartupEvent ev) {
        if (Usuario.count() > 0) {
            return;
        }
        Usuario admin = new Usuario();
        admin.nome = nome;
        admin.login = login;
        admin.email = email;
        admin.senhaHash = auth.hash(senha);
        admin.admin = true;
        // O titular também gasta no próprio cartão, então é utilizador também.
        admin.utilizador = true;
        admin.precisaTrocarSenha = true;
        admin.persist();

        LOG.infof("Administrador '%s' criado no primeiro boot. Troque a senha no 1º acesso.", login);
    }
}
