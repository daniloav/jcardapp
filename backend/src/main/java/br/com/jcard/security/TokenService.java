package br.com.jcard.security;

import br.com.jcard.model.Usuario;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Emissão do JWT (RS256).
 *
 * <p>Papéis viram <i>groups</i> do token. Como são flags, o titular sai com
 * {@code ADMIN} e {@code UTILIZADOR} ao mesmo tempo — ele administra e também
 * assume as próprias compras.
 */
@ApplicationScoped
public class TokenService {

    public static final String ADMIN = "ADMIN";
    public static final String UTILIZADOR = "UTILIZADOR";

    @ConfigProperty(name = "jcard.jwt.duracao-horas")
    long duracaoHoras;

    public String gerar(Usuario u) {
        Set<String> grupos = new HashSet<>();
        if (u.admin) {
            grupos.add(ADMIN);
        }
        if (u.utilizador) {
            grupos.add(UTILIZADOR);
        }
        return Jwt.upn(u.login)
                .subject(String.valueOf(u.id))
                .groups(grupos)
                .claim("nome", u.nome)
                .claim("uid", u.id)
                // O front usa esta claim para empurrar direto à tela de troca de senha.
                .claim("trocarSenha", u.precisaTrocarSenha)
                .expiresIn(Duration.ofHours(duracaoHoras))
                .sign();
    }
}
