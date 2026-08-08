package br.com.jcard.service;

import br.com.jcard.model.AcaoAuditoria;
import br.com.jcard.model.Fatura;
import br.com.jcard.model.Lancamento;
import br.com.jcard.model.OrigemAtribuicao;
import br.com.jcard.model.Usuario;
import br.com.jcard.parser.FaturaLida;
import br.com.jcard.parser.FaturaParser;
import br.com.jcard.parser.LancamentoLido;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.jboss.logging.Logger;

/**
 * Importa o PDF da fatura: extrai, parseia, aplica as regras automáticas de
 * atribuição, valida a soma e avisa os utilizadores.
 *
 * <p>Idempotente pelo SHA-256 do arquivo — reenviar o mesmo PDF é rejeitado com
 * 409 em vez de duplicar a fatura inteira.
 */
@ApplicationScoped
public class FaturaImportService {

    private static final Logger LOG = Logger.getLogger(FaturaImportService.class);

    @Inject
    ExtratorPdf extrator;

    @Inject
    FaturaParser parser;

    @Inject
    AtribuicaoService atribuicao;

    @Inject
    ConciliacaoService conciliacao;

    @Inject
    NotificacaoService notificacao;

    @Inject
    AuditoriaService auditoria;

    /**
     * @param competencia mês de referência; o parser usa para completar o ano das
     *                    datas {@code dd/MM} e para identificar compras do mês anterior
     * @param totalManual quando informado, sobrepõe o total lido do PDF — a saída
     *                    para quando o layout esconde o total mas os lançamentos estão certos
     */
    @Transactional
    public Fatura importar(byte[] pdf, String nomeArquivo, LocalDate competencia,
                           BigDecimal totalManual, Usuario quem) {

        extrator.validarPdf(pdf);
        String hash = extrator.hash(pdf);

        Fatura existente = Fatura.porHash(hash);
        if (existente != null) {
            throw new WebApplicationException(
                    "Esta fatura já foi importada em "
                    + existente.importadaEm.toLocalDate() + " (competência "
                    + existente.competencia + ").", 409);
        }

        String texto = extrator.texto(pdf);
        FaturaLida lida = parser.ler(texto, competencia.withDayOfMonth(1));

        BigDecimal total = totalManual != null ? totalManual : lida.valorTotal();
        if (total == null) {
            throw new WebApplicationException(
                    "Não encontrei o total da fatura no PDF. Reenvie informando o valor total "
                    + "manualmente para eu conferir contra os lançamentos lidos.", 422);
        }
        if (lida.lancamentos().isEmpty()) {
            throw new WebApplicationException(
                    "Não reconheci nenhum lançamento neste PDF. Se o layout mudou, ajuste as "
                    + "regexes em jcard.parser.itau.* e tente de novo.", 422);
        }

        Fatura fatura = new Fatura();
        fatura.competencia = competencia.withDayOfMonth(1);
        fatura.vencimento = lida.vencimento();
        fatura.valorTotal = total.setScale(2, java.math.RoundingMode.HALF_UP);
        fatura.hashPdf = hash;
        fatura.nomeArquivo = nomeArquivo;
        fatura.textoExtraido = texto;
        fatura.emissor = parser.emissor();
        fatura.importadaPor = quem;
        fatura.persist();

        int herdados = 0;
        int porCartao = 0;
        for (LancamentoLido lido : lida.lancamentos()) {
            Lancamento l = novoLancamento(fatura, lido);
            l.persist();

            OrigemAtribuicao origem = atribuicao.aplicar(l);
            if (origem == OrigemAtribuicao.HERDADA_PARCELA) {
                herdados++;
            } else if (origem == OrigemAtribuicao.REGRA_CARTAO) {
                porCartao++;
            }
            l.persist();
        }

        // Valida a leitura ANTES de avisar ninguém: não faz sentido pedir avaliação
        // de uma fatura que o parser leu errado.
        conciliacao.validarLeitura(fatura.id);
        conciliacao.recalcularAcertos(fatura.id);

        int pool = Lancamento.poolDaFatura(fatura.id).size();
        String resumo = "%d lançamentos · %d herdados de parcela · %d por cartão · %d no pool%s"
                .formatted(lida.lancamentos().size(), herdados, porCartao, pool,
                        lida.linhasIgnoradas().isEmpty()
                                ? ""
                                : " · " + lida.linhasIgnoradas().size() + " linha(s) ignorada(s)");
        LOG.infof("Fatura %s importada: %s", fatura.competencia, resumo);
        auditoria.registrar(quem, AcaoAuditoria.IMPORTAR_FATURA, "Fatura", fatura.id, resumo);

        if (fatura.aberta()) {
            notificacao.faturaImportada(fatura, pool);
        } else {
            LOG.warnf("Fatura %d ficou DIVERGENTE — utilizadores não foram avisados.", fatura.id);
        }
        return fatura;
    }

    /**
     * Reprocessa uma fatura a partir do texto já guardado, útil depois de
     * calibrar as regexes. Preserva as atribuições feitas por gente.
     */
    @Transactional
    public Fatura reprocessar(Fatura fatura, Usuario quem) {
        if (fatura.textoExtraido == null || fatura.textoExtraido.isBlank()) {
            throw new WebApplicationException(
                    "Esta fatura não tem o texto guardado; reimporte o PDF.", 422);
        }
        if (fatura.status == br.com.jcard.model.StatusFatura.FECHADA) {
            throw new WebApplicationException("Fatura fechada não é reprocessada.", 409);
        }

        FaturaLida lida = parser.ler(fatura.textoExtraido, fatura.competencia);
        Lancamento.delete("fatura.id = ?1 and (responsavel is null or origemAtribuicao <> ?2)",
                fatura.id, OrigemAtribuicao.MANUAL);

        for (LancamentoLido lido : lida.lancamentos()) {
            // Não recria o que uma pessoa já assumiu manualmente.
            long jaExiste = Lancamento.count(
                    "fatura.id = ?1 and dataCompra = ?2 and valor = ?3 and descricao = ?4",
                    fatura.id, lido.dataCompra(), lido.valor(), lido.descricao());
            if (jaExiste > 0) {
                continue;
            }
            Lancamento l = novoLancamento(fatura, lido);
            l.persist();
            atribuicao.aplicar(l);
            l.persist();
        }

        if (lida.valorTotal() != null) {
            fatura.valorTotal = lida.valorTotal().setScale(2, java.math.RoundingMode.HALF_UP);
        }
        conciliacao.validarLeitura(fatura.id);
        conciliacao.recalcularAcertos(fatura.id);
        auditoria.registrar(quem, AcaoAuditoria.ATUALIZAR, "Fatura", fatura.id, "reprocessada");
        return fatura;
    }

    private Lancamento novoLancamento(Fatura fatura, LancamentoLido lido) {
        Lancamento l = new Lancamento();
        l.fatura = fatura;
        l.dataCompra = lido.dataCompra();
        l.descricao = lido.descricao();
        l.descricaoNormalizada = lido.descricaoNormalizada();
        l.valor = lido.valor();
        l.portadorNome = lido.portadorNome();
        l.final4 = lido.final4();
        l.parcelaAtual = lido.parcelaAtual();
        l.parcelaTotal = lido.parcelaTotal();
        l.tipo = lido.tipo();
        l.linhaOriginal = lido.linhaOriginal();
        return l;
    }
}
