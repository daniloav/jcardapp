package br.com.jcard.parser;

import br.com.jcard.model.TipoLancamento;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Leitor da fatura do Itaú.
 *
 * <p>O PDF vem seccionado por portador — cada cartão adicional abre um bloco
 * com nome e os 4 últimos dígitos, e as linhas seguintes pertencem a ele. O
 * parser percorre o texto de cima para baixo mantendo o portador corrente.
 *
 * <p>As regexes vêm da configuração ({@code jcard.parser.itau.*}) justamente
 * porque layout de banco muda: dá para calibrar contra uma fatura nova editando
 * o {@code .env}, sem recompilar e sem deploy.
 *
 * <p>O que não casa nenhuma regra vai para {@code linhasIgnoradas} em vez de
 * sumir. Como a conciliação exige que a soma bata com o total impresso, uma linha
 * perdida vira fatura {@code DIVERGENTE} — nunca um rateio errado silencioso.
 */
@ApplicationScoped
public class ItauFaturaParser implements FaturaParser {

    private static final Logger LOG = Logger.getLogger(ItauFaturaParser.class);
    private static final DateTimeFormatter DATA_BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Linhas que nunca são lançamento, por mais que casem o formato. */
    private static final Pattern CABECALHO_RUIDO = Pattern.compile(
            "(?i)^\\s*(total|subtotal|saldo|limite|lan[çc]amentos|demonstrativo|fatura|"
            + "vencimento|pagamento m[íi]nimo|encargos previstos|juros de|data\\s+descri|"
            + "compras parceladas|p[áa]gina|www\\.|sac\\b|ouvidoria)\\b.*");

    private final Pattern rePortador;
    private final Pattern reLancamento;
    private final Pattern reTotal;
    private final Pattern reVencimento;

    public ItauFaturaParser(
            @ConfigProperty(name = "jcard.parser.itau.portador") String portador,
            @ConfigProperty(name = "jcard.parser.itau.lancamento") String lancamento,
            @ConfigProperty(name = "jcard.parser.itau.total") String total,
            @ConfigProperty(name = "jcard.parser.itau.vencimento") String vencimento) {
        this.rePortador = Pattern.compile(portador);
        this.reLancamento = Pattern.compile(lancamento);
        this.reTotal = Pattern.compile(total);
        this.reVencimento = Pattern.compile(vencimento);
    }

    @Override
    public String emissor() {
        return "ITAU";
    }

    @Override
    public FaturaLida ler(String texto, LocalDate competencia) {
        List<LancamentoLido> lancamentos = new ArrayList<>();
        List<String> ignoradas = new ArrayList<>();

        String portadorAtual = null;
        String finalAtual = null;

        for (String linhaBruta : texto.split("\\R")) {
            String linha = linhaBruta.strip();
            if (linha.isEmpty()) {
                continue;
            }

            // 1) Abriu um bloco de portador? As linhas seguintes são desse cartão.
            Matcher mp = rePortador.matcher(linha);
            if (mp.find()) {
                portadorAtual = mp.group(1).strip();
                finalAtual = mp.group(2);
                continue;
            }

            // 2) Cabeçalho/rodapé conhecido: descarta sem poluir as ignoradas.
            if (CABECALHO_RUIDO.matcher(linha).matches()) {
                continue;
            }

            // 3) Linha de lançamento.
            Matcher ml = reLancamento.matcher(linha);
            if (!ml.matches()) {
                if (pareceLancamento(linha)) {
                    ignoradas.add(linha);
                }
                continue;
            }

            LancamentoLido lido = montar(ml, linha, competencia, portadorAtual, finalAtual);
            if (lido == null) {
                ignoradas.add(linha);
            } else {
                lancamentos.add(lido);
            }
        }

        BigDecimal total = extrairTotal(texto);
        LocalDate vencimento = extrairVencimento(texto);

        if (!ignoradas.isEmpty()) {
            LOG.warnf("Fatura %s: %d linha(s) com cara de lançamento não reconhecidas.",
                    competencia, ignoradas.size());
        }
        return new FaturaLida(competencia, vencimento, total, lancamentos, ignoradas);
    }

