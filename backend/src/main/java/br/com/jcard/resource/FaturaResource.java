package br.com.jcard.resource;

import br.com.jcard.dto.Requests;
import br.com.jcard.dto.Responses;
import br.com.jcard.model.Acerto;
import br.com.jcard.model.ApelidoEstabelecimento;
import br.com.jcard.model.DivisaoLancamento;
import br.com.jcard.model.Fatura;
import br.com.jcard.model.Lancamento;
import br.com.jcard.model.Reivindicacao;
import br.com.jcard.model.Usuario;
import br.com.jcard.parser.FaturaLida;
import br.com.jcard.parser.ItauPrintParser;
import br.com.jcard.security.TokenService;
import br.com.jcard.security.UsuarioLogado;
import br.com.jcard.service.AcertoService;
import br.com.jcard.service.ConciliacaoService;
import br.com.jcard.service.FaturaImportService;
import br.com.jcard.service.ParcelasPrevistasService;
import br.com.jcard.service.PixConfig;
import br.com.jcard.service.PreviaService;
import br.com.jcard.service.RateioService;
import br.com.jcard.service.ReivindicacaoService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    PreviaService previas;

    @Inject
    ParcelasPrevistasService parcelasPrevistas;

    @Inject
    ItauPrintParser parserPrint;

    @Inject
    ConciliacaoService conciliacao;

    @Inject
    ReivindicacaoService reivindicacoes;

    @Inject
    AcertoService acertos;

    @Inject
    RateioService rateio;

    @Inject
    PixConfig pix;

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

    /**
     * Sobe a prévia do mês — a fatura em aberto, baixada do banco em CSV.
     *
     * <p>Endpoint separado da importação, e não um parâmetro dela, porque as duas
     * pedem coisas diferentes: a fatura de verdade exige o total impresso e
     * recusa arquivo repetido; a prévia não tem total, é para ser subida de novo
     * toda semana e cada subida <b>substitui</b> a anterior. Espremer as duas no
     * mesmo endpoint faria a validação depender de uma flag — e a flag errada
     * apagaria uma fatura.
     *
     * <p>Não pede valor total: numa parcial não existe total impresso para
     * conferir. A invariante "as contas batem" continua sendo cobrada onde
     * importa, na fatura fechada.
     */
    @POST
    @Path("/previa")
    @RolesAllowed(TokenService.ADMIN)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Transactional
    public Responses.PreviaResponse subirPrevia(@RestForm("arquivo") FileUpload arquivo,
                                                @RestForm String competencia) {
        if (arquivo == null) {
            throw new WebApplicationException("Envie o CSV da fatura em aberto.", 400);
        }
        byte[] conteudo;
        try {
            conteudo = Files.readAllBytes(arquivo.uploadedFile());
        } catch (IOException e) {
            throw new WebApplicationException("Falha ao ler o arquivo enviado.", 400);
        }

        PreviaService.Resultado r = previas.subir(conteudo, arquivo.fileName(),
                mesDe(competencia), logado.exigirSenhaTrocada());
        Map<String, String> apelidos = ApelidoEstabelecimento.mapa();
        return new Responses.PreviaResponse(resumo(r.fatura()), r.lancamentos(), r.noPool(),
                r.mantidos(), r.devolvidos(), r.ignoradas(),
                Responses.ParcelaPrevistaResponse.de(r.batimento().conferidas(), apelidos),
                Responses.ParcelaPrevistaResponse.de(r.batimento().ausentes(), apelidos));
    }

    /**
     * Lê o print da fatura em aberto e devolve o que entendeu — <b>sem gravar
     * nada</b>.
     *
     * <p>O OCR roda no navegador e manda o texto; aqui mora o parser, com as
     * regexes na configuração, pela mesma razão do leitor de PDF: layout de app
     * muda, e calibrar não pode exigir recompilar. E a resposta é proposta, não
     * lançamento: quem confirma linha a linha é o admin, olhando a mesma tela de
     * onde tirou o print. OCR troca dígito, e numa prévia não existe total
     * impresso para denunciar a troca — é a mesma razão pela qual o PDF nunca foi
     * aceito aqui.
     */
    @POST
    @Path("/previa/print")
    @RolesAllowed(TokenService.ADMIN)
    @Consumes(MediaType.APPLICATION_JSON)
    public Responses.PrintLidoResponse lerPrint(@Valid Requests.TextoDePrint req) {
        logado.exigirSenhaTrocada();
        LocalDate mes = mesDe(req.competencia());
        FaturaLida lida = parserPrint.ler(req.texto(), mes);
        return new Responses.PrintLidoResponse(mes,
                lida.lancamentos().stream().map(Responses.LinhaLidaResponse::de).toList(),
                lida.linhasIgnoradas());
    }

    /**
     * Soma à prévia do mês as linhas que o admin confirmou.
     *
     * <p><b>Soma</b>, e não substitui — é o contrário do CSV, e é o contrário de
     * propósito: o CSV é o mês inteiro, o print é um pedaço dele. Anexar o
     * segundo print não pode desfazer o primeiro.
     *
     * <p>Linha idêntica a uma que já está na prévia é descartada em silêncio (só
     * o número aparece na resposta): quem rola a tela do banco tirando foto
     * fotografa a mesma compra várias vezes, e isso é muito mais comum do que a
     * compra idêntica repetida no mesmo dia. Para essa, o caminho é a linha
     * digitada à mão na tela de conferência.
     */
    @POST
    @Path("/previa/lancamentos")
    @RolesAllowed(TokenService.ADMIN)
    @Consumes(MediaType.APPLICATION_JSON)
    public Responses.SomaDePrintResponse somarAoPrevia(@Valid Requests.LinhasDePrint req) {
        Usuario eu = logado.exigirSenhaTrocada();
        List<PreviaService.Linha> linhas = req.linhas().stream()
                .map(l -> new PreviaService.Linha(l.data(), l.descricao(), l.valor(),
                        l.parcelaAtual(), l.parcelaTotal()))
                .toList();

        PreviaService.ResultadoSoma r = previas.somar(mesDe(req.competencia()), linhas, eu);
        Map<String, String> apelidos = ApelidoEstabelecimento.mapa();
        return new Responses.SomaDePrintResponse(resumo(r.fatura()), r.somados(), r.repetidos(),
                r.total(), r.noPool(),
                Responses.ParcelaPrevistaResponse.de(r.batimento().conferidas(), apelidos),
                Responses.ParcelaPrevistaResponse.de(r.batimento().ausentes(), apelidos));
    }

    /**
     * O mês em aberto inteiro: o que o CSV já trouxe e o que os parcelamentos em
     * curso ainda vão trazer.
     *
     * <p>Existe separado da listagem porque responde <b>sem prévia subida</b> —
     * que é justamente quando ele mais serve. No dia 1º ainda não há CSV, mas as
     * parcelas de quem comprou em 10x já são certas: elas são do mês do mesmo
     * jeito, e começar a tela em zero esconderia a maior parte do que a pessoa
     * vai dever.
     *
     * <p>Sem {@code competencia}, responde sobre a prévia mais recente — o mês em
     * aberto não é necessariamente o mês do calendário, já que a fatura fecha
     * antes do fim dele.
     */
    @GET
    @Path("/previa")
    @Transactional
    public Responses.PreviaDoMes previaDoMes(@QueryParam("competencia") String competencia) {
        Usuario eu = logado.exigirSenhaTrocada();
        boolean escolhido = competencia != null && !competencia.isBlank();
        Fatura previa = escolhido
                ? Fatura.previaDa(mesDe(competencia))
                : Fatura.previaMaisRecente();
        LocalDate mes = previa != null ? previa.competencia
                : (escolhido ? mesDe(competencia) : mesEmAberto());

        boolean todas = eu.admin;
        List<ParcelasPrevistasService.Prevista> previstas = todas
                ? parcelasPrevistas.doMesEmAberto(mes)
                : parcelasPrevistas.doMesEmAberto(mes, eu.id);

        return new Responses.PreviaDoMes(mes,
                previa == null ? null : resumo(previa),
                Responses.ParcelaPrevistaResponse.de(previstas, ApelidoEstabelecimento.mapa()),
                ParcelasPrevistasService.somar(previstas),
                todas);
    }

    @POST
    @Path("/{id}/reprocessar")
    @RolesAllowed(TokenService.ADMIN)
    @Transactional
    public Responses.FaturaResponse reprocessar(@PathParam("id") Long id) {
        return resumo(importacao.reprocessar(buscar(id), logado.get()));
    }

    // ------------------------------------------------------------- consulta --

    /**
     * A lista de faturas — e a base do gráfico da tela inicial.
     *
     * <p>Três consultas no total, independentes da quantidade de faturas: o
     * cabeçalho de todos os meses (sem o texto da fatura), as contagens de
     * lançamentos e as de conflito. Antes eram três <b>por fatura</b>, uma delas
     * carregando o pool inteiro para chamar {@code size()} — com um ano de
     * faturas de 514 linhas, a tela inicial esperava por isso.
     */
    @GET
    @Transactional
    public List<Responses.FaturaResponse> listar() {
        logado.exigirSenhaTrocada();
        Map<Long, Lancamento.Contagem> contagens = Lancamento.contagensPorFatura();
        Map<Long, Integer> conflitos = Reivindicacao.conflitosPorFatura();
        return Fatura.resumosRecentes().stream()
                .map(f -> {
                    Lancamento.Contagem c = contagens.getOrDefault(
                            f.id(), Lancamento.Contagem.VAZIA);
                    return Responses.FaturaResponse.de(f, (int) c.total(), (int) c.noPool(),
                            conflitos.getOrDefault(f.id(), 0));
                })
                .toList();
    }

    /** Visão completa da fatura — só o admin vê todos os lançamentos com dono. */
    @GET
    @Path("/{id}")
    @RolesAllowed(TokenService.ADMIN)
    @Transactional
    public Map<String, Object> detalhe(@PathParam("id") Long id) {
        Fatura f = buscar(id);
        Usuario eu = logado.get();
        Map<String, String> apelidos = ApelidoEstabelecimento.mapa();
        return Map.of(
                "fatura", resumo(f),
                "lancamentos", Lancamento.daFatura(id).stream()
                        .map(l -> Responses.LancamentoResponse.de(l, eu.id)
                                .comApelido(apelidos.get(l.descricaoNormalizada))
                                .comDivisao(DivisaoLancamento.doLancamento(l.id), eu.id))
                        .toList(),
                "acertos", Responses.AcertoResponse.daFatura(id, Acerto.daFatura(id)));
    }

    /**
     * A tela do utilizador: o pool, o que já é dele e a fatia dele nos encargos.
     *
     * <p>Não devolve o que outra pessoa assumiu — é decisão de privacidade: cada
     * um vê as próprias contas e o pool, nada além. A exceção é a conta
     * dividida: aí os participantes se veem, porque estavam na mesma mesa.
     *
     * <p>Os valores saem do mesmo {@code RateioService} que alimenta a
     * conciliação. É de propósito: o número que a pessoa confere aqui tem de ser,
     * ao centavo, o número que ela vai pagar.
     */
    @GET
    @Path("/{id}/minhas-contas")
    @Transactional
    public Responses.MinhasContas minhasContas(@PathParam("id") Long id) {
        Fatura f = buscar(id);
        Usuario eu = logado.exigirSenhaTrocada();
        Map<String, String> apelidos = ApelidoEstabelecimento.mapa();

        // Onde esta pessoa já comprou em outras faturas. Uma consulta só, antes
        // do laço: numa fatura de 514 linhas, perguntar por lançamento seria
        // 514 consultas para responder a mesma pergunta.
        Set<String> conhecidos = Lancamento.estabelecimentosDe(eu.id, id);

        List<Responses.LancamentoResponse> pool = Lancamento.poolDaFatura(id).stream()
                .map(l -> Responses.LancamentoResponse.de(l, eu.id).anonimo()
                        .comApelido(apelidos.get(l.descricaoNormalizada))
                        .comHistorico(conhecidos.contains(l.descricaoNormalizada)))
                .toList();

        List<Responses.LancamentoResponse> meus = Lancamento.deUsuarioNaFatura(id, eu.id).stream()
                .map(l -> Responses.LancamentoResponse.de(l, eu.id)
                        .comApelido(apelidos.get(l.descricaoNormalizada))
                        .comDivisao(DivisaoLancamento.doLancamento(l.id), eu.id))
                .toList();
        BigDecimal totalCompras = meus.stream()
                .map(Responses.LancamentoResponse::minhaParte)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // A fatia dos encargos vem do rateio da fatura inteira: dividir aqui de
        // novo daria outro arredondamento e a tela mostraria centavo a menos.
        Map<Long, BigDecimal> meusEncargos = rateio.ratear(f, conciliacao.titular()).stream()
                .filter(p -> p.usuarioId().equals(eu.id))
                .collect(java.util.stream.Collectors.toMap(
                        RateioService.Parte::lancamentoId, RateioService.Parte::valor,
                        BigDecimal::add));
        List<Responses.LancamentoResponse> encargos = Lancamento.encargosDaFatura(id).stream()
                .filter(l -> meusEncargos.containsKey(l.id))
                .map(l -> Responses.LancamentoResponse.de(l, eu.id)
                        .comApelido(apelidos.get(l.descricaoNormalizada))
                        .comMinhaParte(meusEncargos.get(l.id)))
                .toList();
        BigDecimal totalEncargos = encargos.stream()
                .map(Responses.LancamentoResponse::minhaParte)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Na prévia, o que ainda vai chegar por parcelamento é dela do mesmo
        // jeito: assumir a 1/10 é assumir as dez. Fica em campo próprio e fora do
        // `total` porque previsão não é cobrança — o total é o que o acerto copia.
        List<Responses.ParcelaPrevistaResponse> previstas = f.ehPrevia()
                ? Responses.ParcelaPrevistaResponse.de(
                        parcelasPrevistas.doMesEmAberto(f.competencia, eu.id), apelidos)
                : List.of();
        BigDecimal totalPrevisto = previstas.stream()
                .map(Responses.ParcelaPrevistaResponse::valor)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal total = totalCompras.add(totalEncargos);

        Acerto a = Acerto.de(id, eu.id);
        return new Responses.MinhasContas(
                resumo(f),
                pool,
                meus,
                encargos,
                totalCompras,
                totalEncargos,
                total,
                previstas,
                totalPrevisto,
                total.add(totalPrevisto),
                a == null ? null : Responses.AcertoResponse.de(a),
                pix.atual());
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
        // Em lote: os pagamentos e os comprovantes de todos os acertos saem em
        // duas consultas, e não em duas por pessoa.
        return Responses.AcertoResponse.daFatura(id, Acerto.daFatura(id));
    }

    /**
     * A conta de uma pessoa aberta linha a linha, para o admin conferir.
     *
     * <p>Responde à pergunta que o total sozinho não responde: "esse encargo foi
     * rateado com ela ou não?" — e, quando não foi, mostra o porquê, que é
     * sempre o mesmo: ela não tem lançamento assumido nesta fatura, então não
     * consta como quem usou o cartão no mês.
     *
     * <p>Sai do <b>mesmo</b> {@code RateioService.ratear} que alimenta a tela da
     * pessoa e a conciliação. Conferir contra um segundo cálculo não provaria
     * nada: provaria só que os dois cálculos concordam entre si.
     *
     * <p>Funciona para qualquer utilizador, inclusive quem <b>não</b> tem acerto
     * — é justamente aí que mora a dúvida.
     */
    @GET
    @Path("/{id}/utilizadores/{usuarioId}/detalhe")
    @RolesAllowed(TokenService.ADMIN)
    @Transactional
    public Responses.DetalheDoUtilizador detalheDoUtilizador(@PathParam("id") Long id,
                                                             @PathParam("usuarioId") Long usuarioId) {
        Fatura f = buscar(id);
        Usuario alvo = Usuario.findById(usuarioId);
        if (alvo == null) {
            throw new WebApplicationException("Utilizador não encontrado.", 404);
        }
        Usuario titular = conciliacao.titular();
        Map<String, String> apelidos = ApelidoEstabelecimento.mapa();

        List<Usuario> participantes = rateio.participantesDa(f, titular);
        Map<Long, BigDecimal> dela = rateio.ratear(f, titular).stream()
                .filter(p -> p.usuarioId().equals(usuarioId))
                .collect(java.util.stream.Collectors.toMap(
                        RateioService.Parte::lancamentoId, RateioService.Parte::valor,
                        BigDecimal::add));

        // As divisões vêm numa consulta só: por lançamento seriam 514 idas ao
        // banco para montar uma tela de conferência.
        Map<Long, List<DivisaoLancamento>> divisoes = new java.util.HashMap<>();
        for (DivisaoLancamento d : DivisaoLancamento.daFatura(id)) {
            divisoes.computeIfAbsent(d.lancamento.getId(), k -> new java.util.ArrayList<>()).add(d);
        }

        List<Responses.LancamentoResponse> compras = new java.util.ArrayList<>();
        List<Responses.LancamentoResponse> encargos = new java.util.ArrayList<>();
        for (Lancamento l : Lancamento.daFatura(id)) {
            BigDecimal parte = dela.get(l.id);
            if (parte == null) {
                continue;
            }
            Responses.LancamentoResponse linha = Responses.LancamentoResponse.de(l, usuarioId)
                    .comApelido(apelidos.get(l.descricaoNormalizada))
                    .comDivisao(divisoes.getOrDefault(l.id, List.of()), usuarioId)
                    .comMinhaParte(parte);
            if (l.tipo.rateavel()) {
                encargos.add(linha);
            } else {
                compras.add(linha);
            }
        }

        // Escala fixa em 2: somar lista vazia devolve BigDecimal.ZERO, que sai
        // como "0" no JSON enquanto os demais saem como "0.00". Numa tela de
        // conferência de dinheiro, o mesmo número tem de ter sempre a mesma cara.
        BigDecimal totalCompras = somar(compras);
        BigDecimal totalEncargos = somar(encargos);
        BigDecimal total = totalCompras.add(totalEncargos);

        Acerto a = Acerto.de(id, usuarioId);
        return new Responses.DetalheDoUtilizador(
                new Responses.Pessoa(alvo.id, alvo.nome),
                participantes.stream().anyMatch(u -> u.id.equals(usuarioId)),
                participantes.stream().map(u -> new Responses.Pessoa(u.id, u.nome)).toList(),
                compras, encargos, totalCompras, totalEncargos, total,
                a == null ? null : Responses.AcertoResponse.de(a),
                a == null ? null : a.valorDevido.subtract(total));
    }

    // ------------------------------------------------------------ fechamento --

    @POST
    @Path("/{id}/conciliar")
    @RolesAllowed(TokenService.ADMIN)
    @Transactional
    public Responses.FaturaResponse conciliar(@PathParam("id") Long id) {
        return resumo(conciliacao.conciliar(id, logado.get()));
    }

    /**
     * Devolve a fatura conciliada para avaliação — a saída para o "marquei o
     * lançamento errado" descoberto tarde demais.
     *
     * <p>Mexe em valor já combinado: os aceites caem, quem for afetado recebe
     * e-mail e a ação vai para a auditoria com o motivo. Acerto já confirmado
     * barra a operação — ver {@code ConciliacaoService.reabrirAvaliacao}.
     */
    @POST
    @Path("/{id}/reabrir-avaliacao")
    @RolesAllowed(TokenService.ADMIN)
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Responses.FaturaResponse reabrirAvaliacao(@PathParam("id") Long id,
                                                     Requests.ReabrirAvaliacao req) {
        return resumo(conciliacao.reabrirAvaliacao(id, logado.get(),
                req == null ? null : req.motivo()));
    }

    @POST
    @Path("/{id}/fechar")
    @RolesAllowed(TokenService.ADMIN)
    @Transactional
    public Responses.FaturaResponse fechar(@PathParam("id") Long id) {
        return resumo(conciliacao.fechar(id, logado.get()));
    }

    // ------------------------------------------------------------- pagamento --

    /** "Conferi o meu total e concordo com ele." Libera o formulário de pagamento. */
    @POST
    @Path("/{id}/aceite")
    @Transactional
    public Responses.AcertoResponse aceitar(@PathParam("id") Long id) {
        return Responses.AcertoResponse.de(acertos.aceitar(id, logado.exigirSenhaTrocada()));
    }

    /**
     * O utilizador declara <b>uma</b> transferência desta fatura.
     *
     * <p>É multipart porque o comprovante do PIX é <b>obrigatório</b>: sem ele
     * não existe registro de que o dinheiro saiu, e a confirmação do admin viraria
     * palavra contra palavra.
     *
     * <p>O {@code valor} é opcional e em branco vale "paguei o que faltava" — o
     * caso comum. Quando vem preenchido, é pagamento parcial ou complementar, e
     * o saldo continua aberto até fechar.
     */
    @POST
    @Path("/{id}/pagamento")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Transactional
    public Responses.AcertoResponse informarPagamento(@PathParam("id") Long id,
                                                      @RestForm("comprovante") FileUpload comprovante,
                                                      @RestForm String valor,
                                                      @RestForm String pagoEm,
                                                      @RestForm String observacao) {
        Usuario eu = logado.exigirSenhaTrocada();
        if (comprovante == null) {
            throw new WebApplicationException(
                    "Anexe o comprovante do PIX ou da transferência.", 400);
        }
        byte[] conteudo;
        try {
            conteudo = Files.readAllBytes(comprovante.uploadedFile());
        } catch (IOException e) {
            throw new WebApplicationException("Falha ao ler o comprovante enviado.", 400);
        }
        return Responses.AcertoResponse.de(acertos.informarPagamento(
                id, eu, valorDe(valor), dataDe(pagoEm), observacao, conteudo,
                comprovante.fileName(), comprovante.contentType()).acerto);
    }

    /**
     * Apaga a fatura — a saída para o arquivo errado ou a competência trocada,
     * já que o hash único impede reimportar por cima.
     */
    @DELETE
    @Path("/{id}")
    @RolesAllowed(TokenService.ADMIN)
    public void excluir(@PathParam("id") Long id) {
        importacao.excluir(id, logado.get());
    }

    // --------------------------------------------------------------- apoio --

    /** Soma as fatias de uma lista, sempre com duas casas. */
    private static BigDecimal somar(List<Responses.LancamentoResponse> linhas) {
        return linhas.stream()
                .map(Responses.LancamentoResponse::minhaParte)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Qual mês está em aberto quando não existe prévia para dizer.
     *
     * <p>O primeiro, a partir do corrente, cuja fatura de verdade ainda não foi
     * importada. O ciclo do cartão fecha antes do fim do mês, e em 25 de agosto,
     * com agosto já importado, o que está em aberto é setembro — responder
     * "agosto" ali diria que não vem parcela nenhuma, justo quando elas todas
     * ainda vêm. O limite de doze é só para não procurar para sempre: quem
     * importou um ano adiantado não tem mês em aberto para mostrar.
     */
    private static LocalDate mesEmAberto() {
        LocalDate mes = LocalDate.now().withDayOfMonth(1);
        for (int i = 0; i < 12 && Fatura.definitivaDa(mes) != null; i++) {
            mes = mes.plusMonths(1);
        }
        return mes;
    }

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

    /** Data do pagamento; ausente ou ilegível vira "hoje", que é o caso comum. */
    /**
     * O valor de uma transferência, como a tela manda. Aceita "1.234,56" e
     * "1234.56": o campo é digitado no celular, e recusar por causa da vírgula
     * seria travar o pagamento por formatação.
     */
    private BigDecimal valorDe(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        String limpo = valor.strip().replace("R$", "").strip();
        if (limpo.contains(",")) {
            limpo = limpo.replace(".", "").replace(',', '.');
        }
        try {
            return new BigDecimal(limpo);
        } catch (RuntimeException e) {
            throw new WebApplicationException(
                    "Valor do pagamento inválido: informe algo como 130,00.", 400);
        }
    }

    private LocalDate dataDe(String data) {
        if (data == null || data.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(data.strip());
        } catch (RuntimeException e) {
            throw new WebApplicationException("Data do pagamento inválida: use AAAA-MM-DD.", 400);
        }
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
