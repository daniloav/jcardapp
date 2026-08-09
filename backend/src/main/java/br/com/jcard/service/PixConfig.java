package br.com.jcard.service;

import br.com.jcard.dto.Responses;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * A chave PIX para onde os acertos são pagos.
 *
 * <p>Vem da configuração e não de uma constante no código: é dado pessoal do
 * titular (CPF e nome completo), e quem clonar o repositório não deveria levá-lo
 * junto. Trocar de titular é mudar o {@code .env} e reiniciar.
 */
@ApplicationScoped
public class PixConfig {

    @ConfigProperty(name = "jcard.pix.tipo")
    String tipo;

    @ConfigProperty(name = "jcard.pix.chave")
    String chave;

    @ConfigProperty(name = "jcard.pix.titular")
    String titular;

    public Responses.PixResponse atual() {
        return new Responses.PixResponse(tipo, chave, titular);
    }
}
