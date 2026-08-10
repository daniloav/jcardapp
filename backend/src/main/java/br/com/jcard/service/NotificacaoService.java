package br.com.jcard.service;

import br.com.jcard.model.Acerto;
import br.com.jcard.model.Fatura;
import br.com.jcard.model.Lancamento;
import br.com.jcard.model.StatusAcerto;
import br.com.jcard.model.Usuario;
import io.quarkus.mailer.Mail;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * E-mails do app. Todos respeitam o toggle {@code jcard.notificacoes.enabled} e
 * o opt-in do usuário, e nenhum deles lança: falha de e-mail nunca desfaz a
 * operação que o disparou.
 */
@ApplicationScoped
public class NotificacaoService {

    private static final Logger LOG = Logger.getLogger(NotificacaoService.class);
    private static final DateTimeFormatter MES = DateTimeFormatter.ofPattern("MM/yyyy");

    @Inject
    EmailDispatcher dispatcher;

    @ConfigProperty(name = "jcard.notificacoes.enabled")
    boolean habilitado;

    @ConfigProperty(name = "jcard.app.url")
    String appUrl;

    /**
     * Aviso de fatura nova para <b>todos</b> os utilizadores ativos — é o gatilho
     * que põe o ciclo de avaliação de responsabilidade em movimento.
     */
    public void faturaImportada(Fatura fatura, int qtdPool) {
        if (!habilitado) {
            LOG.debug("Notificações desligadas: não avisei da fatura importada.");
            return;
        }
        String mes = fatura.competencia.format(MES);
        for (Usuario u : Usuario.utilizadoresAtivos()) {
            if (!u.recebeNotificacoes || u.email == null || u.email.isBlank()) {
                continue;
            }
            String corpo = """
                    <p>Olá, %s!</p>
                    <p>A fatura de <strong>%s</strong> foi lançada no JcardApp e já está
                    disponível para avaliação de responsabilidade.</p>
                    <p>São <strong>%d lançamento(s)</strong> aguardando dono. Entre no app,
                    confira a lista e marque o que foi você.</p>
                    <p><a href="%s">Abrir o JcardApp</a></p>
                    <p style="color:#666;font-size:12px">Se não reconhecer nada na lista,
                    não precisa fazer nada.</p>
                    """.formatted(primeiroNome(u.nome), mes, qtdPool, appUrl);
            dispatcher.enfileirar(Mail.withHtml(u.email,
                    "JcardApp · fatura de " + mes + " para avaliação", corpo));
        }
    }

    /** Lembrete de lançamentos ainda sem dono, enquanto a fatura está aberta. */
    public void lembretePendencia(Usuario u, Fatura fatura, int qtdPool) {
        if (!habilitado || !u.recebeNotificacoes || u.email == null || u.email.isBlank()) {
            return;
        }
        String corpo = """
                <p>Olá, %s!</p>
                <p>Ainda há <strong>%d lançamento(s)</strong> sem dono na fatura de
                <strong>%s</strong>. Se algum for seu, marque no app para o fechamento sair.</p>
                <p><a href="%s">Abrir o JcardApp</a></p>
                """.formatted(primeiroNome(u.nome), qtdPool, fatura.competencia.format(MES), appUrl);
        dispatcher.enfileirar(Mail.withHtml(u.email,
                "JcardApp · lançamentos aguardando avaliação", corpo));
    }

    /** Avisa o admin de um conflito para arbitrar. */
    public void conflito(Lancamento l, List<Usuario> disputantes) {
        if (!habilitado) {
            return;
        }
        String nomes = disputantes.stream().map(u -> u.nome).reduce((a, b) -> a + ", " + b).orElse("");
        for (Usuario admin : Usuario.<Usuario>list("admin = true and ativo = true")) {
            if (admin.email == null || admin.email.isBlank()) {
                continue;
            }
            String corpo = """
                    <p>Duas ou mais pessoas reivindicaram o mesmo lançamento:</p>
                    <p><strong>%s</strong> — R$ %s em %s</p>
                    <p>Disputando: %s</p>
                    <p><a href="%s">Arbitrar no JcardApp</a></p>
                    """.formatted(l.descricao, l.valor, l.dataCompra, nomes, appUrl);
            dispatcher.enfileirar(Mail.withHtml(admin.email,
                    "JcardApp · conflito para arbitrar", corpo));
        }
    }

