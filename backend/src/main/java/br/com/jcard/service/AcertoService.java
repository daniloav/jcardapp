package br.com.jcard.service;

import br.com.jcard.model.AcaoAuditoria;
import br.com.jcard.model.Acerto;
import br.com.jcard.model.ComprovantePagamento;
import br.com.jcard.model.PagamentoAcerto;
import br.com.jcard.model.StatusAcerto;
import br.com.jcard.model.StatusFatura;
import br.com.jcard.model.Usuario;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import java.math.BigDecimal;
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
 *
 * <p>Existe um atalho, e ele é do admin: quem pagou e não abriu o app ficaria com
 * o acerto {@code ABERTO} para sempre. Aí ele dá a <b>baixa manual</b>
 * ({@link #registrarBaixa}), sem comprovante, com o nome dele gravado no
 * pagamento e um e-mail para a pessoa — ver {@link PagamentoAcerto#registradoPor}.
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

    /**
     * "Conferi o total e concordo." Abre o formulário de pagamento.
     *
     * <p>Vale também para quem <b>já pagou</b> e viu o valor subir depois: o
     * aceite é sobre um número, e o número mudou. Enquanto sobrar saldo, a
     * pessoa concorda de novo antes de mandar o complemento — é o mesmo
     * princípio de "pagar exige aceitar antes", aplicado à diferença.
     */
    @Transactional
    public Acerto aceitar(Long faturaId, Usuario quem) {
        Acerto a = meu(faturaId, quem);
        exigirFaturaFechavel(a);
        if (a.status == StatusAcerto.CONFIRMADO) {
            throw new WebApplicationException("Este acerto já foi confirmado.", 409);
        }
        if (a.aceitoEm != null && saldo(a).signum() <= 0) {
            throw new WebApplicationException("Este acerto já passou do aceite.", 409);
        }
        // Com transferência já declarada o acerto continua INFORMADO: ele tem
        // dinheiro dentro, e voltar para ACEITO esconderia isso da tela do admin.
        boolean temPagamento = !PagamentoAcerto.doAcerto(a.id).isEmpty();
        a.status = temPagamento ? StatusAcerto.INFORMADO : StatusAcerto.ACEITO;
        a.aceitoEm = LocalDateTime.now();
        a.persist();
        auditoria.registrar(quem, AcaoAuditoria.ACEITAR_VALOR, "Acerto", a.id,
                "R$ " + a.valorDevido + (temPagamento ? " (novo valor, com saldo em aberto)" : ""));
        return a;
    }

    /**
     * O utilizador declara <b>uma</b> transferência, com valor e comprovante.
     *
     * <p>Pode ser a quitação inteira ou uma parte. Quando o valor devido sobe
     * depois de a pessoa já ter pago — o divisor do encargo mudou, o admin
     * atribuiu mais um lançamento —, ela declara um pagamento complementar e o
     * primeiro continua registrado com a prova dele.
     *
     * <p>Não confirma nada sozinho: quem confere se o dinheiro caiu é o admin.
     */
    @Transactional
    public PagamentoAcerto informarPagamento(Long faturaId, Usuario quem, BigDecimal valor,
                                             LocalDate pagoEm, String observacao, byte[] arquivo,
                                             String nomeArquivo, String tipoArquivo) {
        Acerto a = meu(faturaId, quem);
        exigirFaturaFechavel(a);
        if (a.status == StatusAcerto.CONFIRMADO) {
            throw new WebApplicationException("Este acerto já foi confirmado.", 409);
        }
        // O aceite, e não o status: quando o valor muda, o aceite anterior é
        // anulado mesmo que a pessoa já tenha declarado uma transferência — ela
        // concorda com o número novo antes de mandar a diferença.
        if (a.aceitoEm == null) {
            throw new WebApplicationException(
                    "Confira e aceite o valor antes de declarar o pagamento.", 409);
        }

        BigDecimal declarado = valorDeclarado(a, valor);

        PagamentoAcerto p = new PagamentoAcerto();
        p.acerto = a;
        p.valor = declarado;
        p.pagoEm = pagoEm == null ? LocalDate.now() : pagoEm;
        p.observacao = observacao;
        p.informadoEm = LocalDateTime.now();
        p.persist();

        salvarComprovante(p, arquivo, nomeArquivo, tipoArquivo);

        // Os campos do acerto seguem o último pagamento: são o que a listagem
        // mostra sem abrir a lista de transferências.
        a.status = StatusAcerto.INFORMADO;
        a.informadoEm = p.informadoEm;
        a.pagoEm = p.pagoEm;
        a.observacao = observacao;
        a.persist();

        auditoria.registrar(quem, AcaoAuditoria.INFORMAR_PAGAMENTO, "Acerto", a.id,
                "R$ " + declarado + " de R$ " + a.valorDevido
                + " · comprovante " + nomeArquivo);
        notificacao.pagamentoInformado(a, declarado, saldo(a));
        return p;
    }

    /**
     * O admin registra uma transferência <b>em nome</b> do utilizador, sem
     * comprovante — a baixa manual.
     *
     * <p>Parte das pessoas paga o PIX e nunca abre o app para mandar o print.
     * Sem esta porta o acerto delas ficaria {@code ABERTO} para sempre e a
     * fatura nunca fecharia, mesmo com o dinheiro na conta. A prova aqui é o
     * extrato que o admin está olhando, e é por isso que a baixa já nasce
     * confirmada: exigir que ele clicasse "recebi" no próprio registro seria
     * teatro. O que fica registrado é <b>quem</b> registrou
     * ({@link PagamentoAcerto#registradoPor}), porque sem comprovante essa é a
     * única prova que existe.
     *
     * <p>Não exige aceite: o aceite serve para a discussão sobre o valor
     * acontecer antes de o dinheiro sair, e aqui ele já saiu. Exige a fatura
     * conciliada como qualquer pagamento — dar baixa contra um total que ainda
     * muda registraria a quitação de um número provisório.
     *
     * <p>O utilizador é avisado por e-mail: lançaram uma baixa no nome dele, e
     * ele precisa poder contestar.
     */
    @Transactional
    public PagamentoAcerto registrarBaixa(Long acertoId, Usuario admin, BigDecimal valor,
                                          LocalDate pagoEm, String observacao) {
        Acerto a = buscar(acertoId);
        exigirFaturaFechavel(a);
        if (a.status == StatusAcerto.CONFIRMADO) {
            throw new WebApplicationException(
                    "Este acerto já está confirmado. Reabra antes de registrar outro pagamento.",
                    409);
        }

        BigDecimal declarado = valorDeclarado(a, valor);

        PagamentoAcerto p = new PagamentoAcerto();
        p.acerto = a;
        p.valor = declarado;
        p.pagoEm = pagoEm == null ? LocalDate.now() : pagoEm;
        p.observacao = observacao;
        p.informadoEm = LocalDateTime.now();
        p.registradoPor = admin;
        // Quem registra é quem confere: a baixa nasce confirmada.
        p.confirmadoEm = p.informadoEm;
        p.confirmadoPor = admin;
        p.persist();

        a.status = StatusAcerto.INFORMADO;
        a.informadoEm = p.informadoEm;
        a.pagoEm = p.pagoEm;
        a.observacao = observacao;
        a.persist();

        auditoria.registrar(admin, AcaoAuditoria.REGISTRAR_BAIXA, "Acerto", a.id,
                a.usuario.nome + " · R$ " + declarado + " de R$ " + a.valorDevido
                + " · sem comprovante"
                + (observacao == null || observacao.isBlank() ? "" : " · " + observacao));
        notificacao.baixaRegistradaPeloAdmin(a, declarado, saldo(a), admin);
        quitarSeFechou(a, false);
        return p;
    }

    /**
     * Desfaz uma baixa manual — a saída para o valor digitado errado.
     *
     * <p>Só a baixa manual: o pagamento declarado pelo utilizador tem
     * comprovante, e apagar a prova de que o dinheiro saiu seria negá-lo. A
     * baixa não tem prova nenhuma além do dedo do admin, então é justamente ela
     * que precisa de volta.
     *
     * <p>Se o acerto tinha fechado por causa dela, ele volta a ficar aberto — o
     * saldo é derivado, e deixar {@code CONFIRMADO} com dinheiro faltando diria
     * que a pessoa pagou o que ela não pagou. O aceite fica: o valor devido não
     * mudou, e anulá-lo faria a pessoa reconferir um número por erro que não
     * foi dela.
     */
    @Transactional
    public Acerto excluirBaixa(Long pagamentoId, Usuario admin) {
        PagamentoAcerto p = PagamentoAcerto.findById(pagamentoId);
        if (p == null) {
            throw new WebApplicationException("Pagamento não encontrado.", 404);
        }
        if (!p.baixaManual()) {
            throw new WebApplicationException(
                    "Este pagamento foi declarado pelo utilizador e tem comprovante: "
                    + "apagá-lo seria apagar a prova de que o dinheiro saiu. "
                    + "Reabra o acerto se o valor mudou.", 409);
        }
        Acerto a = p.acerto;
        BigDecimal valor = p.valor;
        p.delete();
        PagamentoAcerto.getEntityManager().flush();

        List<PagamentoAcerto> restantes = PagamentoAcerto.doAcerto(a.id);
        if (saldo(a).signum() != 0 && a.status == StatusAcerto.CONFIRMADO) {
            a.status = restantes.isEmpty() ? StatusAcerto.ABERTO : StatusAcerto.INFORMADO;
            a.confirmadoEm = null;
            a.confirmadoPor = null;
        } else if (restantes.isEmpty() && a.status == StatusAcerto.INFORMADO) {
            a.status = a.aceitoEm == null ? StatusAcerto.ABERTO : StatusAcerto.ACEITO;
        }
        // Os campos do acerto seguem o último pagamento: sem nenhum, não há o
        // que apontar — e deixar a observação da baixa desfeita descreveria na
        // listagem um pagamento que não existe mais.
        PagamentoAcerto ultimo = restantes.isEmpty() ? null : restantes.get(restantes.size() - 1);
        a.informadoEm = ultimo == null ? null : ultimo.informadoEm;
        a.pagoEm = ultimo == null ? null : ultimo.pagoEm;
        a.observacao = ultimo == null ? null : ultimo.observacao;
        a.persist();

        auditoria.registrar(admin, AcaoAuditoria.EXCLUIR, "Acerto", a.id,
                "baixa desfeita · " + a.usuario.nome + " · R$ " + valor);
        return a;
    }

    /**
     * O admin confirma <b>uma</b> transferência.
     *
     * <p>Uma de cada vez porque é assim que ele confere: uma entrada por vez, no
     * extrato. O acerto só fica {@code CONFIRMADO} quando todas estão
     * confirmadas e o saldo zerou — confirmar o acerto inteiro com saldo em
     * aberto diria que a pessoa pagou o que ela não pagou.
     */
    @Transactional
    public PagamentoAcerto confirmarPagamento(Long pagamentoId, Usuario admin) {
        PagamentoAcerto p = PagamentoAcerto.findById(pagamentoId);
        if (p == null) {
            throw new WebApplicationException("Pagamento não encontrado.", 404);
        }
        if (!p.confirmado()) {
            p.confirmadoEm = LocalDateTime.now();
            p.confirmadoPor = admin;
            p.persist();
            auditoria.registrar(admin, AcaoAuditoria.CONFIRMAR_PAGAMENTO, "Acerto", p.acerto.getId(),
                    p.acerto.usuario.nome + " · R$ " + p.valor);
        }
        quitarSeFechou(p.acerto);
        return p;
    }

    /**
     * Desfaz a confirmação do acerto. Os pagamentos e os comprovantes ficam: o
     * dinheiro saiu, e apagar a prova disso seria negá-lo.
     *
     * <p>O acerto volta para {@code INFORMADO} se já houver transferência
     * declarada, ou para {@code ABERTO} se não houver — e o aceite cai nos dois
     * casos, porque é o valor que vai voltar a mudar.
     */
    @Transactional
    public Acerto reabrir(Long acertoId, Usuario admin) {
        Acerto a = buscar(acertoId);
        boolean temPagamento = !PagamentoAcerto.doAcerto(acertoId).isEmpty();
        a.status = temPagamento ? StatusAcerto.INFORMADO : StatusAcerto.ABERTO;
        a.confirmadoEm = null;
        a.confirmadoPor = null;
        a.aceitoEm = null;
        if (!temPagamento) {
            a.informadoEm = null;
        }
        a.persist();
        auditoria.registrar(admin, AcaoAuditoria.ATUALIZAR, "Acerto", a.id,
                temPagamento
                        ? "reaberto · " + PagamentoAcerto.doAcerto(acertoId).size()
                          + " pagamento(s) mantidos"
                        : "reaberto");
        return a;
    }

    /**
     * O comprovante de uma transferência. Só o dono do acerto e o admin veem —
     * é documento bancário de outra pessoa.
     */
    public ComprovantePagamento comprovante(Long pagamentoId, Usuario quem, boolean admin) {
        PagamentoAcerto p = PagamentoAcerto.findById(pagamentoId);
        if (p == null) {
            throw new WebApplicationException("Pagamento não encontrado.", 404);
        }
        if (!admin && !p.acerto.usuario.getId().equals(quem.id)) {
            throw new WebApplicationException("Este comprovante não é seu.", 403);
        }
        ComprovantePagamento c = ComprovantePagamento.doPagamento(pagamentoId);
        if (c == null) {
            throw new WebApplicationException("Este pagamento não tem comprovante.", 404);
        }
        return c;
    }

    /** Quanto ainda falta neste acerto. Derivado, nunca gravado. */
    public BigDecimal saldo(Acerto a) {
        return a.valorDevido.subtract(PagamentoAcerto.totalPago(a.id));
    }

    // ------------------------------------------------------------ internos --

    /**
     * O valor da transferência declarada.
     *
     * <p>Em branco vale "paguei o que faltava" — é o caso comum, e obrigar a
     * digitar de novo um número que o app acabou de mostrar só cria divergência
     * de centavo. Pagamento com sinal trocado é recusado: crédito de fatura é
     * assunto do lançamento, não do acerto.
     */
    private BigDecimal valorDeclarado(Acerto a, BigDecimal valor) {
        BigDecimal falta = saldo(a);
        if (valor == null) {
            if (falta.signum() == 0) {
                throw new WebApplicationException(
                        "Este acerto já está quitado: informe o valor se pagou algo a mais.", 409);
            }
            return falta;
        }
        BigDecimal declarado = valor.setScale(2, java.math.RoundingMode.HALF_UP);
        if (declarado.signum() == 0) {
            throw new WebApplicationException("Informe quanto você pagou.", 400);
        }
        if (falta.signum() != 0 && declarado.signum() != falta.signum()) {
            throw new WebApplicationException(
                    "O valor tem sinal contrário ao que falta pagar (R$ " + falta + ").", 400);
        }
        return declarado;
    }

    /**
     * Fecha o acerto quando não falta nada e o admin confirmou tudo.
     *
     * <p>Os dois de uma vez: saldo zerado com transferência ainda por conferir
     * seria dar por recebido o que ninguém viu cair, e confirmar tudo com saldo
     * em aberto marcaria como pago quem ainda deve.
     */
    private void quitarSeFechou(Acerto a) {
        quitarSeFechou(a, true);
    }

    /**
     * @param avisar {@code false} quando quem fechou o acerto foi a baixa
     *               manual: o e-mail dela já diz que a conta ficou quitada, e
     *               dois avisos do mesmo ato treinam a pessoa a ignorar os dois
     */
    private void quitarSeFechou(Acerto a, boolean avisar) {
        List<PagamentoAcerto> pagamentos = PagamentoAcerto.doAcerto(a.id);
        boolean todosConfirmados = pagamentos.stream().allMatch(PagamentoAcerto::confirmado);
        boolean quitado = saldo(a).signum() <= 0;
        if (!todosConfirmados || !quitado || a.status == StatusAcerto.CONFIRMADO) {
            return;
        }
        a.status = StatusAcerto.CONFIRMADO;
        a.confirmadoEm = LocalDateTime.now();
        a.confirmadoPor = pagamentos.get(pagamentos.size() - 1).confirmadoPor;
        a.persist();
        if (avisar) {
            notificacao.pagamentoConfirmado(a);
        }
    }

    private void salvarComprovante(PagamentoAcerto p, byte[] arquivo, String nome, String tipo) {
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

        // Um comprovante por transferência. Reenviar substitui o daquela
        // transferência — a pessoa mandou o print errado e corrige —, e nunca
        // toca no comprovante das outras.
        ComprovantePagamento c = ComprovantePagamento.doPagamento(p.id);
        if (c == null) {
            c = new ComprovantePagamento();
            c.pagamento = p;
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
