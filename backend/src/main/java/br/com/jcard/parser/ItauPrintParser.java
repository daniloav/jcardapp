package br.com.jcard.parser;

import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Leitor do <b>print da fatura em aberto</b> — o texto que o OCR do navegador
 * extrai de uma foto da tela do app do banco.
 *
 * <p>Existe porque o CSV da fatura em aberto nem sempre está ao alcance: no
 * celular o que dá para tirar é print. Sem ele o mês em aberto fica sem começo,
 * e a família volta ao mutirão do dia do vencimento.
 *
 * <p><b>O que ele lê não vira lançamento.</b> Vira <i>proposta</i>: OCR troca
 * dígito ("189,00" por "180,00"), e a prévia não tem total impresso para
 * denunciar — foi por isso que o PDF já tinha sido recusado nela. Quem confirma
 * linha a linha é o admin, olhando a mesma tela de onde tirou o print. Aqui a
 * máquina serve para não ter de digitar quarenta linhas, não para dizer a
 * verdade.
 *
 * <p>Por isso as três decisões deste leitor:
 * <ol>
 *   <li><b>Só emite bloco completo</b> — data, descrição e valor. É o que impede
 *       "Total desta fatura R$ 1.234,56" de virar uma compra: total não tem data.</li>
 *   <li><b>Não conserta dígito.</b> Trocar {@code O} por {@code 0} para fazer o
 *       valor casar é adivinhar dinheiro dos outros em silêncio. O que não casa
 *       volta em {@code linhasIgnoradas}, a tela mostra, e o admin digita.</li>
 *   <li><b>As regexes ficam na configuração</b> ({@code jcard.parser.itau.print.*}),
 *       como as do PDF: layout de app muda mais que layout de PDF, e calibrar não
 *       pode exigir recompilar.</li>
 * </ol>
 *
 * <p>Aceita as duas formas em que a tela do app costuma sair no OCR — tudo numa
 * linha, ou em bloco:
 * <pre>
 * 05/08  PADARIA DO BAIRRO  R$ 120,00
 *
 * 08 ago
 * LOJA DE MOVEIS
 * Parcela 2 de 10
 * R$ 190,00
 * </pre>
 */
@ApplicationScoped
public class ItauPrintParser implements FaturaParser {

    private static final Logger LOG = Logger.getLogger(ItauPrintParser.class);

    /** Meses por extenso, na ordem — o app escreve "08 ago" mais que "08/08". */
    private static final List<String> MESES =
            List.of("jan", "fev", "mar", "abr", "mai", "jun",
                    "jul", "ago", "set", "out", "nov", "dez");

    private final Pattern data;
    private final Pattern valor;
    private final Pattern parcela;
    private final Pattern ignorar;

    public ItauPrintParser(
            @ConfigProperty(name = "jcard.parser.itau.print.data") String data,
            @ConfigProperty(name = "jcard.parser.itau.print.valor") String valor,
            @ConfigProperty(name = "jcard.parser.itau.print.parcela") String parcela,
            @ConfigProperty(name = "jcard.parser.itau.print.ignorar") String ignorar) {
        this.data = Pattern.compile(data);
        this.valor = Pattern.compile(valor);
        this.parcela = Pattern.compile(parcela);
        this.ignorar = Pattern.compile(ignorar);
    }

    @Override
    public String emissor() {
        return "ITAU_PRINT";
    }

    @Override
    public FaturaLida ler(String texto, LocalDate competencia) {
        List<LancamentoLido> lidos = new ArrayList<>();
        List<String> ignoradas = new ArrayList<>();
        Bloco bloco = new Bloco();

        for (String bruta : texto.split("\\R")) {
            String linha = bruta.strip();
            if (linha.isEmpty()) {
                continue;
            }
            if (ignorar.matcher(linha).find()) {
                // Cabeçalho, total, limite, vencimento: o que a tela do app mostra
                // em volta dos lançamentos. Fecha o bloco aberto, se houver.
                emitir(bloco, competencia, lidos, ignoradas);
                bloco = new Bloco();
                continue;
            }

            String comData = comDataLegivel(linha);
            Matcher md = data.matcher(comData);
            if (md.find() && md.start() == 0) {
                linha = comData;
                // Data começa lançamento novo: o anterior, se estava aberto, acabou.
                emitir(bloco, competencia, lidos, ignoradas);
                bloco = new Bloco();
                bloco.data = md.group();
                bloco.linhas.add(linha);
                consumir(bloco, linha.substring(md.end()).strip());
                // Data + descrição + valor na mesma linha: já está completo.
                if (bloco.completo()) {
                    emitir(bloco, competencia, lidos, ignoradas);
                    bloco = new Bloco();
                }
                continue;
            }

            if (bloco.data == null) {
                if (parcelaSolta(linha, lidos)) {
                    continue;
                }
                // Texto solto antes de qualquer data. Não inventa lançamento com
                // ele: sem data não há como saber de que dia é a compra.
                ignoradas.add(linha);
                continue;
            }

            bloco.linhas.add(linha);
            consumir(bloco, linha);
            if (bloco.completo()) {
                emitir(bloco, competencia, lidos, ignoradas);
                bloco = new Bloco();
            }
        }
        emitir(bloco, competencia, lidos, ignoradas);

        if (!ignoradas.isEmpty()) {
            LOG.infof("Print: %d linha(s) não reconhecidas de %d lançamento(s) lidos.",
                    ignoradas.size(), lidos.size());
        }
        // Nem total nem vencimento: um print é um pedaço do mês, não a fatura.
        return new FaturaLida(competencia, null, null, lidos, ignoradas);
    }

