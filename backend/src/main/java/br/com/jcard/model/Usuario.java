package br.com.jcard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Pessoa que usa o app: o titular do cartão (admin) e cada utilizador que faz
 * compras com ele.
 *
 * <p>Papéis são <b>flags</b>, não um campo "role": o titular normalmente é
 * {@code admin} <i>e</i> {@code utilizador}, porque também gasta no próprio cartão.
 */
@Entity
@Table(name = "usuario")
public class Usuario extends EntidadeBase {

    @Column(nullable = false, length = 120)
    public String nome;

    @Column(nullable = false, unique = true, length = 60)
    public String login;

    @Column(nullable = false, unique = true, length = 160)
    public String email;

    @Column(name = "senha_hash", nullable = false, length = 120)
    public String senhaHash;

    @Column(nullable = false)
    public boolean admin = false;

    @Column(nullable = false)
    public boolean utilizador = true;

    @Column(nullable = false)
    public boolean ativo = true;

    /** Senha padrão do cadastro: força a troca no primeiro acesso. */
    @Column(name = "precisa_trocar_senha", nullable = false)
    public boolean precisaTrocarSenha = true;

    /** Opt-in de e-mail (LGPD): só notificamos quem consentiu. */
    @Column(name = "recebe_notificacoes", nullable = false)
    public boolean recebeNotificacoes = true;

    @Column(name = "criado_em", nullable = false)
    public LocalDateTime criadoEm = LocalDateTime.now();

    public static Usuario porLogin(String login) {
        return find("lower(login)", login == null ? null : login.toLowerCase()).firstResult();
    }

    public static Usuario porEmail(String email) {
        return find("lower(email)", email == null ? null : email.toLowerCase()).firstResult();
    }

    /** Utilizadores ativos — destinatários do aviso de fatura nova. */
    public static java.util.List<Usuario> utilizadoresAtivos() {
        return list("utilizador = true and ativo = true order by nome");
    }
}
