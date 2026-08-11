package br.com.jcard.service;

import br.com.jcard.dto.Responses;
import br.com.jcard.model.AcaoAuditoria;
import br.com.jcard.model.ConfiguracaoPix;
import br.com.jcard.model.Usuario;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * A chave PIX para onde os acertos são pagos.
 *
 * <p>Duas origens, nesta ordem: o que o admin salvou pela tela (tabela
 * {@code configuracao_pix}) e, enquanto ele não salvar nada, a variável de
 * ambiente {@code JCARD_PIX_CHAVE}. O ambiente sozinho obrigava a entrar por
 * ssh na VM para trocar a chave — decisão do dono do cartão virando tarefa de
 * sysadmin. O banco sozinho obrigaria a preencher a tela antes do primeiro
 * pagamento em qualquer instalação nova.
 *
 * <p>O que <b>não</b> muda: o CPF nunca vai para o git. Ele fica no banco ou no
 * {@code .env}, e o repositório é público.
 */
@ApplicationScoped
public class PixConfig {

    @Inject
    AuditoriaService auditoria;

    @ConfigProperty(name = "jcard.pix.tipo")
    String tipoPadrao;

    /**
     * Vazia enquanto ninguém definir {@code JCARD_PIX_CHAVE}. Antes o default
     * era o próprio aviso ("defina JCARD_PIX_CHAVE no .env"), que a tela exibia
     * como se fosse chave — com botão de copiar e tudo. Um texto de configuração
     * não é uma chave: agora a falta vira uma flag, e quem decide o que dizer é
     * a tela.
     *
     * <p>{@code Optional} e não {@code String} com default vazio: para o
     * SmallRye um valor vazio é <b>ausente</b>, e injetar ausente numa
     * {@code String} não é falta de valor, é erro de configuração — o app nem
     * sobe. Justamente o caso normal aqui, que é ninguém ter configurado ainda.
     */
    @ConfigProperty(name = "jcard.pix.chave")
    Optional<String> chavePadrao;

    @ConfigProperty(name = "jcard.pix.titular")
    String titularPadrao;

    /** O que a tela de pagamento mostra. Nunca lança: no pior caso, não configurada. */
    @Transactional
    public Responses.PixResponse atual() {
        ConfiguracaoPix salva = ConfiguracaoPix.atual();
        if (salva != null) {
            return new Responses.PixResponse(salva.tipo, salva.chave, salva.titular,
                    true, Responses.OrigemPix.APP);
        }
        String doAmbiente = chavePadrao.map(String::trim).orElse("");
        if (doAmbiente.isEmpty()) {
            return new Responses.PixResponse(tipoPadrao, "", titularPadrao,
                    false, Responses.OrigemPix.NENHUMA);
        }
        return new Responses.PixResponse(tipoPadrao, doAmbiente, titularPadrao,
                true, Responses.OrigemPix.AMBIENTE);
    }

    /**
     * O admin troca a chave pela tela.
     *
     * <p>A partir da primeira gravação o banco manda, e mexer no {@code .env}
     * deixa de ter efeito. É o único comportamento honesto: editar pela tela e
     * ver a variável de ambiente vencer seria pior do que não ter tela.
     */
    @Transactional
    public Responses.PixResponse salvar(String tipo, String chave, String titular, Usuario quem) {
        String novoTipo = limpar(tipo, 20);
        String novaChave = limpar(chave, 140);
        String novoTitular = limpar(titular, 120);
        if (novaChave.isEmpty()) {
            throw new WebApplicationException("Informe a chave PIX.", 400);
        }
        if (novoTipo.isEmpty()) {
            throw new WebApplicationException("Informe o tipo da chave (CPF, e-mail, telefone...).", 400);
        }
        if (novoTitular.isEmpty()) {
            throw new WebApplicationException("Informe o nome de quem recebe.", 400);
        }

        ConfiguracaoPix c = ConfiguracaoPix.atual();
        boolean primeira = c == null;
        if (primeira) {
            c = new ConfiguracaoPix();
        }
        String anterior = primeira ? "(vinha do .env)" : c.chave;
        c.tipo = novoTipo;
        c.chave = novaChave;
        c.titular = novoTitular;
        c.atualizadoEm = LocalDateTime.now();
        c.atualizadoPor = quem;
        c.persist();

        // Vai para a auditoria com a chave inteira: mudar para onde o dinheiro
        // de todo mundo vai é a operação mais sensível do app, e "para qual
        // chave estava mandando em março?" precisa ter resposta.
        auditoria.registrar(quem, AcaoAuditoria.ATUALIZAR, "ConfiguracaoPix", c.id,
                "Chave PIX alterada de [" + anterior + "] para [" + novaChave
                        + "] (" + novoTipo + " · " + novoTitular + ")");

        return new Responses.PixResponse(c.tipo, c.chave, c.titular, true, Responses.OrigemPix.APP);
    }

    private String limpar(String texto, int limite) {
        String t = texto == null ? "" : texto.strip();
        return t.length() <= limite ? t : t.substring(0, limite);
    }
}
