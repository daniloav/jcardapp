package br.com.jcard.resource;

import br.com.jcard.dto.Requests;
import br.com.jcard.dto.Responses;
import br.com.jcard.model.DivisaoLancamento;
import br.com.jcard.model.Lancamento;
import br.com.jcard.model.Usuario;
import br.com.jcard.security.TokenService;
import br.com.jcard.security.UsuarioLogado;
import br.com.jcard.service.ApelidoService;
import br.com.jcard.service.DivisaoService;
import br.com.jcard.service.ReivindicacaoService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Assumir, desistir e (para o admin) arbitrar um lançamento. */
@Path("/api/lancamentos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LancamentoResource {

    @Inject
    ReivindicacaoService servico;

    @Inject
    DivisaoService divisao;

    @Inject
    ApelidoService apelidos;

    @Inject
    UsuarioLogado logado;

    /** "Essa compra foi minha." */
    @POST
    @Path("/{id}/reivindicar")
    @Transactional
    public Responses.LancamentoResponse reivindicar(@PathParam("id") Long id,
                                                    Requests.Reivindicar req) {
        Usuario eu = logado.exigirSenhaTrocada();
        return Responses.LancamentoResponse.de(
                servico.reivindicar(id, eu, req == null ? null : req.observacao()), eu.id);
    }

    /** Desiste do lançamento; ele volta ao pool. */
    @DELETE
    @Path("/{id}/reivindicar")
    @Transactional
    public Responses.LancamentoResponse desistir(@PathParam("id") Long id) {
        Usuario eu = logado.exigirSenhaTrocada();
        return Responses.LancamentoResponse.de(servico.desistir(id, eu), eu.id);
    }

    /**
     * Racha o lançamento entre várias pessoas. Quem divide é quem assumiu — é
     * ele que sabe quem estava na mesa — ou o admin.
     */
    @POST
    @Path("/{id}/divisao")
    @Transactional
    public Responses.LancamentoResponse dividir(@PathParam("id") Long id,
                                                @Valid Requests.Dividir req) {
        Usuario eu = logado.exigirSenhaTrocada();
        Lancamento l = divisao.dividir(id, eu, logado.isAdmin(),
                req.partes().stream()
                        .map(p -> new DivisaoService.Parte(p.usuarioId(), p.valor()))
                        .toList());
        return Responses.LancamentoResponse.de(l, eu.id)
                .comDivisao(DivisaoLancamento.doLancamento(l.id), eu.id);
    }

    /** Desfaz a divisão: a conta volta inteira para quem a assumiu. */
    @DELETE
    @Path("/{id}/divisao")
    @Transactional
    public Responses.LancamentoResponse juntar(@PathParam("id") Long id) {
        Usuario eu = logado.exigirSenhaTrocada();
        return Responses.LancamentoResponse.de(
                divisao.juntar(id, eu, logado.isAdmin()), eu.id);
    }

    /**
     * Decisão do admin sobre quem fica com o lançamento.
     *
     * <p>Vale para o conflito e para o lançamento que ninguém reivindicou — nos
     * dois casos a pessoa é avisada por e-mail e pode contestar com "não foi
     * minha" enquanto a fatura estiver em avaliação.
     */
    @POST
    @Path("/{id}/arbitrar")
    @RolesAllowed(TokenService.ADMIN)
    @Transactional
    public Responses.LancamentoResponse arbitrar(@PathParam("id") Long id,
                                                 @Valid Requests.Arbitrar req) {
        Usuario admin = logado.get();
        return Responses.LancamentoResponse.de(
                servico.arbitrar(id, req.vencedorId(), admin), admin.id);
    }

    /**
     * A mesma decisão para vários lançamentos de uma vez — o resultado de uma
     * busca na conciliação indo para uma pessoa só.
     *
     * <p>Não fica sob {@code /{id}} porque a ação é sobre o conjunto. Encargos
     * que caírem na seleção são pulados em silêncio, e a resposta diz o que foi
     * feito e o que foi pulado.
     */
    @POST
    @Path("/arbitrar-lote")
    @RolesAllowed(TokenService.ADMIN)
    @Transactional
    public ReivindicacaoService.ResultadoLote arbitrarLote(@Valid Requests.ArbitrarLote req) {
        return servico.arbitrarEmLote(req.lancamentoIds(), req.vencedorId(), logado.get());
    }

    /**
     * Batiza o estabelecimento deste lançamento.
     *
     * <p>Qualquer utilizador apelida: quem reconhece a loja é quem comprou nela.
     * O apelido vale para todo mundo e para as próximas faturas.
     */
    @POST
    @Path("/{id}/apelido")
    @Transactional
    public Responses.ApelidoResponse apelidar(@PathParam("id") Long id,
                                              @Valid Requests.Apelidar req) {
        return Responses.ApelidoResponse.de(
                apelidos.apelidar(id, req.apelido(), logado.exigirSenhaTrocada()));
    }

    /** Volta a mostrar o nome que o banco imprime. */
    @DELETE
    @Path("/{id}/apelido")
    @Transactional
    public void removerApelido(@PathParam("id") Long id) {
        apelidos.remover(id, logado.exigirSenhaTrocada());
    }
}
