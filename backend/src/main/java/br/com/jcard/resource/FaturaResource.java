package br.com.jcard.resource;

import br.com.jcard.dto.Requests;
import br.com.jcard.dto.Responses;
import br.com.jcard.model.Acerto;
import br.com.jcard.model.Fatura;
import br.com.jcard.model.Lancamento;
import br.com.jcard.model.Reivindicacao;
import br.com.jcard.model.Usuario;
import br.com.jcard.security.TokenService;
import br.com.jcard.security.UsuarioLogado;
import br.com.jcard.service.AcertoService;
import br.com.jcard.service.ConciliacaoService;
import br.com.jcard.service.FaturaImportService;
import br.com.jcard.service.ReivindicacaoService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

/**
 * Faturas: importação, acompanhamento, conciliação e a visão do utilizador.
 *
 * <p>Os métodos são {@code @Transactional} porque montam DTO a partir de
 * entidade: sem a sessão aberta durante o mapeamento, tocar uma associação lazy
 * (por exemplo {@code acerto.fatura.competencia}) estoura com
 * {@code LazyInitializationException} depois que a transação do serviço fechou.
 */
@Path("/api/faturas")
@Produces(MediaType.APPLICATION_JSON)
public class FaturaResource {

    @Inject
    FaturaImportService importacao;

    @Inject
    ConciliacaoService conciliacao;

    @Inject
    ReivindicacaoService reivindicacoes;

    @Inject
    AcertoService acertos;

    @Inject
    UsuarioLogado logado;

    // ---------------------------------------------------------- importação --

    /**
     * Sobe o PDF da fatura.
     *
     * @param competencia mês de referência {@code AAAA-MM}
     * @param valorTotal  opcional: sobrepõe o total lido, para quando o layout
     *                    esconde o total mas os lançamentos saem certos
     */
    @POST
    @RolesAllowed(TokenService.ADMIN)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Transactional
    public Responses.FaturaResponse importar(@RestForm("arquivo") FileUpload arquivo,
                                             @RestForm String competencia,
                                             @RestForm String valorTotal) {
        if (arquivo == null) {
            throw new WebApplicationException("Envie o PDF da fatura.", 400);
        }
        byte[] conteudo;
        try {
            conteudo = Files.readAllBytes(arquivo.uploadedFile());
        } catch (IOException e) {
            throw new WebApplicationException("Falha ao ler o arquivo enviado.", 400);
        }

        LocalDate mes = mesDe(competencia);
        BigDecimal total = (valorTotal == null || valorTotal.isBlank())
                ? null : new BigDecimal(valorTotal.replace(".", "").replace(',', '.'));

        Fatura f = importacao.importar(conteudo, arquivo.fileName(), mes, total,
                logado.exigirSenhaTrocada());
        return resumo(f);
    }

    @POST
    @Path("/{id}/reprocessar")
    @RolesAllowed(TokenService.ADMIN)
    @Transactional
    public Responses.FaturaResponse reprocessar(@PathParam("id") Long id) {
        return resumo(importacao.reprocessar(buscar(id), logado.get()));
    }

    // ------------------------------------------------------------- consulta --

    @GET
    @Transactional
    public List<Responses.FaturaResponse> listar() {
        logado.exigirSenhaTrocada();
        return Fatura.recentes().stream().map(this::resumo).toList();
    }

    /** Visão completa da fatura — só o admin vê todos os lançamentos com dono. */
    @GET
    @Path("/{id}")
    @RolesAllowed(TokenService.ADMIN)
    @Transactional
    public Map<String, Object> detalhe(@PathParam("id") Long id) {
        Fatura f = buscar(id);
        Usuario eu = logado.get();
        return Map.of(
                "fatura", resumo(f),
                "lancamentos", Lancamento.daFatura(id).stream()
                        .map(l -> Responses.LancamentoResponse.de(l, eu.id)).toList(),
                "acertos", Acerto.daFatura(id).stream()
                        .map(Responses.AcertoResponse::de).toList());
    }