    /**
     * Conserta o dígito da <b>data</b> quando o OCR o leu como letra —
     * "O5 ago" em vez de "05 ago", que é o erro mais comum de todos.
     *
     * <p>Isto é uma exceção deliberada ao "não conserta dígito", e a fronteira é
     * clara: <b>data não é dinheiro</b>. Ler 05 como 08 não cobra nada de
     * ninguém, e o admin ainda vê a data num seletor antes de confirmar. Já um
     * valor corrigido por adivinhação viraria cobrança sem que ninguém
     * percebesse — ali o leitor continua desistindo.
     *
     * <p>Mexe só no primeiro pedaço da linha, e só quando o que sobra vira uma
     * data de verdade. "OI TELEFONIA" não tem mês depois, então não vira "01".
     */
    private String comDataLegivel(String linha) {
        int fim = linha.indexOf(' ');
        String inicio = fim < 0 ? linha : linha.substring(0, fim);
        if (!inicio.matches("[0-9OoDlI|/]{1,5}")) {
            return linha;
        }
        String corrigido = inicio
                .replace('O', '0').replace('o', '0').replace('D', '0')
                .replace('l', '1').replace('I', '1').replace('|', '1');
        if (corrigido.equals(inicio)) {
            return linha;
        }
        String candidata = corrigido + (fim < 0 ? "" : linha.substring(fim));
        Matcher m = data.matcher(candidata);
        return m.find() && m.start() == 0 ? candidata : linha;
    }

    /**
     * A linha que só diz "Parcela 2 de 10", <b>depois</b> do lançamento já ter
     * fechado.
     *
     * <p>Acontece porque o app põe o valor à direita, na mesma altura do nome da
     * loja, e a informação da parcela numa segunda linha abaixo — o OCR entrega
     * na ordem em que lê, e o bloco já fechou no valor. Sem isto a compra entra
     * sem a parcela, e uma parcela sem número não gruda com quem a assumiu: a
     * regra que sustenta o app inteiro deixaria de valer justo no caminho do
     * print.
     *
     * <p>Só cola no lançamento imediatamente anterior e só se ele ainda não tiver
     * parcela — mais que isso seria adivinhar a qual compra a linha pertence.
     */
    private boolean parcelaSolta(String linha, List<LancamentoLido> lidos) {
        if (lidos.isEmpty()) {
            return false;
        }
        Matcher m = parcela.matcher(linha);
        if (!m.find() || !linha.strip().equals(m.group().strip())) {
            return false;
        }
        int atual = Integer.parseInt(m.group(1));
        int total = Integer.parseInt(m.group(2));
        if (total < 2 || total > 99 || atual < 1 || atual > total) {
            return false;
        }
        LancamentoLido ultimo = lidos.get(lidos.size() - 1);
        if (ultimo.parcelaTotal() != null) {
            return false;
        }
        lidos.set(lidos.size() - 1, new LancamentoLido(
                ultimo.dataCompra(), ultimo.descricao(), ultimo.descricaoNormalizada(),
                ultimo.valor(), ultimo.portadorNome(), ultimo.final4(), atual, total,
                ultimo.tipo(), ultimo.linhaOriginal() + " · " + linha));
        return true;
    }

