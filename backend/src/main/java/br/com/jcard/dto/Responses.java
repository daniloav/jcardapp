package br.com.jcard.dto;

import br.com.jcard.model.Acerto;
import br.com.jcard.model.Cartao;
import br.com.jcard.model.Fatura;
import br.com.jcard.model.Lancamento;
import br.com.jcard.model.OrigemAtribuicao;
import br.com.jcard.model.StatusAcerto;
import br.com.jcard.model.StatusFatura;
import br.com.jcard.model.TipoLancamento;
import br.com.jcard.model.Usuario;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Payloads de saída. Entidades nunca vão direto para a API — além do risco de
 * vazar campo (senha, texto do PDF), o formato da resposta é decidido aqui.
 */
public final class Responses {

    private Responses() {
    }

    // -------------------------------------------------------------- sessão --

    public record Login(String token, Usuario usuario, boolean precisaTrocarSenha) {
        public static Login de(String token, br.com.jcard.model.Usuario u) {
            return new Login(token, Usuario.de(u), u.precisaTrocarSenha);
        }
    }

    public record Usuario(Long id, String nome, String login, String email,
                          boolean admin, boolean utilizador, boolean ativo,
                          boolean recebeNotificacoes, boolean precisaTrocarSenha) {
        public static Usuario de(br.com.jcard.model.Usuario u) {
            return new Usuario(u.id, u.nome, u.login, u.email, u.admin, u.utilizador,
                    u.ativo, u.recebeNotificacoes, u.precisaTrocarSenha);
        }
    }

    // -------------------------------------------------------------- cartão --

    public record CartaoResponse(Long id, String apelido, String final4, String portadorNome,
                                 Long donoPadraoId, String donoPadraoNome,
                                 boolean titular, boolean ativo) {
        public static CartaoResponse de(Cartao c) {
            return new CartaoResponse(c.id, c.apelido, c.final4, c.portadorNome,
                    c.donoPadrao == null ? null : c.donoPadrao.getId(),
                    c.donoPadrao == null ? null : c.donoPadrao.nome,
                    c.titular, c.ativo);
        }
    }

    // -------------------------------------------------------------- fatura --

    /**
     * @param divergencia diferença entre o total impresso e a soma lida; só é
     *                    diferente de zero quando o parser errou
     */
    public record FaturaResponse(Long id, LocalDate competencia, LocalDate vencimento,
                                 BigDecimal valorTotal, BigDecimal valorLancado,
                                 BigDecimal divergencia, StatusFatura status,
                                 String emissor, LocalDateTime importadaEm,
                                 int totalLancamentos, int noPool, int emConflito) {
        public static FaturaResponse de(Fatura f, int totalLancamentos, int noPool, int emConflito) {
            return new FaturaResponse(f.id, f.competencia, f.vencimento, f.valorTotal,
                    f.valorLancado, f.divergencia(), f.status, f.emissor, f.importadaEm,
                    totalLancamentos, noPool, emConflito);
        }
    }

    /**
     * Um lançamento como o utilizador vê.
     *
     * @param disputantes nomes de quem reivindicou; só é preenchido para o admin
     *                    na fila de conflitos — o utilizador comum não vê quem
     *                    mais está disputando
     */
    public record LancamentoResponse(Long id, LocalDate dataCompra, String descricao,
                                     BigDecimal valor, String portadorNome, String final4,
                                     Integer parcelaAtual, Integer parcelaTotal,
                                     TipoLancamento tipo, Long responsavelId,
                                     String responsavelNome, OrigemAtribuicao origemAtribuicao,
                                     boolean meu, List<String> disputantes) {

        public static LancamentoResponse de(Lancamento l, Long usuarioId) {
            return new LancamentoResponse(l.id, l.dataCompra, l.descricao, l.valor,
                    l.portadorNome, l.final4, l.parcelaAtual, l.parcelaTotal, l.tipo,
                    l.responsavel == null ? null : l.responsavel.getId(),
                    l.responsavel == null ? null : l.responsavel.nome,
                    l.origemAtribuicao,
                    l.responsavel != null && l.responsavel.getId().equals(usuarioId),
                    null);
        }

        public LancamentoResponse comDisputantes(List<String> nomes) {
            return new LancamentoResponse(id, dataCompra, descricao, valor, portadorNome,
                    final4, parcelaAtual, parcelaTotal, tipo, responsavelId, responsavelNome,
                    origemAtribuicao, meu, nomes);
        }

        /**
         * Versão sem o dono, para o pool: o utilizador não precisa saber de quem
         * era antes, e esconder isso evita influenciar a reivindicação.
         */
        public LancamentoResponse anonimo() {
            return new LancamentoResponse(id, dataCompra, descricao, valor, portadorNome,
                    final4, parcelaAtual, parcelaTotal, tipo, null, null, null, false, null);
        }
    }

    // -------------------------------------------------------------- acerto --

    public record AcertoResponse(Long id, Long faturaId, LocalDate competencia,
                                 Long usuarioId, String usuarioNome, BigDecimal valorDevido,
                                 StatusAcerto status, LocalDateTime informadoEm,
                                 LocalDateTime confirmadoEm, String observacao) {
        public static AcertoResponse de(Acerto a) {
            return new AcertoResponse(a.id, a.fatura.getId(), a.fatura.competencia,
                    a.usuario.getId(), a.usuario.nome, a.valorDevido, a.status,
                    a.informadoEm, a.confirmadoEm, a.observacao);
        }
    }

    /**
     * A tela principal do utilizador numa fatura.
     *
     * @param pool  lançamentos sem dono — o que ele pode assumir
     * @param meus  o que já é dele
     * @param total quanto ele deve hoje nessa fatura
     */
    public record MinhasContas(FaturaResponse fatura,
                               List<LancamentoResponse> pool,
                               List<LancamentoResponse> meus,
                               BigDecimal total,
                               AcertoResponse acerto) {
    }
}