    /**
     * A tela do utilizador: o que está sem dono e o que já é dele.
     *
     * <p>Não devolve o que outra pessoa assumiu — é decisão de privacidade: cada
     * um vê as próprias contas e o pool, nada além.
     */
    @GET
    @Path("/{id}/minhas-contas")
    @Transactional
    public Responses.MinhasContas minhasContas(@PathParam("id") Long id) {
        Fatura f = buscar(id);
        Usuario eu = logado.exigirSenhaTrocada();

        List<Responses.LancamentoResponse> pool = Lancamento.poolDaFatura(id).stream()
                .map(l -> Responses.LancamentoResponse.de(l, eu.id).anonimo())
                .toList();
        List<Lancamento> meus = Lancamento.deUsuarioNaFatura(id, eu.id);
        BigDecimal total = meus.stream()
                .map(l -> l.valor)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Acerto a = Acerto.de(id, eu.id);
        return new Responses.MinhasContas(
                resumo(f),
                pool,
                meus.stream().map(l -> Responses.LancamentoResponse.de(l, eu.id)).toList(),
                total,
                a == null ? null : Responses.AcertoResponse.de(a));
    }

    /** Fila de arbitragem: lançamentos com dois ou mais pretendentes. */
    @GET
    @Path("/{id}/conflitos")
    @RolesAllowed(TokenService.ADMIN)
    @Transactional
    public List<Responses.LancamentoResponse> conflitos(@PathParam("id") Long id) {
        Usuario eu = logado.get();
        return reivindicacoes.conflitos(id).stream()
                .map(l -> Responses.LancamentoResponse.de(l, eu.id)
                        .comDisputantes(Reivindicacao.pendentesDo(l.id).stream()
                                .map(r -> r.usuario.nome).toList()))
                .toList();
    }

    @GET
    @Path("/{id}/acertos")
    @RolesAllowed(TokenService.ADMIN)
    @Transactional
    public List<Responses.AcertoResponse> acertos(@PathParam("id") Long id) {
        return Acerto.daFatura(id).stream().map(Responses.AcertoResponse::de).toList();
    }

    // ------------------------------------------------------------ fechamento --

    @POST
    @Path("/{id}/conciliar")
    @RolesAllowed(TokenService.ADMIN)
    @Transactional
    public Responses.FaturaResponse conciliar(@PathParam("id") Long id) {
        return resumo(conciliacao.conciliar(id, logado.get()));
    }

    @POST
    @Path("/{id}/fechar")
    @RolesAllowed(TokenService.ADMIN)
    @Transactional
    public Responses.FaturaResponse fechar(@PathParam("id") Long id) {
        return resumo(conciliacao.fechar(id, logado.get()));
    }

    // ------------------------------------------------------------- pagamento --

    /** O utilizador declara que pagou a parte dele nesta fatura. */
    @POST
    @Path("/{id}/pagamento")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Responses.AcertoResponse informarPagamento(@PathParam("id") Long id,
                                                      Requests.InformarPagamento req) {
        Usuario eu = logado.exigirSenhaTrocada();
        return Responses.AcertoResponse.de(
                acertos.informarPagamento(id, eu, req == null ? null : req.observacao()));
    }

    @DELETE
    @Path("/{id}")
    @RolesAllowed(TokenService.ADMIN)
    public void excluir(@PathParam("id") Long id) {
        Fatura f = buscar(id);
        if (f.status == br.com.jcard.model.StatusFatura.FECHADA) {
            throw new WebApplicationException(
                    "Fatura fechada não pode ser excluída — ela é o histórico do acerto.", 409);
        }
        f.delete();
    }

    // --------------------------------------------------------------- apoio --

    private Fatura buscar(Long id) {
        Fatura f = Fatura.findById(id);
        if (f == null) {
            throw new WebApplicationException("Fatura não encontrada.", 404);
        }
        return f;
    }

    private Responses.FaturaResponse resumo(Fatura f) {
        int total = (int) Lancamento.count("fatura.id", f.id);
        int pool = Lancamento.poolDaFatura(f.id).size();
        int conflitos = Reivindicacao.lancamentosEmConflito(f.id).size();
        return Responses.FaturaResponse.de(f, total, pool, conflitos);
    }

    private LocalDate mesDe(String competencia) {
        if (competencia == null || competencia.isBlank()) {
            throw new WebApplicationException("Informe a competência no formato AAAA-MM.", 400);
        }
        try {
            return YearMonth.parse(competencia.strip()).atDay(1);
        } catch (RuntimeException e) {
            throw new WebApplicationException(
                    "Competência inválida: use AAAA-MM (ex.: 2026-08).", 400);
        }
    }
}
