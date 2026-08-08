package br.com.jcard.service;

import br.com.jcard.model.AcaoAuditoria;
import br.com.jcard.model.Usuario;
import io.quarkus.elytron.security.common.BcryptUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * Login, troca de senha e proteção contra força bruta.
 *
 * <p>O bloqueio é em memória de propósito: uma tabela a mais no banco não
 * compensa num app de poucos usuários, e reiniciar o container limpar o contador
 * é aceitável — o atacante não controla o restart.
 */
@ApplicationScoped
public class AuthService {

    private static final Logger LOG = Logger.getLogger(AuthService.class);
    private static final int MAX_TENTATIVAS = 5;
    private static final Duration JANELA = Duration.ofMinutes(15);
    private static final int SENHA_MINIMA = 8;

    private final Map<String, Tentativas> falhas = new ConcurrentHashMap<>();

    @Inject
    AuditoriaService auditoria;

    private record Tentativas(int quantidade, Instant desde) {
    }

    public Usuario autenticar(String login, String senha) {
        String chave = login == null ? "" : login.toLowerCase();
        verificarBloqueio(chave);

        Usuario u = Usuario.porLogin(chave);
        // Mensagem única para login inexistente e senha errada: não entregamos
        // quais logins existem.
        if (u == null || !u.ativo || !BcryptUtil.matches(senha, u.senhaHash)) {
            registrarFalha(chave);
            throw new WebApplicationException("Login ou senha inválidos.", 401);
        }

        falhas.remove(chave);
        auditoria.registrar(u, AcaoAuditoria.LOGIN, "Usuario", u.id, null);
        return u;
    }

    /**
     * Troca a senha e devolve o usuário já atualizado.
     *
     * <p>Recebe o <b>id</b>, não a entidade: quem chama tem o usuário resolvido
     * do JWT, fora de transação, e persistir um objeto destacado estoura em
     * runtime. Recarregar aqui dentro mantém a operação segura de qualquer origem.
     */
    @Transactional
    public Usuario trocarSenha(Long usuarioId, String senhaAtual, String senhaNova) {
        Usuario u = Usuario.findById(usuarioId);
        if (u == null || !u.ativo) {
            throw new WebApplicationException("Usuário inválido.", 401);
        }
        if (!BcryptUtil.matches(senhaAtual, u.senhaHash)) {
            throw new WebApplicationException("Senha atual incorreta.", 400);
        }
        validarForca(senhaNova);
        if (BcryptUtil.matches(senhaNova, u.senhaHash)) {
            throw new WebApplicationException("A nova senha precisa ser diferente da atual.", 400);
        }
        u.senhaHash = BcryptUtil.bcryptHash(senhaNova);
        u.precisaTrocarSenha = false;
        u.persist();
        auditoria.registrar(u, AcaoAuditoria.ATUALIZAR, "Usuario", u.id, "trocou a senha");
        return u;
    }

    public void validarForca(String senha) {
        if (senha == null || senha.length() < SENHA_MINIMA) {
            throw new WebApplicationException(
                    "A senha precisa ter pelo menos " + SENHA_MINIMA + " caracteres.", 400);
        }
    }

    public String hash(String senha) {
        return BcryptUtil.bcryptHash(senha);
    }

    // ------------------------------------------------------- força bruta --

    private void verificarBloqueio(String chave) {
        Tentativas t = falhas.get(chave);
        if (t == null) {
            return;
        }
        if (Duration.between(t.desde(), Instant.now()).compareTo(JANELA) > 0) {
            falhas.remove(chave);
            return;
        }
        if (t.quantidade() >= MAX_TENTATIVAS) {
            LOG.warnf("Login bloqueado por excesso de tentativas: %s", chave);
            throw new WebApplicationException(
                    "Muitas tentativas. Tente de novo em alguns minutos.", 429);
        }
    }

    private void registrarFalha(String chave) {
        falhas.compute(chave, (k, t) -> {
            if (t == null || Duration.between(t.desde(), Instant.now()).compareTo(JANELA) > 0) {
                return new Tentativas(1, Instant.now());
            }
            return new Tentativas(t.quantidade() + 1, t.desde());
        });
    }
}
