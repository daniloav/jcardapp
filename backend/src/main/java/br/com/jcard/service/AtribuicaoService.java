package br.com.jcard.service;

import br.com.jcard.model.Cartao;
import br.com.jcard.model.CompromissoParcelado;
import br.com.jcard.model.Lancamento;
import br.com.jcard.model.OrigemAtribuicao;
import br.com.jcard.model.Usuario;
import br.com.jcard.parser.ChaveParcelamento;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import org.jboss.logging.Logger;

/**
 * Decide, na importação, quem já nasce dono de cada lançamento.
 *
 * <p>Ordem de precedência:
 * <ol>
 *   <li><b>Compromisso parcelado</b> — assumiu a 1/10, leva as outras nove.</li>
 *   <li><b>Dono padrão do cartão</b> — adicional que é sempre da mesma pessoa.</li>
 *   <li>Nada casou: fica no <b>pool</b> para alguém reivindicar.</li>
 * </ol>
 *
 * <p>Na dúvida o lançamento fica no pool. Atribuir errado é pior que pedir para
 * a pessoa confirmar: gera cobrança indevida e desgaste entre as pessoas.
 */
@ApplicationScoped
public class AtribuicaoService {

    private static final Logger LOG = Logger.getLogger(AtribuicaoService.class);

    /**
     * Tolerância ao casar o valor de uma parcela com o compromisso.
     *
     * <p>Existe porque parcelamento raramente divide exato (R$ 100 em 3x sai
     * 33,34 + 33,33 + 33,33) e porque compra internacional varia com o câmbio.
     * Fora dessa faixa, provavelmente é outra compra na mesma loja — deixa no pool.
     */
    private static final BigDecimal TOLERANCIA_ABSOLUTA = new BigDecimal("1.00");
    private static final BigDecimal TOLERANCIA_RELATIVA = new BigDecimal("0.05"); // 5%

    /** Aplica as regras automáticas. Devolve a origem usada, ou {@code null} se ficou no pool. */
    public OrigemAtribuicao aplicar(Lancamento l) {
        if (l.parcelado()) {
            l.chaveParcelamento = ChaveParcelamento.de(
                    l.descricaoNormalizada, l.final4, l.parcelaTotal);

            CompromissoParcelado c = CompromissoParcelado.ativoPorChave(l.chaveParcelamento);
            if (c != null) {
                if (valorCompativel(l.valor, c.valorParcela)) {
                    l.atribuirA(c.usuario, OrigemAtribuicao.HERDADA_PARCELA);
                    c.registrarParcela(l.parcelaAtual);
                    c.persist();
                    return OrigemAtribuicao.HERDADA_PARCELA;
                }
                LOG.infof("Parcela %d/%d de '%s' com valor %s destoa do compromisso (%s) "
                                + "— mantida no pool para conferência.",
                        l.parcelaAtual, l.parcelaTotal, l.descricaoNormalizada,
                        l.valor, c.valorParcela);
            }
        }

        if (l.final4 != null) {
            Cartao cartao = Cartao.porFinal(l.final4);
            if (cartao != null && cartao.ativo && cartao.donoPadrao != null) {
                l.atribuirA(cartao.donoPadrao, OrigemAtribuicao.REGRA_CARTAO);
                return OrigemAtribuicao.REGRA_CARTAO;
            }
        }

        return null;
    }

    /**
     * Cria (ou reaproveita) o compromisso que faz as próximas parcelas caírem
     * sozinhas no nome de quem assumiu esta.
     *
     * <p>Chamado quando uma reivindicação de lançamento parcelado é aceita.
     */
    public void registrarCompromisso(Lancamento l, Usuario dono) {
        if (!l.parcelado()) {
            return;
        }
        if (l.chaveParcelamento == null) {
            l.chaveParcelamento = ChaveParcelamento.de(
                    l.descricaoNormalizada, l.final4, l.parcelaTotal);
        }

        CompromissoParcelado c = CompromissoParcelado.find(
                "chaveParcelamento", l.chaveParcelamento).firstResult();

        if (c == null) {
            c = new CompromissoParcelado();
            c.chaveParcelamento = l.chaveParcelamento;
            c.descricaoNormalizada = l.descricaoNormalizada;
            c.final4 = l.final4;
            c.parcelaTotal = l.parcelaTotal;
            c.valorParcela = l.valor;
            c.origemLancamento = l;
        }
        // Se o dono mudou (admin arbitrou), o compromisso segue o dono novo.
        c.usuario = dono;
        c.ativo = true;
        c.registrarParcela(l.parcelaAtual == null ? 1 : l.parcelaAtual);
        c.persist();
    }

    /** Desfaz o compromisso quando a pessoa desiste do lançamento que o criou. */
    public void encerrarCompromisso(Lancamento l) {
        if (l.chaveParcelamento == null) {
            return;
        }
        CompromissoParcelado c = CompromissoParcelado.ativoPorChave(l.chaveParcelamento);
        if (c != null && c.origemLancamento != null && c.origemLancamento.getId().equals(l.id)) {
            c.ativo = false;
            c.persist();
        }
    }

    /** Aceita diferença de até R$ 1,00 ou 5% — o que for maior. */
    boolean valorCompativel(BigDecimal valor, BigDecimal esperado) {
        if (valor == null || esperado == null) {
            return false;
        }
        BigDecimal diferenca = valor.subtract(esperado).abs();
        BigDecimal limite = esperado.abs().multiply(TOLERANCIA_RELATIVA).max(TOLERANCIA_ABSOLUTA);
        return diferenca.compareTo(limite) <= 0;
    }
}