    /** Tira desta linha o que ela tiver de parcela, de valor e de descrição. */
    private void consumir(Bloco bloco, String linha) {
        if (linha.isBlank()) {
            return;
        }
        String resto = linha;

        Matcher mp = parcela.matcher(resto);
        if (mp.find()) {
            int atual = Integer.parseInt(mp.group(1));
            int total = Integer.parseInt(mp.group(2));
            // "01/01" e totais absurdos são falso-positivo, como no CSV.
            if (total >= 2 && total <= 99 && atual >= 1 && atual <= total) {
                bloco.parcelaAtual = atual;
                bloco.parcelaTotal = total;
            }
            resto = (resto.substring(0, mp.start()) + " " + resto.substring(mp.end())).strip();
        }

        Matcher mv = valor.matcher(resto);
        if (mv.find()) {
            BigDecimal v = TextoFatura.valor(mv.group().replaceAll("(?i)r\\s*[s$]", ""));
            if (v != null) {
                bloco.valor = v;
            }
            resto = (resto.substring(0, mv.start()) + " " + resto.substring(mv.end())).strip();
        }

        if (!resto.isBlank()) {
            bloco.descricao = bloco.descricao.isEmpty() ? resto : bloco.descricao + " " + resto;
        }
    }

    /**
     * Fecha o bloco, se ele for um lançamento de verdade.
     *
     * <p>Faltando data, descrição ou valor, as linhas vão para
     * {@code ignoradas} em vez de virar lançamento pela metade — a tela mostra
     * essas linhas para o admin decidir, que é melhor do que sumir com elas ou
     * do que gravar um valor que ninguém leu.
     */
    private void emitir(Bloco bloco, LocalDate competencia,
                        List<LancamentoLido> lidos, List<String> ignoradas) {
        if (bloco.linhas.isEmpty()) {
            return;
        }
        LocalDate dia = bloco.data == null ? null : dataDe(bloco.data, competencia);
        if (dia == null || bloco.valor == null || bloco.descricao.isBlank()) {
            ignoradas.addAll(bloco.linhas);
            return;
        }

        String descricao = bloco.descricao.length() > 255
                ? bloco.descricao.substring(0, 255) : bloco.descricao;
        String original = String.join(" · ", bloco.linhas);

        lidos.add(new LancamentoLido(
                dia,
                descricao,
                TextoFatura.normalizar(descricao),
                bloco.valor,
                null,                 // o print não diz o portador
                null,                 // nem os 4 dígitos do cartão
                bloco.parcelaAtual,
                bloco.parcelaTotal,
                TextoFatura.classificar(descricao, bloco.valor),
                original.length() > 400 ? original.substring(0, 400) : original));
    }

    /**
     * A data do bloco: {@code 05/08}, {@code 05/08/2026} ou {@code 05 ago}.
     *
     * <p>Sem ano, vale a regra do PDF: mês maior que o da competência é do ano
     * anterior — a fatura de janeiro traz compras de dezembro.
     */
    private LocalDate dataDe(String bruta, LocalDate competencia) {
        String s = bruta.strip().toLowerCase().replace(".", "");
        try {
            Matcher numerica = Pattern.compile("^(\\d{1,2})\\s*/\\s*(\\d{1,2})(?:\\s*/\\s*(\\d{2,4}))?")
                    .matcher(s);
            if (numerica.find()) {
                int dia = Integer.parseInt(numerica.group(1));
                int mes = Integer.parseInt(numerica.group(2));
                if (numerica.group(3) == null) {
                    return TextoFatura.comAno(dia, mes, competencia);
                }
                int ano = Integer.parseInt(numerica.group(3));
                return LocalDate.of(ano < 100 ? 2000 + ano : ano, mes, dia);
            }
            Matcher extenso = Pattern.compile("^(\\d{1,2})\\s*(?:de\\s+)?([a-zç]{3,})").matcher(s);
            if (extenso.find()) {
                int mes = MESES.indexOf(extenso.group(2).substring(0, 3)) + 1;
                if (mes > 0) {
                    return TextoFatura.comAno(Integer.parseInt(extenso.group(1)), mes, competencia);
                }
            }
        } catch (RuntimeException e) {
            LOG.debugf("Data de print descartada (%s): %s", e.getMessage(), bruta);
        }
        return null;
    }

    /** Um lançamento sendo montado — pode vir numa linha só ou em várias. */
    private static final class Bloco {
        String data;
        String descricao = "";
        BigDecimal valor;
        Integer parcelaAtual;
        Integer parcelaTotal;
        final List<String> linhas = new ArrayList<>();

        boolean completo() {
            return data != null && valor != null && !descricao.isBlank();
        }
    }
}
