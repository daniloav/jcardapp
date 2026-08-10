package br.com.jcard.service;

import br.com.jcard.model.DivisaoLancamento;
import br.com.jcard.model.Fatura;
import br.com.jcard.model.Lancamento;
import br.com.jcard.model.Usuario;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Quem deve o quê, lançamento a lançamento. É a única resposta a essa pergunta
 * no app: a conciliação e a tela do utilizador leem daqui, e por isso o valor
 * que a pessoa vê é sempre o mesmo que ela vai pagar.
 *
 * <p>Três caminhos, nesta ordem:
 * <ol>
 *   <li><b>Encargo</b> (IOF, anuidade, juros, ajuste) — rateado entre todos que
 *       usaram o cartão no mês, sempre, independente de quem estiver no campo
 *       {@code responsavel}. Ninguém "reivindica" IOF, e cobrar de um só o
 *       encargo de uma fatura que várias pessoas movimentaram seria arbitrário.</li>
 *   <li><b>Conta dividida</b> — existem partes gravadas: elas são a verdade, e a
 *       soma delas já reproduz o valor do lançamento.</li>
 *   <li><b>Lançamento comum</b> — inteiro para o responsável; sem responsável,
 *       para o titular.</li>
 * </ol>
 *
 * <p>Participante é quem tem pelo menos um lançamento <i>reivindicável</i> na
 * fatura, assumido sozinho ou como parte de uma conta dividida. Se ninguém
 * assumiu nada, o titular fica com tudo — inclusive os encargos.
 */
@ApplicationScoped
public class RateioService {

    private static final BigDecimal CENTAVO = new BigDecimal("0.01");

    /** Uma linha do rateio: {@code valor} do {@code lancamento} que é de {@code usuario}. */
    public record Parte(Long lancamentoId, Long usuarioId, BigDecimal valor) {
    }

    /**
     * Rateio completo da fatura.
     *
     * <p>A soma de todas as partes é sempre igual à soma dos lançamentos — é o
     * que sustenta a segunda invariante da conciliação.
     */
    public List<Parte> ratear(Fatura fatura, Usuario titular) {
        List<Lancamento> lancamentos = Lancamento.daFatura(fatura.getId());
        Map<Long, List<DivisaoLancamento>> divisoes = divisoesPorLancamento(fatura.getId());
        List<Long> participantes = participantes(lancamentos, divisoes, titular);

        List<Parte> partes = new ArrayList<>();
        for (Lancamento l : lancamentos) {
            if (l.tipo.rateavel()) {
                partes.addAll(ratearEntre(l, participantes));
                continue;
            }
            List<DivisaoLancamento> partesDoLancamento = divisoes.get(l.id);
            if (partesDoLancamento != null && !partesDoLancamento.isEmpty()) {
                for (DivisaoLancamento d : partesDoLancamento) {
                    partes.add(new Parte(l.id, d.usuario.getId(), d.valor));
                }
                continue;
            }
            Long dono = l.responsavel != null ? l.responsavel.getId() : titular.getId();
            partes.add(new Parte(l.id, dono, l.valor));
        }
        return partes;
    }

    /** O total de cada pessoa na fatura — o que vira {@code acerto.valorDevido}. */
    public Map<Long, BigDecimal> totaisPorUsuario(Fatura fatura, Usuario titular) {
        Map<Long, BigDecimal> totais = new HashMap<>();
        for (Parte p : ratear(fatura, titular)) {
            totais.merge(p.usuarioId(), p.valor(), BigDecimal::add);
        }
        return totais;
    }

    /**
     * Quem divide os encargos desta fatura, na ordem do rateio.
     *
     * <p>Público porque a conferência do admin precisa <b>nomear</b> quem está
     * dividindo: "a fatia é R$ 2,53 porque o IOF de R$ 7,57 está sendo dividido
     * entre estas três pessoas" responde a dúvida; "R$ 2,53" sozinho, não.
     */
    public List<Usuario> participantesDa(Fatura fatura, Usuario titular) {
        List<Long> ids = participantes(
                Lancamento.daFatura(fatura.getId()),
                divisoesPorLancamento(fatura.getId()),
                titular);
        // Lambda, e não `Usuario::findById`: a estática do Panache é reescrita
        // por bytecode na subclasse, e a referência de método prende a versão
        // base — que estoura com "did you forget to annotate your entity".
        return ids.stream().map(id -> (Usuario) Usuario.findById(id)).toList();
    }

    /**
     * Quem usou o cartão no mês, na ordem em que os centavos de sobra são
     * distribuídos: o titular primeiro, depois os demais por id.
     *
     * <p>O titular à frente é deliberado — quando um encargo não divide exato, a
     * sobra fica com o dono do cartão em vez de cair em quem o app achar primeiro.
     */
    List<Long> participantes(List<Lancamento> lancamentos,
                             Map<Long, List<DivisaoLancamento>> divisoes,
                             Usuario titular) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Lancamento l : lancamentos) {
            if (!l.tipo.reivindicavel()) {
                continue;
            }
            List<DivisaoLancamento> partes = divisoes.get(l.id);
            if (partes != null && !partes.isEmpty()) {
                partes.forEach(d -> ids.add(d.usuario.getId()));
            } else if (l.responsavel != null) {
                ids.add(l.responsavel.getId());
            }
        }
        if (ids.isEmpty()) {
            // Ninguém assumiu nada: não há entre quem ratear, e o titular
            // responde pela fatura inteira — inclusive pelos encargos.
            return List.of(titular.getId());
        }
        List<Long> ordenados = new ArrayList<>(ids);
        ordenados.sort(Comparator
                .comparing((Long id) -> !id.equals(titular.getId()))
                .thenComparing(id -> id));
        return ordenados;
    }

    private List<Parte> ratearEntre(Lancamento encargo, List<Long> participantes) {
        List<BigDecimal> valores = dividir(encargo.valor, participantes.size());
        List<Parte> partes = new ArrayList<>(participantes.size());
        for (int i = 0; i < participantes.size(); i++) {
            partes.add(new Parte(encargo.id, participantes.get(i), valores.get(i)));
        }
        return partes;
    }

    /**
     * Divide um valor em {@code n} partes que somam exatamente o valor.
     *
     * <p>R$ 10,00 em 3 sai 3,34 + 3,33 + 3,33, e não três vezes 3,33 — os
     * centavos de sobra vão para os primeiros da fila. Arredondar cada parte
     * isoladamente perderia (ou inventaria) dinheiro, e aí a fatura não fecharia.
     */
    List<BigDecimal> dividir(BigDecimal valor, int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("Divisão sem participantes.");
        }
        BigDecimal base = valor.divide(BigDecimal.valueOf(n), 2, RoundingMode.DOWN);
        BigDecimal resto = valor.subtract(base.multiply(BigDecimal.valueOf(n)));
        int centavos = resto.movePointRight(2).abs().setScale(0, RoundingMode.HALF_UP).intValue();
        BigDecimal passo = resto.signum() < 0 ? CENTAVO.negate() : CENTAVO;

        List<BigDecimal> valores = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            valores.add(i < centavos ? base.add(passo) : base);
        }
        return valores;
    }

    private Map<Long, List<DivisaoLancamento>> divisoesPorLancamento(Long faturaId) {
        Map<Long, List<DivisaoLancamento>> mapa = new HashMap<>();
        for (DivisaoLancamento d : DivisaoLancamento.daFatura(faturaId)) {
            mapa.computeIfAbsent(d.lancamento.getId(), k -> new ArrayList<>()).add(d);
        }
        return mapa;
    }
}
