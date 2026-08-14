package br.com.jcard.service;

import br.com.jcard.model.AcaoAuditoria;
import br.com.jcard.model.Acerto;
import br.com.jcard.model.CompromissoParcelado;
import br.com.jcard.model.DivisaoLancamento;
import br.com.jcard.model.Fatura;
import br.com.jcard.model.Lancamento;
import br.com.jcard.model.OrigemAtribuicao;
import br.com.jcard.model.Reivindicacao;
import br.com.jcard.model.StatusFatura;
import br.com.jcard.model.Usuario;
import br.com.jcard.parser.FaturaLida;
import br.com.jcard.parser.ItauCsvParser;
import br.com.jcard.parser.LancamentoLido;
import br.com.jcard.parser.TextoFatura;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * A prévia da fatura: o CSV do mês que ainda não fechou, subido quantas vezes o
 * admin quiser.
 *
 * <p>Existe porque a fatura chega inteira e de uma vez. Quinhentas linhas no
 * dia do vencimento é um mutirão: cada pessoa tem de reconhecer um mês de
 * compras de memória, e o que ninguém reconhece a tempo cai no titular. Com a
 * prévia o mesmo trabalho vira meia dúzia de toques por semana, com a compra
 * ainda fresca.
 *
 * <p>Duas regras sustentam isso:
 * <ol>
 *   <li><b>A prévia é uma só e é sobrescrita.</b> Ela é a foto do mês agora;
 *       guardar as fotos anteriores só criaria versões concorrentes do mesmo
 *       mês, com gente assumindo conta em telas diferentes.</li>
 *   <li><b>O que foi assumido gruda.</b> Sobrescrever não pode custar o
 *       trabalho de ninguém — senão a segunda subida desfaz o que a primeira
 *       rendeu, e o recurso inteiro deixa de valer a pena. As atribuições são
 *       recapturadas antes de apagar e reaplicadas na leitura nova; no fim do
 *       mês, a <b>fatura de verdade</b> herda da prévia pelo mesmo caminho
 *       ({@link #consumir}) — que é o objetivo de tudo isto.</li>
 * </ol>
 *
 * <p>O que a prévia não faz: acerto, conciliação, fechamento e e-mail. Ninguém
 * deve nada por uma parcial, e um aviso por subida — que pode ser diária —
 * treinaria a família a ignorar justamente o e-mail da fatura de verdade.
 *
 * <p>O CSV não é a única fonte do mês em aberto. As parcelas dos parcelamentos
 * já assumidos são certas antes de qualquer arquivo chegar, e o
 * {@link ParcelasPrevistasService} as mostra ao lado do que foi lido. Quando o
 * arquivo traz a parcela, ela deixa de ser previsão e vira lançamento — é o
 * batimento que o {@link Resultado} reporta.
 */
@ApplicationScoped
public class PreviaService {

    private static final Logger LOG = Logger.getLogger(PreviaService.class);

    @Inject
    ExtratorPdf extrator;

    @Inject
    ItauCsvParser parser;

    @Inject
    AtribuicaoService atribuicao;

    @Inject
    AuditoriaService auditoria;

    @Inject
    ParcelasPrevistasService parcelasPrevistas;

    // ------------------------------------------------------------ herança --

    /** Uma parte de conta dividida, sem depender das entidades que serão apagadas. */
    public record Parte(Long usuarioId, BigDecimal valor) {
    }

    /** De quem era um lançamento na leitura anterior, e como ele estava rachado. */
    public record Atribuicao(Long usuarioId, OrigemAtribuicao origem, List<Parte> partes) {
    }

    /**
     * O que as pessoas já tinham assumido, pronto para ser reaplicado na leitura
     * nova — da prévia seguinte ou da fatura de verdade.
     *
     * <p>Guarda uma <b>fila por chave</b>, e não um valor por chave: três
     * corridas de Uber de R$ 7,35 no mesmo dia são três lançamentos com a mesma
     * identidade, e podem ser de três pessoas diferentes. Casar uma por uma, na
     * ordem, mantém a contagem certa; um mapa simples daria as três ao dono da
     * última.
     */
    public static final class Heranca {

        private final Map<String, Deque<Atribuicao>> porChave;
        private final int total;
        private int aproveitadas;

        static final Heranca VAZIA = new Heranca(Map.of(), 0);

        private Heranca(Map<String, Deque<Atribuicao>> porChave, int total) {
            this.porChave = porChave;
            this.total = total;
        }

        /** Quantas atribuições vieram da leitura anterior. */
        public int total() {
            return total;
        }

        /** Quantas casaram com um lançamento da leitura nova. */
        public int aproveitadas() {
            return aproveitadas;
        }

        /**
         * As que não casaram: a compra mudou de valor ou de data no arquivo novo,
         * ou simplesmente sumiu dele.
         *
         * <p>Elas voltam ao pool, e é o desfecho certo: casar "quase igual"
         * cobraria de alguém uma compra que ela não viu. Vale a mesma regra do
         * {@code AtribuicaoService} — na dúvida, deixa no pool.
         */
        public int perdidas() {
            return total - aproveitadas;
        }

        /** A próxima atribuição guardada para esta chave, ou {@code null}. */
        private Atribuicao proxima(String chave) {
            Deque<Atribuicao> fila = porChave.get(chave);
            return fila == null ? null : fila.poll();
        }
    }

    /**
     * Devolve o lançamento a quem já o tinha assumido na leitura anterior.
     *
     * <p>Chamado <b>depois</b> das regras automáticas e por cima delas: se
     * alguém assumiu esta linha, foi uma decisão de gente sobre uma linha que
     * ela viu, enquanto o dono padrão do cartão é um palpite do app.
     *
     * @param comCompromisso registra o parcelamento junto. Verdadeiro só na
     *                       fatura de verdade — a prévia não mexe no compromisso,
     *                       ver {@link AtribuicaoService#registrarCompromisso}
     * @return se este lançamento nasceu com dono por herança
     */
    boolean aplicar(Heranca heranca, Lancamento l, boolean comCompromisso) {
        if (!l.tipo.reivindicavel()) {
            return false;
        }
        Atribuicao a = heranca.proxima(chaveDe(l));
        if (a == null) {
            return false;
        }
        Usuario dono = Usuario.findById(a.usuarioId());
        if (dono == null || !dono.ativo) {
            // Pessoa desativada no meio do mês: a conta volta ao pool em vez de
            // nascer no nome de quem não usa mais o app.
            return false;
        }

        l.atribuirA(dono, a.origem());
        l.persist();
        aplicarDivisao(l, a.partes(), dono);
        if (comCompromisso) {
            atribuicao.registrarCompromisso(l, dono);
        }
        heranca.aproveitadas++;
        return true;
    }

    /**
     * Refaz o racha, quando havia um.
     *
     * <p>Copiado verbatim, sem revalidar: o valor do lançamento faz parte da
     * chave que casou os dois, então a soma que fechava antes fecha agora.
     * Participante desativado no meio do caminho derruba a divisão inteira —
     * meia divisão não somaria o lançamento, e as contas deixariam de bater.
     */
    private void aplicarDivisao(Lancamento l, List<Parte> partes, Usuario responsavel) {
        if (partes.isEmpty()) {
            return;
        }
        List<DivisaoLancamento> novas = new ArrayList<>(partes.size());
        for (Parte p : partes) {
            Usuario u = Usuario.findById(p.usuarioId());
            if (u == null || !u.ativo) {
                return;
            }
            DivisaoLancamento d = new DivisaoLancamento();
            d.lancamento = l;
            d.usuario = u;
            d.valor = p.valor();
            d.criadoPor = responsavel;
            novas.add(d);
        }
        novas.forEach(d -> d.persist());
    }

    /**
     * A identidade de um lançamento entre duas leituras do mesmo mês.
     *
     * <p>Data, estabelecimento, valor e parcela — tudo que o CSV traz. É
     * deliberadamente estrita: uma compra que aparece com valor diferente no
     * arquivo novo (a gorjeta entrou, a pré-autorização virou débito) <b>não</b>
     * é a mesma compra, e devolvê-la ao pool custa um toque, enquanto herdar
     * errado cobra de alguém um valor que ela não conferiu.
     *
     * <p>Texto, e não os objetos: {@code BigDecimal} compara escala em
     * {@code equals} ("7.35" ≠ "7.350"), e a chave passa por um {@code HashMap}.
     */
    static String chaveDe(Lancamento l) {
        return chaveDe(l.dataCompra, l.descricaoNormalizada, l.valor,
                l.parcelaAtual, l.parcelaTotal);
    }

    private static String chaveDe(LocalDate data, String descricaoNormalizada, BigDecimal valor,
                                  Integer parcelaAtual, Integer parcelaTotal) {
        return data + "|" + descricaoNormalizada
                + "|" + valor.setScale(2, RoundingMode.HALF_UP).toPlainString()
                + "|" + parcelaAtual + "/" + parcelaTotal;
    }

    // ------------------------------------------------------------- subida --

    /**
     * O que uma subida de prévia produziu, para a tela poder contar em vez de só
     * recarregar. O que se perdeu importa tanto quanto o que ficou: é a parte
     * que o admin não vê acontecer.
     *
     * @param mantidos  atribuições que sobreviveram à sobrescrita
     * @param devolvidos as que não casaram com nenhuma linha do arquivo novo e
     *                   voltaram ao pool
     * @param batimento  o confronto com as parcelas que os compromissos já
     *                   prometiam para este mês: quais chegaram no arquivo e
     *                   quais continuam sendo só previsão
     */
    public record Resultado(Fatura fatura, int lancamentos, int noPool,
                            int mantidos, int devolvidos, int ignoradas,
                            ParcelasPrevistasService.Batimento batimento) {
    }

    /**
     * Sobe (ou substitui) a prévia do mês.
     *
     * <p>Só CSV. O leitor de PDF fecha 2 de 5 cartões numa fatura real (ver
     * {@code docs/parser-itau.md}); numa prévia isso não travaria nada — não há
     * total contra o qual conferir —, e as pessoas passariam o mês assumindo
     * contas de uma leitura silenciosamente incompleta.
     */
    @Transactional
    public Resultado subir(byte[] arquivo, String nomeArquivo, LocalDate mes, Usuario quem) {
        LocalDate competencia = mes.withDayOfMonth(1);

        if (extrator.ehPdf(arquivo)) {
            throw new WebApplicationException(
                    "A prévia é só por CSV. O leitor de PDF não lê a fatura inteira, e numa "
                    + "prévia não há total impresso para denunciar a falta — as pessoas "
                    + "passariam o mês assumindo contas de uma leitura incompleta.", 422);
        }
        Fatura definitiva = Fatura.definitivaDa(competencia);
        if (definitiva != null) {
            throw new WebApplicationException(
                    "A fatura de " + competencia + " já foi importada. A prévia é do mês que "
                    + "ainda não fechou — abra a fatura para mexer nos lançamentos dela.", 409);
        }

        String texto = new String(arquivo, StandardCharsets.UTF_8);
        FaturaLida lida = parser.ler(texto, competencia);
        if (lida.lancamentos().isEmpty()) {
            throw new WebApplicationException(
                    "Não reconheci nenhum lançamento no CSV. O cabeçalho esperado é "
                    + "pagina;coluna;data;estabelecimento;parcela;valor", 422);
        }

        // Captura o que já foi assumido e só então apaga a prévia anterior — nesta
        // ordem, porque a leitura nova é construída em cima do que a antiga rendeu.
        Heranca heranca = consumir(competencia);

        // O que os parcelamentos em curso prometiam para este mês, antes de olhar
        // o arquivo. É a metade esperada do batimento; a outra sai das chaves que
        // a leitura nova trouxer.
        List<ParcelasPrevistasService.Prevista> esperadas = parcelasPrevistas.prometidas();

        Fatura previa = new Fatura();
        previa.competencia = competencia;
        previa.status = StatusFatura.PREVIA;
        // O CSV não traz o total e a fatura ainda não fechou: aqui o total É a
        // soma do que foi lido. Não há divergência possível, e é por isso que a
        // prévia nunca trava — travar é papel da fatura de verdade, que tem um
        // total impresso para contradizer a leitura.
        BigDecimal soma = lida.lancamentos().stream()
                .map(LancamentoLido::valor)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        previa.valorTotal = soma;
        previa.valorLancado = soma;
        previa.hashPdf = extrator.hash(arquivo);
        previa.nomeArquivo = nomeArquivo;
        previa.textoExtraido = texto;
        previa.emissor = parser.emissor();
        previa.importadaPor = quem;
        previa.persist();

        Set<String> chavesLidas = new HashSet<>();
        for (LancamentoLido lido : lida.lancamentos()) {
            Lancamento l = FaturaImportService.novoLancamento(previa, lido);
            l.persist();
            atribuicao.aplicar(l);
            aplicar(heranca, l, false);
            l.persist();
            if (l.chaveParcelamento != null) {
                chavesLidas.add(l.chaveParcelamento);
            }
        }
        ParcelasPrevistasService.Batimento batimento =
                parcelasPrevistas.bater(esperadas, chavesLidas);

        int noPool = Lancamento.poolDaFatura(previa.id).size();
        String resumo = "%d lançamentos · %d atribuição(ões) mantida(s) · %d devolvida(s) ao pool "
                + "· %d no pool · %d parcela(s) prevista(s) conferida(s), %d ainda por vir%s";
        resumo = resumo.formatted(lida.lancamentos().size(), heranca.aproveitadas(),
                heranca.perdidas(), noPool,
                batimento.conferidas().size(), batimento.ausentes().size(),
                lida.linhasIgnoradas().isEmpty()
                        ? ""
                        : " · " + lida.linhasIgnoradas().size() + " linha(s) ignorada(s)");
        LOG.infof("Prévia de %s atualizada: %s", competencia, resumo);
        auditoria.registrar(quem, AcaoAuditoria.IMPORTAR_FATURA, "Fatura", previa.id,
                "prévia · " + resumo);

        return new Resultado(previa, lida.lancamentos().size(), noPool,
                heranca.aproveitadas(), heranca.perdidas(), lida.linhasIgnoradas().size(),
                batimento);
    }

    // -------------------------------------------------------------- prints --

    /**
     * Uma linha que o admin <b>confirmou</b> na tela de conferência do print.
     *
     * <p>Vem da tela, e não do leitor, de propósito: o que o OCR entendeu é
     * proposta, e o que entra na prévia é o que uma pessoa leu e aprovou. Entre
     * os dois pode haver correção de dígito — é para isso que a conferência
     * existe.
     */
    public record Linha(LocalDate data, String descricao, BigDecimal valor,
                        Integer parcelaAtual, Integer parcelaTotal) {
    }

    /**
     * O que uma leva de linhas confirmadas produziu.
     *
     * @param somados   linhas que viraram lançamento
     * @param repetidos linhas idênticas a algo que já estava na prévia — dois
     *                  prints com a mesma compra, que é o normal de quem rola a
     *                  tela tirando foto. Descartadas, mas contadas: sumir em
     *                  silêncio absoluto esconderia a compra que se repete de
     *                  verdade no mesmo dia
     * @param total     quantos lançamentos a prévia tem agora, somando tudo
     */
    public record ResultadoSoma(Fatura fatura, int somados, int repetidos, int noPool,
                                int total, ParcelasPrevistasService.Batimento batimento) {
    }

    /**
     * Soma lançamentos à prévia do mês — o caminho do print.
     *
     * <p>É o oposto de {@link #subir}, e o oposto é o ponto: o CSV é o mês
     * inteiro e por isso <b>substitui</b>; um print é um pedaço do mês, e cinco
     * prints são cinco pedaços que têm de se somar. Se cada print apagasse o
     * anterior, anexar o segundo desfaria o primeiro.
     *
     * <p>Cria a prévia se ainda não houver uma: o primeiro print do mês é o que
     * dá início a ela. Daí em diante todo print entra na mesma, e um CSV que
     * apareça depois substitui tudo — ele é a leitura do banco, e o print é o
     * que se faz na falta dela.
     *
     * <p>Cada linha passa pelas mesmas regras automáticas da importação
     * ({@code AtribuicaoService}), então a parcela de um parcelamento já assumido
     * nasce no nome do dono aqui também. O que ela <b>não</b> faz é registrar
     * compromisso — pela mesma razão de sempre: a prévia é provisória.
     */
    @Transactional
    public ResultadoSoma somar(LocalDate mes, List<Linha> linhas, Usuario quem) {
        LocalDate competencia = mes.withDayOfMonth(1);
        if (linhas == null || linhas.isEmpty()) {
            throw new WebApplicationException("Nenhuma linha confirmada para somar.", 400);
        }
        Fatura definitiva = Fatura.definitivaDa(competencia);
        if (definitiva != null) {
            throw new WebApplicationException(
                    "A fatura de " + competencia + " já foi importada. O print serve para o mês "
                    + "que ainda não fechou — abra a fatura para mexer nos lançamentos dela.", 409);
        }

        List<ParcelasPrevistasService.Prevista> esperadas = parcelasPrevistas.prometidas();

        Fatura previa = Fatura.previaDa(competencia);
        if (previa == null) {
            previa = novaPrevia(competencia, quem);
        }

        // As chaves que já estão na prévia. Um multiset seria o ideal — três
        // corridas de R$ 7,35 no mesmo dia são três compras —, mas aqui a decisão
        // foi descartar a repetida sem perguntar: quem tira print rola a tela e
        // fotografa a mesma linha várias vezes, e isso é muito mais frequente do
        // que a compra idêntica repetida. A saída para o caso raro é a linha
        // "adicionar à mão" da tela de conferência, que não passa por aqui.
        Set<String> jaNaPrevia = new HashSet<>();
        for (Lancamento l : Lancamento.daFatura(previa.id)) {
            jaNaPrevia.add(chaveDe(l));
        }

        int somados = 0;
        int repetidos = 0;
        for (Linha linha : linhas) {
            Lancamento l = FaturaImportService.novoLancamento(previa, deLinha(linha));
            String chave = chaveDe(l);
            if (!jaNaPrevia.add(chave)) {
                repetidos++;
                continue;
            }
            l.persist();
            atribuicao.aplicar(l);
            l.persist();
            somados++;
        }

        recalcularTotal(previa);

        int noPool = Lancamento.poolDaFatura(previa.id).size();
        int total = (int) Lancamento.count("fatura.id", previa.id);
        ParcelasPrevistasService.Batimento batimento = parcelasPrevistas.bater(
                esperadas, Lancamento.chavesParceladasDaFatura(previa.id));

        String resumo = "%d linha(s) somada(s) · %d repetida(s) descartada(s) · %d no total "
                + "· %d no pool".formatted(somados, repetidos, total, noPool);
        LOG.infof("Prévia de %s: %s", competencia, resumo);
        auditoria.registrar(quem, AcaoAuditoria.IMPORTAR_FATURA, "Fatura", previa.id,
                "prévia por print · " + resumo);

        return new ResultadoSoma(previa, somados, repetidos, noPool, total, batimento);
    }

    /**
     * A prévia que o primeiro print do mês inaugura.
     *
     * <p>Sem arquivo, e é o que ela tem de diferente: {@code hashPdf} é a
     * identidade do arquivo importado e aqui não há arquivo, então vira uma
     * marca sintética da competência — a coluna é única e obrigatória, e uma
     * prévia por mês já é garantida pelo índice parcial. {@code textoExtraido}
     * fica vazio de propósito: ele existe para reprocessar a leitura sem pedir o
     * arquivo de novo, e reprocessar OCR não faz sentido — o que vale não é o
     * que a máquina leu, é o que o admin confirmou.
     */
    private Fatura novaPrevia(LocalDate competencia, Usuario quem) {
        Fatura previa = new Fatura();
        previa.competencia = competencia;
        previa.status = StatusFatura.PREVIA;
        previa.valorTotal = BigDecimal.ZERO;
        previa.valorLancado = BigDecimal.ZERO;
        previa.hashPdf = "print:" + competencia;
        previa.emissor = "ITAU_PRINT";
        previa.importadaPor = quem;
        previa.persist();
        return previa;
    }

    /** O total da prévia é sempre a soma do que ela tem — não há total impresso. */
    private void recalcularTotal(Fatura previa) {
        BigDecimal soma = Lancamento.daFatura(previa.id).stream()
                .map(l -> l.valor)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        previa.valorTotal = soma;
        previa.valorLancado = soma;
        previa.persist();
    }

    /** Converte a linha confirmada em lançamento, pelas mesmas regras do CSV. */
    private static LancamentoLido deLinha(Linha linha) {
        if (linha.data() == null || linha.valor() == null
                || linha.descricao() == null || linha.descricao().isBlank()) {
            throw new WebApplicationException(
                    "Toda linha precisa de data, descrição e valor.", 400);
        }
        String descricao = linha.descricao().strip();
        if (descricao.length() > 255) {
            descricao = descricao.substring(0, 255);
        }
        Integer atual = linha.parcelaAtual();
        Integer total = linha.parcelaTotal();
        if (total == null || total < 2 || atual == null || atual < 1 || atual > total) {
            atual = null;
            total = null;
        }
        BigDecimal valor = linha.valor().setScale(2, RoundingMode.HALF_UP);
        return new LancamentoLido(linha.data(), descricao, TextoFatura.normalizar(descricao),
                valor, null, null, atual, total,
                TextoFatura.classificar(descricao, valor), "print · confirmado à mão");
    }

    /**
     * Recolhe as atribuições da prévia daquele mês e a apaga.
     *
     * <p>É o mesmo movimento nos dois usos: a prévia seguinte e a fatura de
     * verdade partem do que as pessoas já tinham assumido. Sem uma prévia no mês,
     * devolve vazio e não faz nada.
     */
    @Transactional
    public Heranca consumir(LocalDate competencia) {
        Fatura previa = Fatura.previaDa(competencia);
        if (previa == null) {
            return Heranca.VAZIA;
        }
        Heranca heranca = capturar(previa.id);
        apagar(previa.id);
        return heranca;
    }

    /**
     * O que foi decidido por gente: {@code MANUAL} (a pessoa assumiu) e
     * {@code ADMIN} (o admin apontou o dono).
     *
     * <p>As origens automáticas ficam de fora de propósito. Elas são recalculadas
     * a cada subida a partir da própria fonte — o compromisso de parcelamento e o
     * dono padrão do cartão —, e carregar uma cópia velha faria uma regra
     * desligada no meio do mês continuar valendo por herança.
     */
    private Heranca capturar(Long previaId) {
        Map<Long, List<DivisaoLancamento>> divisoes = new HashMap<>();
        for (DivisaoLancamento d : DivisaoLancamento.daFatura(previaId)) {
            divisoes.computeIfAbsent(d.lancamento.getId(), k -> new ArrayList<>()).add(d);
        }

        Map<String, Deque<Atribuicao>> porChave = new HashMap<>();
        int total = 0;
        for (Lancamento l : Lancamento.daFatura(previaId)) {
            if (l.responsavel == null
                    || (l.origemAtribuicao != OrigemAtribuicao.MANUAL
                        && l.origemAtribuicao != OrigemAtribuicao.ADMIN)) {
                continue;
            }
            List<Parte> partes = divisoes.getOrDefault(l.id, List.of()).stream()
                    .map(d -> new Parte(d.usuario.getId(), d.valor))
                    .toList();
            porChave.computeIfAbsent(chaveDe(l), k -> new ArrayDeque<>())
                    .add(new Atribuicao(l.responsavel.getId(), l.origemAtribuicao, partes));
            total++;
        }
        return new Heranca(porChave, total);
    }

    /**
     * Apaga a prévia inteira, filha por filha.
     *
     * <p>Em consultas de massa, e não por {@code delete()} na entidade: o
     * cascateamento aqui é do banco ({@code ON DELETE CASCADE}), e apagar o pai
     * pela sessão deixaria os lançamentos que acabamos de ler pendurados no
     * contexto de persistência, prontos para brigar com os que vamos criar.
     *
     * <p>O compromisso de parcelamento entra na lista pelo mesmo motivo da
     * exclusão de fatura: a FK dele é {@code ON DELETE SET NULL}, e ele
     * sobreviveria órfão atribuindo parcelas a partir de uma prévia que não
     * existe mais. Pela regra do {@code AtribuicaoService} a prévia não cria
     * compromisso nenhum — a limpeza é o cinto de segurança.
     */
    private void apagar(Long previaId) {
        Acerto.delete("fatura.id = ?1", previaId);
        DivisaoLancamento.delete("lancamento.fatura.id = ?1", previaId);
        Reivindicacao.delete("lancamento.fatura.id = ?1", previaId);
        CompromissoParcelado.delete(
                "origemLancamento.id in (select l.id from Lancamento l where l.fatura.id = ?1)",
                previaId);
        Lancamento.delete("fatura.id = ?1", previaId);
        Fatura.delete("id = ?1", previaId);
    }
}
