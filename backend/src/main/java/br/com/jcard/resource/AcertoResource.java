package br.com.jcard.resource;

import br.com.jcard.dto.Responses;
import br.com.jcard.model.Acerto;
import br.com.jcard.security.TokenService;
import br.com.jcard.security.UsuarioLogado;
import br.com.jcard.service.AcertoService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/** Quitação: o histórico do utilizador e a confirmação pelo admin. */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class AcertoResource {

    @Inject
    AcertoService servico;

    @Inject
    UsuarioLogado logado;

    /** Histórico do próprio utilizador: quanto deveu e o que já foi confirmado. */
    @GET
    @Path("/me/acertos")
    public List<Responses.AcertoResponse> meus() {
        return Acerto.doUsuario(logado.exigirSenhaTrocada().id).stream()
                .map(Responses.AcertoResponse::de).toList();
    }

    @POST
    @Path("/acertos/{id}/confirmar")
    @RolesAllowed(TokenService.ADMIN)
    public Responses.AcertoResponse confirmar(@PathParam("id") Long id) {
        return Responses.AcertoResponse.de(servico.confirmarPagamento(id, logado.get()));
    }

    @POST
    @Path("/acertos/{id}/reabrir")
    @RolesAllowed(TokenService.ADMIN)
    public Responses.AcertoResponse reabrir(@PathParam("id") Long id) {
        return Responses.AcertoResponse.de(servico.reabrir(id, logado.get()));
    }
}
