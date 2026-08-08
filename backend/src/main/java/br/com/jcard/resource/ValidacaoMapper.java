package br.com.jcard.resource;

import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Erros do Bean Validation no mesmo formato do {@link ErrorMapper}, juntando as
 * mensagens numa frase só — as telas do app são curtas e mostram um toast.
 */
@Provider
public class ValidacaoMapper implements ExceptionMapper<ConstraintViolationException> {

    @Override
    public Response toResponse(ConstraintViolationException e) {
        String mensagem = e.getConstraintViolations().stream()
                .map(v -> v.getMessage())
                .distinct()
                .collect(Collectors.joining(" "));
        return Response.status(400)
                .entity(Map.of("message", mensagem.isBlank() ? "Dados inválidos." : mensagem,
                        "status", 400))
                .build();
    }
}
