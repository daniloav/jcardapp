package br.com.jcard.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

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

    /**
     * As partes de uma conta rachada. A soma tem de reproduzir o valor do
     * lançamento — validado no {@code DivisaoService}, que é onde o valor real
     * do lançamento está disponível para comparar.
     */
    public record Dividir(
            @NotNull(message = "Informe as partes da divisão")
            @Size(min = 2, message = "Uma divisão precisa de pelo menos duas pessoas")
            java.util.List<@Valid Parte> partes) {

        public record Parte(
                @NotNull(message = "Informe a pessoa") Long usuarioId,
                @NotNull(message = "Informe o valor da parte") BigDecimal valor) {
        }
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
