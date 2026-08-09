package br.com.jcard.service;

import br.com.jcard.model.AcaoAuditoria;
import br.com.jcard.model.Acerto;
import br.com.jcard.model.ComprovantePagamento;
import br.com.jcard.model.StatusAcerto;
import br.com.jcard.model.StatusFatura;
import br.com.jcard.model.Usuario;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Ciclo de quitação: a pessoa confere o valor, aceita, paga e manda o
 * comprovante; o admin confere e confirma.
 *
 * <pre>
 *   ABERTO --aceitar--> ACEITO --pagar(+comprovante)--> INFORMADO --admin--> CONFIRMADO
 * </pre>
 *
 * <p>O aceite existe para a discussão sobre o valor acontecer <b>antes</b> de o
 * dinheiro sair, e só é liberado com a fatura conciliada — enquanto ela está em
 * avaliação o total ainda muda toda vez que alguém assume um lançamento ou um
 * encargo é rerrateado, e aceitar um número que vai mudar não significa nada.
 *
 * <p>O comprovante é obrigatório: é o único registro de que o PIX aconteceu. A
 * confirmação continua sendo do admin — o app não tem como saber se o dinheiro caiu.
 */
@ApplicationScoped
public class AcertoService {

    /**
     * Teto por comprovante. Um print de PIX cabe folgado; o limite existe porque
     * o arquivo vai para o Postgres do Neon, que tem 0,5 GB no plano gratuito.
     */
    static final int TAMANHO_MAXIMO = 3 * 1024 * 1024;

    /** Só o que dá para conferir com o olho: imagem ou PDF. */
    private static final List<String> TIPOS_ACEITOS =
            List.of("image/jpeg", "image/png", "image/webp", "image/heic", "application/pdf");

    @Inject
    NotificacaoService notificacao;

    @Inject
    AuditoriaService auditoria;

    /** "Conferi o total e concordo." Abre o formulário de pagamento. */
    @Transactional
    public Acerto aceitar(Long faturaId, Usuario quem) {
        Acerto a = meu(faturaId, quem);
        exigirFaturaFechavel(a);
        if (a.status == StatusAcerto.CONFIRMADO || a.status == StatusAcerto.INFORMADO) {
            throw new WebApplicationException("Este acerto já passou do aceite.", 409);
        }
        a.status = StatusAcerto.ACEITO;
        a.aceitoEm = LocalDateTime.now();
        a.persist();
        auditoria.registrar(quem, AcaoAuditoria.ACEITAR_VALOR, "Acerto", a.id,
                "R$ " + a.valorDevido);
        return a;
    }

    /**
     * O utilizador diz que pagou e anexa o comprovante. Não confirma nada
     * sozinho — quem confere se o dinheiro caiu é o admin.
     */
    @Transactional
    public Acerto informarPagamento(Long faturaId, Usuario quem, LocalDate pagoEm,
                                    String observacao, byte[] arquivo,
                                    String nomeArquivo, String tipoArquivo) {
        Acerto a = meu(faturaId, quem);
        exigirFaturaFechavel(a);
        if (a.status == StatusAcerto.CONFIRMADO) {
            throw new WebApplicationException("Este acerto já foi confirmado.", 409);
        }
        if (a.status == StatusAcerto.ABERTO) {
            throw new WebApplicationException(
                    "Confira e aceite o valor antes de declarar o pagamento.", 409);
        }

        salvarComprovante(a, arquivo, nomeArquivo, tipoArquivo);

        a.status = StatusAcerto.INFORMADO;
        a.informadoEm = LocalDateTime.now();
        a.pagoEm = pagoEm == null ? LocalDate.now() : pagoEm;
        a.observacao = observacao;
        a.persist();
        auditoria.registrar(quem, AcaoAuditoria.INFORMAR_PAGAMENTO, "Acerto", a.id,
                "R$ " + a.valorDevido + " · comprovante " + nomeArquivo);
        notificacao.pagamentoInformado(a);
        return a;
    }