    // ------------------------------------------------------------- internos --

    private LancamentoLido montar(Matcher m, String linha, LocalDate competencia,
                                  String portador, String final4) {
        try {
            String[] dm = m.group(1).split("/");
            LocalDate data = TextoFatura.comAno(
                    Integer.parseInt(dm[0]), Integer.parseInt(dm[1]), competencia);

            String descricao = m.group(2).strip();
            BigDecimal valor = TextoFatura.valor(m.group(3));
            if (valor == null || descricao.isBlank()) {
                return null;
            }

            int[] parcela = TextoFatura.parcela(descricao);
            TipoLancamento tipo = classificar(descricao, valor);

            // Crédito (estorno/pagamento) é grandeza negativa mesmo quando o PDF
            // imprime sem sinal — o total só fecha se o sinal estiver certo.
            if ((tipo == TipoLancamento.PAGAMENTO || tipo == TipoLancamento.ESTORNO)
                    && valor.signum() > 0) {
                valor = valor.negate();
            }

            return new LancamentoLido(
                    data,
                    descricao.length() > 255 ? descricao.substring(0, 255) : descricao,
                    TextoFatura.normalizar(descricao),
                    valor,
                    portador,
                    final4,
                    parcela == null ? null : parcela[0],
                    parcela == null ? null : parcela[1],
                    tipo,
                    linha.length() > 400 ? linha.substring(0, 400) : linha);
        } catch (RuntimeException e) {
            LOG.debugf("Linha descartada (%s): %s", e.getMessage(), linha);
            return null;
        }
    }

    /**
     * Classifica pela descrição. Só {@code COMPRA} e {@code ESTORNO} são
     * reivindicáveis; encargos e anuidade ficam com o titular por padrão.
     */
    private TipoLancamento classificar(String descricao, BigDecimal valor) {
        String d = TextoFatura.normalizar(descricao);
        if (d.contains("PAGAMENTO") && (d.contains("EFETUADO") || d.contains("FATURA"))
                || d.startsWith("PGTO")) {
            return TipoLancamento.PAGAMENTO;
        }
        if (d.contains("ESTORNO") || d.contains("DEVOLUCAO") || d.contains("CANCELAMENTO")) {
            return TipoLancamento.ESTORNO;
        }
        if (d.contains("ANUIDADE")) {
            return TipoLancamento.ANUIDADE;
        }
        if (d.contains("IOF")) {
            return TipoLancamento.IOF;
        }
        if (d.contains("JUROS") || d.contains("MULTA") || d.contains("ENCARGO")
                || d.contains("MORA") || d.contains("TARIFA") || d.contains("SEGURO")) {
            return TipoLancamento.ENCARGO;
        }
        // Crédito sem palavra-chave: trata como estorno para o sinal fechar.
        return valor.signum() < 0 ? TipoLancamento.ESTORNO : TipoLancamento.COMPRA;
    }

    /** Começa com data e termina com dinheiro: quase certamente é lançamento. */
    private boolean pareceLancamento(String linha) {
        return linha.matches("^\\s*\\d{2}/\\d{2}\\b.*[\\d,]{4,}\\s*$");
    }

    private BigDecimal extrairTotal(String texto) {
        Matcher m = reTotal.matcher(texto);
        BigDecimal ultimo = null;
        // O texto pode citar o total mais de uma vez (resumo + detalhe); o último
        // costuma ser o definitivo no layout do Itaú.
        while (m.find()) {
            BigDecimal v = TextoFatura.valor(m.group(1));
            if (v != null) {
                ultimo = v;
            }
        }
        return ultimo;
    }

    private LocalDate extrairVencimento(String texto) {
        Matcher m = reVencimento.matcher(texto);
        if (!m.find()) {
            return null;
        }
        try {
            return LocalDate.parse(m.group(1), DATA_BR.withLocale(Locale.forLanguageTag("pt-BR")));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
