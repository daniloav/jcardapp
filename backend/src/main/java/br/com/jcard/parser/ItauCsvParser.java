package br.com.jcard.parser;

import br.com.jcard.model.TipoLancamento;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Leitor da fatura do Itaú em CSV.
 *
 * <p>É o caminho preferido, e por um motivo concreto: o PDF é formato de
 * <b>apresentação</b>. A fatura real tem duas colunas lado a lado, cinco tipos
 * de bloco (lançamentos por cartão, internacionais com colunas US$ e R$, taxas,
 * simulações e parcelas futuras) e descrições truncadas na largura da coluna —
 * ler aquilo exige geometria e erra. O CSV já vem com um lançamento por linha,
 * com a parcela num campo próprio.
 *
 * <p>Formato esperado (cabeçalho obrigatório, separador {@code ;}):
 * <pre>
 * pagina;coluna;data;estabelecimento;parcela;valor
 * 2;1;08/09;PARTMED E ODONTOCO;11/12;190,00
 * 6;2;09/07;DL*UberRides;;7,35
 * 8;2;04/08;DESC ANTECIPA PARCELAS;;-23,48
 * </pre>
 *
 * <p>{@code pagina} e {@code coluna} são ignorados — servem só para conferir a
 * origem contra o PDF quando algo não bate.
 *
 * <p>O CSV <b>não traz o total da fatura nem o cartão de cada lançamento</b>.
 * O total é informado por quem importa (e a conciliação confere contra a soma);
 * sem o cartão, tudo nasce no pool para as pessoas reivindicarem, que é o fluxo
 * normal do app.
 */
@ApplicationScoped
public class ItauCsvParser implements FaturaParser {

    private static final Logger LOG = Logger.getLogger(ItauCsvParser.class);
    private static final String SEPARADOR = ";";

    /** Colunas que o arquivo precisa ter, na ordem em que as lemos por nome. */
    private static final List<String> OBRIGATORIAS =
            List.of("data", "estabelecimento", "valor");

    @Override
    public String emissor() {
        return "ITAU_CSV";
    }

    @Override
    public FaturaLida ler(String texto, LocalDate competencia) {
        List<LancamentoLido> lancamentos = new ArrayList<>();
        List<String> ignoradas = new ArrayList<>();

        String[] linhas = texto.split("\\R");
        if (linhas.length == 0) {
            return new FaturaLida(competencia, null, null, lancamentos, ignoradas);
        }

        // O BOM do UTF-8 vem colado no primeiro cabeçalho e quebraria o nome da coluna.
        int[] indices = mapearColunas(linhas[0].replace("﻿", ""));

        for (int i = 1; i < linhas.length; i++) {
            String linha = linhas[i].strip();
            if (linha.isEmpty()) {
                continue;
            }
            LancamentoLido lido = montar(linha, indices, competencia);
            if (lido == null) {
                ignoradas.add(linha);
            } else {
                lancamentos.add(lido);
            }
        }

        if (!ignoradas.isEmpty()) {
            LOG.warnf("CSV: %d linha(s) não reconhecidas.", ignoradas.size());
        }
        // Total e vencimento não existem no CSV: quem importa informa o total.
        return new FaturaLida(competencia, null, null, lancamentos, ignoradas);
    }

    /** Descobre a posição de cada coluna pelo nome, para não depender da ordem. */
    private int[] mapearColunas(String cabecalho) {
        String[] nomes = cabecalho.toLowerCase().split(SEPARADOR, -1);
        int[] idx = new int[4];   // data, estabelecimento, parcela, valor
        java.util.Arrays.fill(idx, -1);
        for (int i = 0; i < nomes.length; i++) {
            switch (nomes[i].strip()) {
                case "data" -> idx[0] = i;
                case "estabelecimento", "descricao", "descrição" -> idx[1] = i;
                case "parcela" -> idx[2] = i;
                case "valor" -> idx[3] = i;
                default -> { }
            }
        }
        for (int i = 0; i < OBRIGATORIAS.size(); i++) {
            int pos = i == 2 ? idx[3] : idx[i];   // parcela é opcional
            if (pos < 0) {
                throw new jakarta.ws.rs.WebApplicationException(
                        "O CSV precisa ter a coluna '" + OBRIGATORIAS.get(i)
                        + "'. Cabeçalho esperado: pagina;coluna;data;estabelecimento;parcela;valor", 422);
            }
        }
        return idx;
    }

    private LancamentoLido montar(String linha, int[] idx, LocalDate competencia) {
        String[] c = linha.split(SEPARADOR, -1);
        try {
            String data = campo(c, idx[0]);
            String descricao = campo(c, idx[1]);
            String parcela = campo(c, idx[2]);
            BigDecimal valor = TextoFatura.valor(campo(c, idx[3]));

            if (valor == null || descricao.isBlank() || !data.matches("\\d{2}/\\d{2}")) {
                return null;
            }

            String[] dm = data.split("/");
            LocalDate dataCompra = TextoFatura.comAno(
                    Integer.parseInt(dm[0]), Integer.parseInt(dm[1]), competencia);

            Integer atual = null;
            Integer total = null;
            if (parcela.matches("\\d{1,2}/\\d{1,2}")) {
                String[] p = parcela.split("/");
                int a = Integer.parseInt(p[0]);
                int t = Integer.parseInt(p[1]);
                // 01/01 é compra à vista impressa com sufixo, não parcelamento.
                if (t >= 2 && a >= 1 && a <= t) {
                    atual = a;
                    total = t;
                }
            }

            TipoLancamento tipo = TextoFatura.classificar(descricao, valor);

            return new LancamentoLido(
                    dataCompra,
                    descricao.length() > 255 ? descricao.substring(0, 255) : descricao,
                    TextoFatura.normalizar(descricao),
                    valor,
                    null,        // o CSV não traz o portador
                    null,        // nem os 4 dígitos do cartão
                    atual,
                    total,
                    tipo,
                    linha.length() > 400 ? linha.substring(0, 400) : linha);
        } catch (RuntimeException e) {
            LOG.debugf("Linha de CSV descartada (%s): %s", e.getMessage(), linha);
            return null;
        }
    }

    private String campo(String[] c, int i) {
        return i >= 0 && i < c.length ? c[i].strip() : "";
    }

}
