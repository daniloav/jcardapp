package br.com.jcard.resource;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Traduz exceção em JSON {@code {message, status}} — o front tem um formato único
 * para mostrar no toast.
 *
 * <p>Erro de negócio (4xx) devolve a mensagem escrita para o usuário; erro
 * inesperado (5xx) devolve texto genérico e o detalhe vai só para o log, para não
 * vazar interno da aplicação.
 */
@Provider
public class ErrorMapper implements ExceptionMapper<Exception> {

    private static final Logger LOG = Logger.getLogger(ErrorMapper.class);

    @Override
    public Response toResponse(Exception e) {
        if (e instanceof WebApplicationException wae) {
            int status = wae.getResponse().getStatus();
            String mensagem = wae.getMessage();
            if (status >= 500) {
                LOG.error("Erro na API", e);
                mensagem = "Erro interno. Tente de novo em instantes.";
            }
            return Response.status(status)
                    .entity(Map.of("message", mensagem == null ? "Erro" : mensagem,
                            "status", status))
                    .build();
        }
        LOG.error("Erro não tratado", e);
        return Response.status(500)
                .entity(Map.of("message", "Erro interno. Tente de novo em instantes.",
                        "status", 500))
                .build();
    }
}