    /**
     * Avisa quem dividiu a conta de que alguém recusou a parte.
     *
     * <p>Importa porque o lançamento volta inteiro para o organizador: ele
     * precisa saber que o valor dele subiu, e por quê, antes de aceitar o total.
     */
    public void parteRecusada(Lancamento l, Usuario quemRecusou) {
        Usuario dono = l.responsavel;
        if (!habilitado || dono == null || !dono.recebeNotificacoes
                || dono.email == null || dono.email.isBlank()) {
            return;
        }
        String corpo = """
                <p>Olá, %s!</p>
                <p><strong>%s</strong> não reconheceu a parte na conta
                <strong>%s</strong> (R$ %s, em %s).</p>
                <p>A divisão foi desfeita e o lançamento voltou inteiro para você.
                Se ainda for para rachar, refaça a divisão no app com quem participou.</p>
                <p><a href="%s">Abrir o JcardApp</a></p>
                """.formatted(primeiroNome(dono.nome), quemRecusou.nome, l.descricao,
                valor(l.valor), l.dataCompra, appUrl);
        dispatcher.enfileirar(Mail.withHtml(dono.email,
                "JcardApp · uma parte da conta dividida foi recusada", corpo));
    }

    /**
     * A avaliação foi reaberta: os valores voltaram a mudar.
     *
     * <p>É a operação que mais mexe em dinheiro já combinado — a pessoa pode ter
     * aceitado (ou até pago) um total que agora vai ser recalculado. Avisar não
     * é cortesia: sem o e-mail, o valor mudaria em silêncio.
     */
    public void avaliacaoReaberta(Fatura fatura, List<Acerto> afetados,
                                  String motivo, int devolvidosAoPool) {
        if (!habilitado) {
            return;
        }
        String mes = fatura.competencia.format(MES);
        String porque = motivo == null || motivo.isBlank()
                ? "" : "<p>Motivo informado: <em>%s</em></p>".formatted(motivo);

        for (Acerto a : afetados) {
            Usuario u = a.usuario;
            if (!u.recebeNotificacoes || u.email == null || u.email.isBlank()) {
                continue;
            }
            String jaPagou = a.status == StatusAcerto.INFORMADO
                    ? """
                      <p><strong>Você já declarou o pagamento desta fatura.</strong> O comprovante
                      continua registrado; se o seu total mudar, o administrador acerta a
                      diferença com você.</p>
                      """
                    : "";
            String corpo = """
                    <p>Olá, %s!</p>
                    <p>A fatura de <strong>%s</strong> voltou para <strong>avaliação</strong>:
                    o administrador reabriu a indicação para corrigir a responsabilidade de
                    algum lançamento.</p>
                    %s
                    <p>%d lançamento(s) voltaram para a lista de quem não tem dono. O seu
                    total pode mudar, e o aceite anterior não vale mais — você vai conferir
                    o valor de novo quando a fatura for conciliada.</p>
                    %s
                    <p><a href="%s">Conferir no JcardApp</a></p>
                    """.formatted(primeiroNome(u.nome), mes, jaPagou, devolvidosAoPool,
                    porque, appUrl);
            dispatcher.enfileirar(Mail.withHtml(u.email,
                    "JcardApp · a fatura de " + mes + " voltou para avaliação", corpo));
        }
    }

    /**
     * O admin colocou um lançamento no nome de alguém.
     *
     * <p>Atribuição mandatória sem aviso é cobrança silenciosa — exatamente o
     * que o app existe para evitar. O e-mail diz também que dá para contestar
     * enquanto a fatura estiver em avaliação.
     */
    public void atribuidoPeloAdmin(Lancamento l, Usuario dono, boolean haviaDisputa) {
        if (!habilitado || dono == null || !dono.recebeNotificacoes
                || dono.email == null || dono.email.isBlank()) {
            return;
        }
        String abertura = haviaDisputa
                ? "Mais de uma pessoa reivindicou este lançamento, e o administrador decidiu "
                  + "que ele é seu:"
                : "O administrador indicou que este lançamento é seu:";
        String corpo = """
                <p>Olá, %s!</p>
                <p>%s</p>
                <p><strong>%s</strong> — R$ %s em %s</p>
                <p>Se não foi você, abra o app e use <strong>"não foi minha"</strong> enquanto
                a fatura de %s estiver em avaliação: o lançamento volta para a lista de quem
                não tem dono e o administrador é avisado.</p>
                <p><a href="%s">Abrir o JcardApp</a></p>
                """.formatted(primeiroNome(dono.nome), abertura, l.descricao, valor(l.valor),
                l.dataCompra, l.fatura.competencia.format(MES), appUrl);
        dispatcher.enfileirar(Mail.withHtml(dono.email,
                "JcardApp · um lançamento foi atribuído a você", corpo));
    }

