package br.com.jcard.security;

import br.com.jcard.model.Usuario;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Resolve o {@link Usuario} do JWT da requisição.
 *
 * <p>Sempre relê do banco: um token válido pode pertencer a alguém que foi
 * desativado depois da emissão, e num app de cobrança isso não pode passar.
 */
@RequestScoped
public class UsuarioLogado {

    @Inject
    JsonWebToken jwt;

    @Inject
    SecurityIdentity identity;

    private Usuario cache;

    public Usuario get() {
        if (cache != null) {
            return cache;
        }
        Object uid = jwt.getClaim("uid");
        if (uid == null) {
            throw new WebApplicationException("Sessão inválida.", 401);
        }
        Usuario u = Usuario.findById(Long.valueOf(uid.toString()));
        if (u == null || !u.ativo) {
            throw new WebApplicationException("Usuário inativo ou inexistente.", 401);
        }
        cache = u;
        return u;
    }

    public boolean isAdmin() {
        return identity.hasRole(TokenService.ADMIN);
    }

    /**
     * Barra operações de quem ainda está com a senha provisória — evita que a
     * conta fique usável indefinidamente com a senha padrão.
     */
    public Usuario exigirSenhaTrocada() {
        Usuario u = get();
        if (u.precisaTrocarSenha) {
            throw new WebApplicationException("Troque sua senha antes de continuar.", 403);
        }
        return u;
    }
}
