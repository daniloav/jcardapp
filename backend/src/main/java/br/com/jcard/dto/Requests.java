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
     * "Isso tudo é da Maria": o resultado de uma busca na conciliação indo para
     * uma pessoa só.
     *
     * <p>Os ids vêm da tela porque o filtro é dela — mandar o termo da busca
     * para o backend refazer a consulta faria o lote pegar linha que o admin não
     * viu na lista. O teto de 1000 é a fatura inteira com folga (a maior real
     * tem 514) e evita que um payload absurdo trave a transação.
     */
    public record ArbitrarLote(
            @NotNull(message = "Escolha quem fica com os lançamentos") Long vencedorId,
            @NotNull @Size(min = 1, max = 1000, message = "Selecione de 1 a 1000 lançamentos")
            java.util.List<Long> lancamentoIds) {
    }

    /**
     * Por que a fatura está voltando para avaliação.
     *
     * <p>Opcional para não travar a correção urgente, mas vai no e-mail de quem
     * for afetado e na auditoria: é a operação que mais mexe em dinheiro já
     * combinado, e "por que o meu valor mudou?" tem de ter resposta.
     */
    public record ReabrirAvaliacao(
            @Size(max = 400) String motivo) {
    }

    public record Apelidar(
            @NotBlank(message = "Informe o apelido do estabelecimento")
            @Size(max = 120) String apelido) {
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

    /**
     * A chave PIX que o admin define pela tela.
     *
     * <p>O tipo é texto livre e não enum: quem lê é gente, na hora de abrir o
     * app do banco, e uma lista fechada envelheceria com o próximo tipo de
     * chave que o Banco Central inventar.
     */
    public record ChavePix(
            @NotBlank(message = "Informe o tipo da chave (CPF, e-mail, telefone...)")
            @Size(max = 20) String tipo,
            @NotBlank(message = "Informe a chave PIX") @Size(max = 140) String chave,
            @NotBlank(message = "Informe o nome de quem recebe") @Size(max = 120) String titular) {
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