    /** O admin confirma que o dinheiro entrou. */
    @Transactional
    public Acerto confirmarPagamento(Long acertoId, Usuario admin) {
        Acerto a = buscar(acertoId);
        if (a.status == StatusAcerto.CONFIRMADO) {
            return a;
        }
        a.status = StatusAcerto.CONFIRMADO;
        a.confirmadoEm = LocalDateTime.now();
        a.confirmadoPor = admin;
        a.persist();
        auditoria.registrar(admin, AcaoAuditoria.CONFIRMAR_PAGAMENTO, "Acerto", a.id,
                a.usuario.nome + " · R$ " + a.valorDevido);
        notificacao.pagamentoConfirmado(a);
        return a;
    }

    /** Desfaz uma confirmação feita por engano. O comprovante fica. */
    @Transactional
    public Acerto reabrir(Long acertoId, Usuario admin) {
        Acerto a = buscar(acertoId);
        a.status = StatusAcerto.ABERTO;
        a.confirmadoEm = null;
        a.confirmadoPor = null;
        a.informadoEm = null;
        a.aceitoEm = null;
        a.persist();
        auditoria.registrar(admin, AcaoAuditoria.ATUALIZAR, "Acerto", a.id, "reaberto");
        return a;
    }

    /**
     * O comprovante em si. Só o dono do acerto e o admin veem — é documento
     * bancário de outra pessoa.
     */
    public ComprovantePagamento comprovante(Long acertoId, Usuario quem, boolean admin) {
        Acerto a = buscar(acertoId);
        if (!admin && !a.usuario.getId().equals(quem.id)) {
            throw new WebApplicationException("Este comprovante não é seu.", 403);
        }
        ComprovantePagamento c = ComprovantePagamento.doAcerto(acertoId);
        if (c == null) {
            throw new WebApplicationException("Este acerto não tem comprovante.", 404);
        }
        return c;
    }

    // ------------------------------------------------------------ internos --

    private void salvarComprovante(Acerto a, byte[] arquivo, String nome, String tipo) {
        if (arquivo == null || arquivo.length == 0) {
            throw new WebApplicationException(
                    "Anexe o comprovante do PIX ou da transferência.", 400);
        }
        if (arquivo.length > TAMANHO_MAXIMO) {
            throw new WebApplicationException(
                    "O comprovante tem " + (arquivo.length / (1024 * 1024))
                    + " MB e o limite é 3 MB. Mande o print em vez do PDF do banco.", 413);
        }
        String tipoNormalizado = tipo == null ? "" : tipo.toLowerCase(Locale.ROOT).strip();
        if (!TIPOS_ACEITOS.contains(tipoNormalizado)) {
            throw new WebApplicationException(
                    "Mande o comprovante como imagem (print) ou PDF.", 415);
        }

        // Reenviar substitui: a pessoa mandou o print errado e corrige.
        ComprovantePagamento c = ComprovantePagamento.doAcerto(a.id);
        if (c == null) {
            c = new ComprovantePagamento();
            c.acerto = a;
        }
        c.nome = nome == null || nome.isBlank() ? "comprovante" : nome;
        if (c.nome.length() > 255) {
            c.nome = c.nome.substring(0, 255);
        }
        c.tipo = tipoNormalizado;
        c.tamanho = arquivo.length;
        c.conteudo = arquivo;
        c.enviadoEm = LocalDateTime.now();
        c.persist();
    }

    /**
     * O aceite e o pagamento só existem com o rateio congelado. Antes disso o
     * valor ainda muda, e cobrar por um número provisório é o erro que o app
     * inteiro tenta evitar.
     */
    private void exigirFaturaFechavel(Acerto a) {
        StatusFatura status = a.fatura.status;
        if (status != StatusFatura.CONCILIADA && status != StatusFatura.FECHADA) {
            throw new WebApplicationException(
                    "A fatura ainda está em avaliação: o seu total pode mudar enquanto "
                    + "houver lançamento sem dono. Aguarde o administrador conciliar.", 409);
        }
    }

    private Acerto meu(Long faturaId, Usuario quem) {
        Acerto a = Acerto.de(faturaId, quem.id);
        if (a == null) {
            throw new WebApplicationException("Você não tem acerto nesta fatura.", 404);
        }
        return a;
    }

    private Acerto buscar(Long acertoId) {
        Acerto a = Acerto.findById(acertoId);
        if (a == null) {
            throw new WebApplicationException("Acerto não encontrado.", 404);
        }
        return a;
    }
}
