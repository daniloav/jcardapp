package br.com.jcard.resource;

import br.com.jcard.dto.Requests;
import br.com.jcard.dto.Responses;
import br.com.jcard.model.Usuario;
import br.com.jcard.security.TokenService;
import br.com.jcard.security.UsuarioLogado;
import br.com.jcard.service.AuthService;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.JsonWebToken;

/** Login, sessão e troca de senha. */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

    @Inject
    AuthService auth;

    @Inject
    TokenService tokens;

    @Inject
    UsuarioLogado logado;

    @POST
    @Path("/auth/login")
    @PermitAll
    @Transactional
    public Responses.Login login(@Valid Requests.Login req) {
        Usuario u = auth.autenticar(req.login(), req.senha());
        return Responses.Login.de(tokens.gerar(u), u);
    }

    /** Quem sou eu — o front usa para montar o menu conforme os papéis. */
    @GET
    @Path("/me")
    public Responses.Usuario eu() {
        return Responses.Usuario.de(logado.get());
    }

    @PUT
    @Path("/me/senha")
    public Responses.Login trocarSenha(@Valid Requests.TrocarSenha req) {
        Usuario u = logado.get();
        auth.trocarSenha(u, req.senhaAtual(), req.senhaNova());
        // Token novo: o antigo carrega trocarSenha=true e barraria o usuário.
        return Responses.Login.de(tokens.gerar(u), u);
    }
}
