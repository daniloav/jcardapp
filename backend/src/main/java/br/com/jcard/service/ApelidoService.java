package br.com.jcard.service;

import br.com.jcard.model.AcaoAuditoria;
import br.com.jcard.model.ApelidoEstabelecimento;
import br.com.jcard.model.Lancamento;
import br.com.jcard.model.Usuario;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import java.time.LocalDateTime;

/**
 * Dar nome de gente ao estabelecimento.
 *
 * <p>Reconhecer a compra é o trabalho todo do utilizador, e até aqui ele fazia
 * isso sozinho, lendo o que o banco imprime. O apelido transfere esse esforço
 * para o app: definido uma vez, vale para as próximas faturas, porque a chave é
 * a descrição normalizada — a mesma que casa a compra parcelada entre meses.
 *
 * <p>Qualquer utilizador apelida, não só o admin: quem reconhece a loja é quem
 * comprou nela. Em compensação toda alteração vai para a auditoria com o nome
 * de quem fez — o apelido é visto por todo mundo.
 */
@ApplicationScoped
public class ApelidoService {

    @Inject
    AuditoriaService auditoria;

    /**
     * Define ou troca o apelido do estabelecimento de um lançamento.
     *
     * <p>Recebe o id do lançamento, e não a descrição normalizada crua, para
     * que a chave venha sempre do que o parser gravou: normalizar de novo aqui,
     * a partir de texto vindo da API, abriria espaço para um apelido que nunca
     * casaria com lançamento nenhum.
     */
    @Transactional
    public ApelidoEstabelecimento apelidar(Long lancamentoId, String apelido, Usuario quem) {
        Lancamento l = Lancamento.findById(lancamentoId);
        if (l == null) {
            throw new WebApplicationException("Lançamento não encontrado.", 404);
        }
        String texto = apelido == null ? "" : apelido.strip();
        if (texto.isEmpty()) {
            throw new WebApplicationException("Informe o apelido do estabelecimento.", 400);
        }
        if (texto.length() > 120) {
            texto = texto.substring(0, 120);
        }

        ApelidoEstabelecimento a = ApelidoEstabelecimento.porDescricao(l.descricaoNormalizada);
        if (a == null) {
            a = new ApelidoEstabelecimento();
            a.descricaoNormalizada = l.descricaoNormalizada;
        }
        a.apelido = texto;
        a.atualizadoEm = LocalDateTime.now();
        a.atualizadoPor = quem;
        a.persist();

        auditoria.registrar(quem, AcaoAuditoria.APELIDAR, "Apelido", a.id,
                l.descricaoNormalizada + " → " + texto);
        return a;
    }

    /** Volta a mostrar o que o banco imprime. */
    @Transactional
    public void remover(Long lancamentoId, Usuario quem) {
        Lancamento l = Lancamento.findById(lancamentoId);
        if (l == null) {
            throw new WebApplicationException("Lançamento não encontrado.", 404);
        }
        ApelidoEstabelecimento a = ApelidoEstabelecimento.porDescricao(l.descricaoNormalizada);
        if (a == null) {
            return;
        }
        a.delete();
        auditoria.registrar(quem, AcaoAuditoria.APELIDAR, "Apelido", a.id,
                l.descricaoNormalizada + " — apelido removido");
    }
}
