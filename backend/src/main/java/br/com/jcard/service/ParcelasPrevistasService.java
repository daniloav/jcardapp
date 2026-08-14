package br.com.jcard.service;

import br.com.jcard.model.CompromissoParcelado;
import br.com.jcard.model.Fatura;
import br.com.jcard.model.Lancamento;
import br.com.jcard.model.Usuario;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * O que o app <b>já sabe</b> que vai entrar no mês que ainda não fechou: as
 * parcelas dos parcelamentos que alguém assumiu em faturas anteriores.
 *
 * <p>Existe porque "parcelamento gruda" já valia — mas só aparecia quando o
 * arquivo chegava. Quem assumiu a 1/10 da geladeira segue devendo nove parcelas
 * e não tinha onde ver isso: a prévia do mês começava vazia e ia enchendo com o
 * CSV, como se o mês não tivesse compromisso nenhum. Prever é literal aqui — o
 * compromisso diz o dono, a parcela e o valor, e não há palpite envolvido.
 *
 * <p>Três decisões sustentam o desenho:
 * <ol>
 *   <li><b>Nada é gravado.</b> A previsão sai do {@link CompromissoParcelado} a
 *       cada leitura, pela mesma razão do encargo rateado: um lançamento
 *       fantasma no banco viraria pool, viraria reivindicação, viraria divisão —
 *       e a fatura de verdade herdaria uma compra que nunca existiu.</li>
 *   <li><b>Quando o arquivo traz a parcela, a previsão some.</b> É o batimento:
 *       o lançamento de verdade manda, com o valor de verdade. Somar os dois
 *       cobraria a parcela duas vezes.</li>
 *   <li><b>Previsão não é cobrança.</b> Vale só para o mês em aberto. Fechada a
 *       fatura daquela competência, o que vale é o que veio no arquivo — parcela
 *       prometida que não chegou é assunto do mês seguinte, não uma linha a
 *       mais numa fatura conciliada.</li>
 * </ol>
 */
@ApplicationScoped
public class ParcelasPrevistasService {

    /**
     * Uma parcela que o mês em aberto ainda vai receber.
     *
     * @param valor  o valor da parcela que criou o compromisso. É estimativa: a
     *               próxima pode variar alguns centavos (R$ 100 em 3x sai
     *               33,34 + 33,33) ou o câmbio, em compra internacional. A tela
     *               diz isso — precisão de centavo aqui seria promessa falsa.
     * @param jaVeio nunca é {@code true} no que sai daqui: quem já veio deixou de
     *               ser previsão. O campo existe para o relatório do batimento,
     *               que precisa contar as duas metades.
     */
    public record Prevista(Long compromissoId, String chaveParcelamento,
                           String descricaoNormalizada, int parcela, int parcelaTotal,
                           BigDecimal valor, Long usuarioId, String usuarioNome,
                           boolean jaVeio) {

        Prevista comoVinda() {
            return new Prevista(compromissoId, chaveParcelamento, descricaoNormalizada,
                    parcela, parcelaTotal, valor, usuarioId, usuarioNome, true);
        }
    }

    /**
     * Tudo que os compromissos ativos prometem para o próximo mês, sem olhar o
     * que já chegou.
     *
     * <p>Dono desativado no meio do parcelamento fica de fora: a conta dele volta
     * para o pool quando o arquivo chegar (é o que a herança da prévia já faz), e
     * anunciar a parcela no nome de quem não usa mais o app só criaria uma
     * previsão que a importação vai desmentir.
     */
    public List<Prevista> prometidas() {
        List<Prevista> previstas = new ArrayList<>();
        for (CompromissoParcelado c : CompromissoParcelado.ativos()) {
            Usuario dono = c.usuario;
            if (dono == null || !dono.ativo) {
                continue;
            }
            int parcela = c.proximaParcela();
            if (parcela > c.parcelaTotal) {
                continue;
            }
            previstas.add(new Prevista(c.id, c.chaveParcelamento, c.descricaoNormalizada,
                    parcela, c.parcelaTotal, c.valorParcela, dono.getId(), dono.nome, false));
        }
        return previstas;
    }

    /**
     * O que ainda falta chegar na competência — a previsão depois do batimento.
     *
     * <p>Vazio quando a fatura de verdade daquele mês já foi importada: ali o
     * arquivo é a verdade, e o compromisso já teve a parcela registrada por ele.
     */
    public List<Prevista> doMesEmAberto(LocalDate competencia) {
        LocalDate mes = competencia.withDayOfMonth(1);
        if (Fatura.definitivaDa(mes) != null) {
            return List.of();
        }
        Fatura previa = Fatura.previaDa(mes);
        Set<String> jaVieram = previa == null
                ? Set.of()
                : Lancamento.chavesParceladasDaFatura(previa.id);
        return prometidas().stream()
                .filter(p -> !jaVieram.contains(p.chaveParcelamento()))
                .toList();
    }

    /**
     * As parcelas previstas <b>desta pessoa</b>.
     *
     * <p>A privacidade do app vale aqui igual ao resto: cada um vê as próprias
     * contas e o pool, nunca o que outra pessoa assumiu — e uma parcela prevista
     * é exatamente uma conta que outra pessoa assumiu.
     */
    public List<Prevista> doMesEmAberto(LocalDate competencia, Long usuarioId) {
        return doMesEmAberto(competencia).stream()
                .filter(p -> p.usuarioId().equals(usuarioId))
                .toList();
    }

    /** Soma das parcelas de uma lista, sempre com duas casas. */
    public static BigDecimal somar(List<Prevista> previstas) {
        return previstas.stream()
                .map(Prevista::valor)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    // ---------------------------------------------------------- batimento --

    /**
     * O confronto entre o que era esperado e o que o arquivo trouxe.
     *
     * <p>É o número que o admin não tem como ver sozinho: ele sobe o CSV e a
     * tela some com a previsão, sem dizer se a parcela chegou ou se ela ficou
     * para trás. Uma parcela que não chegou pode ser mês sem cobrança, mudança
     * de descrição no banco ou parcelamento quitado por fora — todas merecem
     * conferência, nenhuma é erro do app.
     */
    public record Batimento(List<Prevista> conferidas, List<Prevista> ausentes) {
    }

    /**
     * Compara o que os compromissos prometiam com as chaves que a leitura nova
     * trouxe.
     *
     * @param esperadas o retorno de {@link #prometidas()} tirado <b>antes</b> da
     *                  subida: depois dela o compromisso continua igual (a prévia
     *                  não escreve nele), mas passar a lista deixa explícito que
     *                  os dois lados do batimento são da mesma foto
     */
    public Batimento bater(List<Prevista> esperadas, Set<String> chavesLidas) {
        List<Prevista> conferidas = new ArrayList<>();
        List<Prevista> ausentes = new ArrayList<>();
        for (Prevista p : esperadas) {
            if (chavesLidas.contains(p.chaveParcelamento())) {
                conferidas.add(p.comoVinda());
            } else {
                ausentes.add(p);
            }
        }
        return new Batimento(conferidas, ausentes);
    }
}
