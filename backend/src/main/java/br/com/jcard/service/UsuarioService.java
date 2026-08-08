package br.com.jcard.service;

import br.com.jcard.dto.UsuarioRequest;
import br.com.jcard.model.AcaoAuditoria;
import br.com.jcard.model.Acerto;
import br.com.jcard.model.Lancamento;
import br.com.jcard.model.Usuario;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import java.text.Normalizer;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Cadastro de utilizadores — só o admin mexe aqui.
 *
 * <p>O login é derivado do nome ({@code nome.sobrenome}) e a senha inicial é a
 * padrão, com troca obrigatória no primeiro acesso.
 */
@ApplicationScoped
public class UsuarioService {

    @Inject
    AuthService auth;

    @Inject
    NotificacaoService notificacao;

    @Inject
    AuditoriaService auditoria;

    @ConfigProperty(name = "jcard.usuario.senha-padrao")
    String senhaPadrao;

    public List<Usuario> listar() {
        return Usuario.listAll(io.quarkus.panache.common.Sort.by("nome"));
    }

    @Transactional
    public Usuario criar(UsuarioRequest req, Usuario admin) {
        if (Usuario.porEmail(req.email()) != null) {
            throw new WebApplicationException("Já existe um utilizador com esse e-mail.", 409);
        }
        Usuario u = new Usuario();
        u.nome = req.nome().strip();
        u.email = req.email().strip().toLowerCase();
        u.login = gerarLogin(u.nome);
        u.senhaHash = auth.hash(senhaPadrao);
        u.admin = req.admin();
        u.utilizador = req.utilizador();
        u.recebeNotificacoes = req.recebeNotificacoes();
        u.precisaTrocarSenha = true;
        u.persist();

        auditoria.registrar(admin, AcaoAuditoria.CRIAR, "Usuario", u.id, u.nome);
        notificacao.boasVindas(u, senhaPadrao);
        return u;
    }

    @Transactional
    public Usuario atualizar(Long id, UsuarioRequest req, Usuario admin) {
        Usuario u = Usuario.findById(id);
        if (u == null) {
            throw new WebApplicationException("Utilizador não encontrado.", 404);
        }
        Usuario mesmoEmail = Usuario.porEmail(req.email());
        if (mesmoEmail != null && !mesmoEmail.id.equals(id)) {
            throw new WebApplicationException("Esse e-mail já é de outro utilizador.", 409);
        }
        u.nome = req.nome().strip();
        u.email = req.email().strip().toLowerCase();
        u.admin = req.admin();
        u.utilizador = req.utilizador();
        u.recebeNotificacoes = req.recebeNotificacoes();
        u.persist();

        garantirAdminRemanescente();
        auditoria.registrar(admin, AcaoAuditoria.ATUALIZAR, "Usuario", u.id, u.nome);
        return u;
    }

    /**
     * Desativa em vez de excluir quando há histórico: apagar alguém que já assumiu
     * lançamentos deixaria faturas passadas sem dono e quebraria a conciliação.
     */
    @Transactional
    public void remover(Long id, Usuario admin) {
        Usuario u = Usuario.findById(id);
        if (u == null) {
            throw new WebApplicationException("Utilizador não encontrado.", 404);
        }
        if (u.id.equals(admin.id)) {
            throw new WebApplicationException("Você não pode remover a própria conta.", 409);
        }

        long lancamentos = Lancamento.count("responsavel.id", id);
        long acertos = Acerto.count("usuario.id", id);
        if (lancamentos > 0 || acertos > 0) {
            u.ativo = false;
            u.persist();
            garantirAdminRemanescente();
            auditoria.registrar(admin, AcaoAuditoria.ATUALIZAR, "Usuario", id,
                    "desativado (tem " + lancamentos + " lançamento(s) e " + acertos + " acerto(s))");
            return;
        }
        u.delete();
        garantirAdminRemanescente();
        auditoria.registrar(admin, AcaoAuditoria.EXCLUIR, "Usuario", id, u.nome);
    }

    /** Volta a senha para a padrão e força a troca no próximo acesso. */
    @Transactional
    public void resetarSenha(Long id, Usuario admin) {
        Usuario u = Usuario.findById(id);
        if (u == null) {
            throw new WebApplicationException("Utilizador não encontrado.", 404);
        }
        u.senhaHash = auth.hash(senhaPadrao);
        u.precisaTrocarSenha = true;
        u.persist();
        auditoria.registrar(admin, AcaoAuditoria.ATUALIZAR, "Usuario", id, "senha resetada");
        notificacao.boasVindas(u, senhaPadrao);
    }

    // ------------------------------------------------------------ internos --

    /** Sem admin ativo ninguém importa fatura nem arbitra — o app fica travado. */
    private void garantirAdminRemanescente() {
        if (Usuario.count("admin = true and ativo = true") == 0) {
            throw new WebApplicationException(
                    "Precisa sobrar pelo menos um administrador ativo.", 409);
        }
    }

    String gerarLogin(String nome) {
        String base = Normalizer.normalize(nome, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase()
                .replaceAll("[^a-z\\s]", "")
                .strip();
        String[] partes = base.split("\\s+");
        String candidato = partes.length > 1
                ? partes[0] + "." + partes[partes.length - 1]
                : partes[0];
        if (candidato.length() > 55) {
            candidato = candidato.substring(0, 55);
        }

        String tentativa = candidato;
        int n = 1;
        while (Usuario.porLogin(tentativa) != null) {
            tentativa = candidato + (++n);
        }
        return tentativa;
    }
}
