package br.com.jcard.resource;

import br.com.jcard.dto.Responses;
import br.com.jcard.dto.UsuarioRequest;
import br.com.jcard.model.Usuario;
import br.com.jcard.security.TokenService;
import br.com.jcard.security.UsuarioLogado;
import br.com.jcard.service.UsuarioService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/** CRUD de utilizadores. Só o admin cadastra — não há auto-cadastro. */
@Path("/api/usuarios")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(TokenService.ADMIN)
public class UsuarioResource {

    @Inject
    UsuarioService servico;

    @Inject
    UsuarioLogado logado;

    @GET
    @Transactional
    public List<Responses.Usuario> listar() {
        return servico.listar().stream().map(Responses.Usuario::de).toList();
    }

    @POST
    @Transactional
    public Responses.Usuario criar(@Valid UsuarioRequest req) {
        return Responses.Usuario.de(servico.criar(req, logado.get()));
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Responses.Usuario atualizar(@PathParam("id") Long id, @Valid UsuarioRequest req) {
        return Responses.Usuario.de(servico.atualizar(id, req, logado.get()));
    }

    @DELETE
    @Path("/{id}")
    public void remover(@PathParam("id") Long id) {
        servico.remover(id, logado.get());
    }

    @POST
    @Path("/{id}/resetar-senha")
    public void resetarSenha(@PathParam("id") Long id) {
        servico.resetarSenha(id, logado.get());
    }

    /** Lista enxuta para os seletores de arbitragem e dono padrão de cartão. */
    @GET
    @Path("/utilizadores")
    @Transactional
    public List<Responses.Usuario> utilizadores() {
        return Usuario.utilizadoresAtivos().stream().map(Responses.Usuario::de).toList();
    }
}
