package br.com.jcard.service;

import br.com.jcard.model.AcaoAuditoria;
import br.com.jcard.model.DivisaoLancamento;
import br.com.jcard.model.Lancamento;
import br.com.jcard.model.Usuario;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rachar uma conta entre várias pessoas — o jantar de sete que caiu num cartão só.
 *
 * <p>Quem divide é quem assumiu o lançamento (ou o admin), porque é ele que
 * sabe quem estava na mesa. Em troca, a regra é estrita: <b>a soma das partes
 * tem de reproduzir o valor do lançamento</b>, até o centavo. Aceitar uma
 * divisão que não fecha seria criar dinheiro que a fatura não tem, e a
 * conciliação abortaria o fechamento inteiro depois — melhor recusar aqui, com
 * uma mensagem que diz o que está faltando.
 *
 * <p>Quem recebeu uma parte que não reconhece usa o "não foi minha" de sempre:
 * a divisão inteira cai e o lançamento volta ao pool. Ninguém fica com uma
 * cobrança que não aceitou só porque outra pessoa o incluiu.
 */
@ApplicationScoped
public class DivisaoService {

    @Inject
    ConciliacaoService conciliacao;

    @Inject
    AuditoriaService auditoria;

    /** Uma parte pedida pela API, antes de virar linha. */
    public record Parte(Long usuarioId, BigDecimal valor) {
    }

    /**
     * Grava a divisão, substituindo a anterior se houver.
     *
     * @param quem quem está dividindo: o responsável pelo lançamento ou um admin
     */
    @Transactional
    public Lancamento dividir(Long lancamentoId, Usuario quem, boolean admin, List<Parte> partes) {
        Lancamento l = carregarDivisivel(lancamentoId, quem, admin);

        if (partes == null || partes.size() < 2) {
            throw new WebApplicationException(
                    "Uma divisão precisa de pelo menos duas pessoas. Se a conta é de uma "
                    + "pessoa só, basta ela assumir o lançamento.", 400);
        }

        List<DivisaoLancamento> novas = validar(l, partes, quem);

        DivisaoLancamento.apagarDo(l.id);
        DivisaoLancamento.flush();
        novas.forEach(d -> d.persist());

        conciliacao.recalcularAcertos(l.fatura.getId());
        auditoria.registrar(quem, AcaoAuditoria.DIVIDIR, "Lancamento", l.id,
                l.descricao + " dividido entre " + novas.size() + " pessoa(s)");
        return l;
    }

    /** Desfaz a divisão: o lançamento volta inteiro para quem o assumiu. */
    @Transactional
    public Lancamento juntar(Long lancamentoId, Usuario quem, boolean admin) {
        Lancamento l = carregarDivisivel(lancamentoId, quem, admin);
        if (DivisaoLancamento.doLancamento(l.id).isEmpty()) {
            throw new WebApplicationException("Este lançamento não está dividido.", 409);
        }
        DivisaoLancamento.apagarDo(l.id);
        conciliacao.recalcularAcertos(l.fatura.getId());
        auditoria.registrar(quem, AcaoAuditoria.DIVIDIR, "Lancamento", l.id,
                l.descricao + " — divisão desfeita");
        return l;
    }

    /**
     * Sugestão de partes iguais, para o app oferecer o caso mais comum pronto.
     * Os centavos de sobra ficam com quem está dividindo.
     */
    public List<BigDecimal> partesIguais(BigDecimal valor, int pessoas) {
        BigDecimal base = valor.divide(BigDecimal.valueOf(pessoas), 2, RoundingMode.DOWN);
        BigDecimal sobra = valor.subtract(base.multiply(BigDecimal.valueOf(pessoas)));
        List<BigDecimal> valores = new ArrayList<>(pessoas);
        for (int i = 0; i < pessoas; i++) {
            valores.add(i == 0 ? base.add(sobra) : base);
        }
        return valores;
    }

    // ------------------------------------------------------------ internos --

    private List<DivisaoLancamento> validar(Lancamento l, List<Parte> partes, Usuario quem) {
        List<DivisaoLancamento> novas = new ArrayList<>(partes.size());
        Set<Long> vistos = new HashSet<>();
        BigDecimal soma = BigDecimal.ZERO;

        for (Parte p : partes) {
            if (p.usuarioId() == null || p.valor() == null) {
                throw new WebApplicationException("Informe a pessoa e o valor de cada parte.", 400);
            }
            if (!vistos.add(p.usuarioId())) {
                throw new WebApplicationException(
                        "A mesma pessoa aparece duas vezes na divisão.", 400);
            }
            Usuario u = Usuario.findById(p.usuarioId());
            if (u == null || !u.ativo || !u.utilizador) {
                throw new WebApplicationException(
                        "Só dá para dividir com utilizadores ativos.", 400);
            }
            BigDecimal valor = p.valor().setScale(2, RoundingMode.HALF_UP);
            if (valor.signum() == 0) {
                throw new WebApplicationException(
                        "Parte de R$ 0,00 para " + u.nome + ": tire a pessoa da divisão.", 400);
            }
            // Parte de sinal contrário ao lançamento faria uma pessoa "receber"
            // de uma compra. Quase sempre é dígito trocado, nunca a intenção.
            if (valor.signum() != l.valor.signum()) {
                throw new WebApplicationException(
                        "A parte de " + u.nome + " tem sinal contrário ao do lançamento.", 400);
            }
            soma = soma.add(valor);

            DivisaoLancamento d = new DivisaoLancamento();
            d.lancamento = l;
            d.usuario = u;
            d.valor = valor;
            d.criadoPor = quem;
            novas.add(d);
        }

        if (soma.compareTo(l.valor) != 0) {
            BigDecimal falta = l.valor.subtract(soma);
            throw new WebApplicationException(
                    "As partes somam R$ " + soma + " e o lançamento é R$ " + l.valor
                    + " — " + (falta.signum() > 0 ? "faltam" : "sobram")
                    + " R$ " + falta.abs() + ".", 422);
        }
        return novas;
    }

    private Lancamento carregarDivisivel(Long id, Usuario quem, boolean admin) {
        Lancamento l = Lancamento.findById(id);
        if (l == null) {
            throw new WebApplicationException("Lançamento não encontrado.", 404);
        }
        if (!l.fatura.aberta()) {
            throw new WebApplicationException(
                    "A fatura não está mais em avaliação — não dá para mexer na divisão.", 409);
        }
        if (!l.tipo.reivindicavel()) {
            throw new WebApplicationException(
                    "Encargos já são divididos entre todos que usaram o cartão; "
                    + "não há o que rachar aqui.", 409);
        }
        if (l.responsavel == null) {
            throw new WebApplicationException(
                    "Assuma o lançamento antes de dividi-lo.", 409);
        }
        if (!admin && !l.responsavel.getId().equals(quem.id)) {
            throw new WebApplicationException(
                    "Só quem assumiu o lançamento pode dividi-lo.", 403);
        }
        return l;
    }
}
