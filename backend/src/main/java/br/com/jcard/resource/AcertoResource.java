package br.com.jcard.resource;

import br.com.jcard.dto.Responses;
import br.com.jcard.model.Acerto;
import br.com.jcard.model.ComprovantePagamento;
import br.com.jcard.security.TokenService;
import br.com.jcard.security.UsuarioLogado;
import br.com.jcard.service.AcertoService;
import br.com.jcard.service.PixConfig;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

/** Quitação: o histórico do utilizador e a confirmação pelo admin. */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class AcertoResource {

    @Inject
    AcertoService servico;

    @Inject
    PixConfig pixConfig;

    @Inject
    UsuarioLogado logado;

    /** Histórico do próprio utilizador: quanto deveu e o que já foi confirmado. */
    @GET
    @Path("/me/acertos")
    @Transactional
    public List<Responses.AcertoResponse> meus() {
        return Acerto.doUsuario(logado.exigirSenhaTrocada().id).stream()
                .map(Responses.AcertoResponse::de).toList();
    }

    /** A chave para onde o acerto é pago — a tela mostra com botão de copiar. */
    @GET
    @Path("/pix")
    public Responses.PixResponse pix() {
        logado.exigirSenhaTrocada();
        return pixConfig.atual();
    }

    /**
     * O comprovante de <b>uma</b> transferência. Sai como o arquivo original,
     * {@code inline}, para o admin conferir sem baixar nada.
     *
     * <p>Só o dono do acerto e o admin passam: é documento bancário de outra
     * pessoa, e o resto do app já esconde as contas alheias.
     */
    @GET
    @Path("/pagamentos/{id}/comprovante")
    @Produces(MediaType.WILDCARD)
    @Transactional
    public Response comprovante(@PathParam("id") Long id) {
        ComprovantePagamento c = servico.comprovante(
                id, logado.exigirSenhaTrocada(), logado.isAdmin());
        return Response.ok(c.conteudo, c.tipo)
                .header("Content-Disposition", "inline; filename=\"" + c.nome + "\"")
                // Comprovante é dado financeiro: não pode sobrar em cache de proxy.
                .header("Cache-Control", "private, no-store")
                .build();
    }

    /**
     * O admin dá por recebida uma transferência.
     *
     * <p>Uma de cada vez: é assim que ele confere no extrato, e o acerto só
     * fecha quando todas estão confirmadas e não sobra saldo.
     */
    @POST
    @Path("/pagamentos/{id}/confirmar")
    @RolesAllowed(TokenService.ADMIN)
    @Transactional
    public Responses.AcertoResponse confirmar(@PathParam("id") Long id) {
        return Responses.AcertoResponse.de(
                servico.confirmarPagamento(id, logado.get()).acerto);
    }

    @POST
    @Path("/acertos/{id}/reabrir")
    @RolesAllowed(TokenService.ADMIN)
    @Transactional
    public Responses.AcertoResponse reabrir(@PathParam("id") Long id) {
        return Responses.AcertoResponse.de(servico.reabrir(id, logado.get()));
    }
}
