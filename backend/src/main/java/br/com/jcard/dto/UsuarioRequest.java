package br.com.jcard.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Cadastro/edição de utilizador pelo admin. O login é derivado do nome e a senha
 * inicial é a padrão do sistema — nenhum dos dois vem do cliente.
 */
public record UsuarioRequest(
        @NotBlank(message = "Informe o nome") @Size(max = 120) String nome,
        @NotBlank(message = "Informe o e-mail") @Email(message = "E-mail inválido")
        @Size(max = 160) String email,
        boolean admin,
        boolean utilizador,
        boolean recebeNotificacoes) {
}
