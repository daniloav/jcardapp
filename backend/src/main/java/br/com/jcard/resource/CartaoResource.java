package br.com.jcard.resource;

import br.com.jcard.dto.Requests;
import br.com.jcard.dto.Responses;
import br.com.jcard.model.Cartao;
import br.com.jcard.model.Usuario;
import br.com.jcard.security.TokenService;
import jakarta.annotation.security.RolesAllowed;
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
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * Cartões do titular e adicionais.
 *
 * <p>Configurar o <b>dono padrão</b> de um adicional é o que faz os lançamentos
 * daquele cartão já nascerem atribuídos — poupa o utilizador de reivindicar tudo
 * todo mês. E o cartão marcado como <b>titular</b> é quem absorve a sobra.
 */
@Path("/api/cartoes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(TokenService.ADMIN)
public class CartaoResource {

    @GET
    public List<Responses.CartaoResponse> listar() {
        return Cartao.<Cartao>listAll(io.quarkus.panache.common.Sort.by("apelido")).stream()
                .map(Responses.CartaoResponse::de).toList();
    }

    @POST
    @Transactional
    public Responses.CartaoResponse criar(@Valid Requests.Cartao req) {
        if (Cartao.porFinal(req.final4()) != null) {
            throw new WebApplicationException("Já existe cartão com esse final.", 409);
        }
        Cartao c = new Cartao();
        aplicar(c, req);
        c.persist();
        return Responses.CartaoResponse.de(c);
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Responses.CartaoResponse atualizar(@PathParam("id") Long id, @Valid Requests.Cartao req) {
        Cartao c = Cartao.findById(id);
        if (c == null) {
            throw new WebApplicationException("Cartão não encontrado.", 404);
        }
        Cartao mesmoFinal = Cartao.porFinal(req.final4());
        if (mesmoFinal != null && !mesmoFinal.id.equals(id)) {
            throw new WebApplicationException("Já existe cartão com esse final.", 409);
        }
        aplicar(c, req);
        c.persist();
        return Responses.CartaoResponse.de(c);
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public void remover(@PathParam("id") Long id) {
        Cartao c = Cartao.findById(id);
        if (c == null) {
            throw new WebApplicationException("Cartão não encontrado.", 404);
        }
        c.delete();
    }

    private void aplicar(Cartao c, Requests.Cartao req) {
        c.apelido = req.apelido().strip();
        c.final4 = req.final4().strip();
        c.portadorNome = req.portadorNome();
        c.donoPadrao = req.donoPadraoId() == null ? null : Usuario.findById(req.donoPadraoId());
        c.ativo = req.ativo();

        // Só um cartão pode ser o titular: dois donos da sobra tornaria a
        // conciliação ambígua.
        if (req.titular()) {
            Cartao.update("titular = false where titular = true");
        }
        c.titular = req.titular();
    }
}