    /** Fatura conciliada: cada um recebe quanto ficou devendo. */
    public void acertoDisponivel(Acerto a) {
        if (!habilitado || a.usuario.email == null || a.usuario.email.isBlank()) {
            return;
        }
        String corpo = """
                <p>Olá, %s!</p>
                <p>A fatura de <strong>%s</strong> foi fechada. O seu total ficou em
                <strong>R$ %s</strong>.</p>
                <p>Você pode conferir o detalhe lançamento a lançamento e informar o
                pagamento pelo app.</p>
                <p><a href="%s">Abrir o JcardApp</a></p>
                """.formatted(primeiroNome(a.usuario.nome),
                a.fatura.competencia.format(MES), valor(a.valorDevido), appUrl);
        dispatcher.enfileirar(Mail.withHtml(a.usuario.email,
                "JcardApp · seu acerto de " + a.fatura.competencia.format(MES), corpo));
    }

    /** Avisa o admin de que alguém pagou e mandou o comprovante para conferir. */
    public void pagamentoInformado(Acerto a) {
        if (!habilitado) {
            return;
        }
        String corpo = """
                <p><strong>%s</strong> declarou o pagamento de <strong>R$ %s</strong>
                da fatura de <strong>%s</strong>.</p>
                <p>O comprovante está anexado ao acerto, na tela de conciliação.</p>
                <p><a href="%s">Conferir no JcardApp</a></p>
                """.formatted(a.usuario.nome, valor(a.valorDevido),
                a.fatura.competencia.format(MES), appUrl);
        for (Usuario admin : Usuario.<Usuario>list("admin = true and ativo = true")) {
            if (admin.email == null || admin.email.isBlank()) {
                continue;
            }
            dispatcher.enfileirar(Mail.withHtml(admin.email,
                    "JcardApp · pagamento a conferir", corpo));
        }
    }

    /** Confirmação de recebimento pelo admin — fecha o ciclo para o utilizador. */
    public void pagamentoConfirmado(Acerto a) {
        if (!habilitado || a.usuario.email == null || a.usuario.email.isBlank()) {
            return;
        }
        String corpo = """
                <p>Olá, %s!</p>
                <p>O pagamento de <strong>R$ %s</strong> referente à fatura de
                <strong>%s</strong> foi confirmado. Tudo certo por aqui!</p>
                """.formatted(primeiroNome(a.usuario.nome), valor(a.valorDevido),
                a.fatura.competencia.format(MES));
        dispatcher.enfileirar(Mail.withHtml(a.usuario.email,
                "JcardApp · pagamento confirmado", corpo));
    }

    /** Credenciais do primeiro acesso, no cadastro feito pelo admin. */
    public void boasVindas(Usuario u, String senhaProvisoria) {
        if (!habilitado || u.email == null || u.email.isBlank()) {
            return;
        }
        String corpo = """
                <p>Olá, %s!</p>
                <p>Você foi cadastrado no <strong>JcardApp</strong>, onde as compras feitas
                no cartão são separadas por pessoa a cada fatura.</p>
                <p>Login: <strong>%s</strong><br>Senha provisória: <strong>%s</strong></p>
                <p>Você vai trocar a senha no primeiro acesso.</p>
                <p><a href="%s">Abrir o JcardApp</a></p>
                """.formatted(primeiroNome(u.nome), u.login, senhaProvisoria, appUrl);
        dispatcher.enfileirar(Mail.withHtml(u.email, "JcardApp · seu acesso", corpo));
    }

    private static String primeiroNome(String nome) {
        if (nome == null || nome.isBlank()) {
            return "tudo bem";
        }
        int i = nome.indexOf(' ');
        return i > 0 ? nome.substring(0, i) : nome;
    }

    private static String valor(BigDecimal v) {
        return v == null ? "0,00" : v.toPlainString().replace('.', ',');
    }
}
