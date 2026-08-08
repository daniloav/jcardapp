package br.com.jcard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Payloads de entrada da API, agrupados por serem pequenos e sempre lidos juntos.
 * São {@code record}s: imutáveis e validados pelo Hibernate Validator.
 */
public final class Requests {

    private Requests() {
    }

    public record Login(
            @NotBlank(message = "Informe o login") String login,
            @NotBlank(message = "Informe a senha") String senha) {
    }

    public record TrocarSenha(
            @NotBlank(message = "Informe a senha atual") String senhaAtual,
            @NotBlank @Size(min = 8, message = "A nova senha precisa ter ao menos 8 caracteres")
            String senhaNova) {
    }

    public record Reivindicar(
            @Size(max = 400) String observacao) {
    }

    public record Arbitrar(
            @NotNull(message = "Escolha quem fica com o lançamento") Long vencedorId) {
    }

    public record InformarPagamento(
            @Size(max = 400) String observacao) {
    }

    public record Cartao(
            @NotBlank @Size(max = 80) String apelido,
            @NotBlank @Size(min = 4, max = 4, message = "Informe os 4 últimos dígitos")
            String final4,
            @Size(max = 120) String portadorNome,
            Long donoPadraoId,
            boolean titular,
            boolean ativo) {
    }
}
