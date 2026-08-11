package br.com.jcard.dto;

import br.com.jcard.model.Acerto;
import br.com.jcard.model.Cartao;
import br.com.jcard.model.ComprovantePagamento;
import br.com.jcard.model.DivisaoLancamento;
import br.com.jcard.model.Fatura;
import br.com.jcard.model.Lancamento;
import br.com.jcard.model.OrigemAtribuicao;
import br.com.jcard.model.PagamentoAcerto;
import br.com.jcard.model.ResumoFatura;
import br.com.jcard.model.StatusAcerto;
import br.com.jcard.model.StatusFatura;
import br.com.jcard.model.TipoLancamento;
import br.com.jcard.model.Usuario;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** O mínimo para escolher alguém numa lista, sem expor dado de cadastro. */
    public record Pessoa(Long id, String nome) {
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

        /** A partir da projeção da listagem, que não carrega o texto da fatura. */
        public static FaturaResponse de(ResumoFatura f, int totalLancamentos, int noPool,
                                        int emConflito) {
            return new FaturaResponse(f.id(), f.competencia(), f.vencimento(), f.valorTotal(),
                    f.valorLancado(), f.divergencia(), f.status(), f.emissor(), f.importadaEm(),
                    totalLancamentos, noPool, emConflito);
        }
    }

    /** A parte de uma pessoa numa conta dividida. */
    public record ParteResponse(Long usuarioId, String usuarioNome, BigDecimal valor) {
        public static ParteResponse de(DivisaoLancamento d) {
            return new ParteResponse(d.usuario.getId(), d.usuario.nome, d.valor);
        }
    }

    /**
     * Um lançamento como o utilizador vê.
     *
     * @param descricaoNormalizada a chave do estabelecimento (sem acento, caixa
     *                    alta, sem o sufixo de parcela); é por ela que o apelido
     *                    é gravado e que a mesma loja se reconhece entre faturas
     * @param apelido     o nome que a família deu à loja, quando alguém já deu;
     *                    a tela mostra ele no lugar do que o banco imprime
     * @param minhaParte  quanto <b>deste</b> lançamento é de quem está olhando:
     *                    o valor cheio quando ele é o responsável, a fatia dele
     *                    quando a conta é dividida ou quando é um encargo rateado
     * @param divisao     as partes, quando a conta é rachada; vazio caso contrário
     * @param disputantes nomes de quem reivindicou; só é preenchido para o admin
     *                    na fila de conflitos — o utilizador comum não vê quem
     *                    mais está disputando
     * @param jaFoiSeu    quem está olhando já assumiu compra nesta mesma loja em
     *                    outra fatura; no pool, transforma leitura em conferência
     */
    public record LancamentoResponse(Long id, LocalDate dataCompra, String descricao,
                                     String descricaoNormalizada, String apelido,
                                     BigDecimal valor, String portadorNome, String final4,
                                     Integer parcelaAtual, Integer parcelaTotal,
                                     TipoLancamento tipo, Long responsavelId,
                                     String responsavelNome, OrigemAtribuicao origemAtribuicao,
                                     boolean meu, BigDecimal minhaParte,
                                     List<ParteResponse> divisao, List<String> disputantes,
                                     boolean jaFoiSeu) {

        public static LancamentoResponse de(Lancamento l, Long usuarioId) {
            return new LancamentoResponse(l.id, l.dataCompra, l.descricao,
                    l.descricaoNormalizada, null, l.valor,
                    l.portadorNome, l.final4, l.parcelaAtual, l.parcelaTotal, l.tipo,
                    l.responsavel == null ? null : l.responsavel.getId(),
                    l.responsavel == null ? null : l.responsavel.nome,
                    l.origemAtribuicao,
                    l.responsavel != null && l.responsavel.getId().equals(usuarioId),
                    null, List.of(), null, false);
        }

        /** Anexa as partes e diz qual delas é de quem está olhando. */
        public LancamentoResponse comDivisao(List<DivisaoLancamento> partes, Long usuarioId) {
            List<ParteResponse> mapeadas = partes.stream().map(ParteResponse::de).toList();
            BigDecimal minha = mapeadas.stream()
                    .filter(p -> p.usuarioId().equals(usuarioId))
                    .map(ParteResponse::valor)
                    .findFirst()
                    .orElse(mapeadas.isEmpty() && Boolean.TRUE.equals(meu) ? valor : null);
            return new LancamentoResponse(id, dataCompra, descricao, descricaoNormalizada,
                    apelido, valor, portadorNome,
                    final4, parcelaAtual, parcelaTotal, tipo, responsavelId, responsavelNome,
                    origemAtribuicao, meu || minha != null, minha, mapeadas, disputantes,
                    jaFoiSeu);
        }

        /** Usado no bloco de encargos, onde a fatia vem do rateio e não de uma divisão. */
        public LancamentoResponse comMinhaParte(BigDecimal parte) {
            return new LancamentoResponse(id, dataCompra, descricao, descricaoNormalizada,
                    apelido, valor, portadorNome,
                    final4, parcelaAtual, parcelaTotal, tipo, responsavelId, responsavelNome,
                    origemAtribuicao, meu, parte, divisao, disputantes, jaFoiSeu);
        }

        public LancamentoResponse comDisputantes(List<String> nomes) {
            return new LancamentoResponse(id, dataCompra, descricao, descricaoNormalizada,
                    apelido, valor, portadorNome,
                    final4, parcelaAtual, parcelaTotal, tipo, responsavelId, responsavelNome,
                    origemAtribuicao, meu, minhaParte, divisao, nomes, jaFoiSeu);
        }

        /**
         * Aplica o apelido do estabelecimento, quando existe um.
         *
         * <p>A descrição original continua indo junto: é ela que casa com o
         * extrato do banco quando alguém for conferir, e é ela que permite
         * depurar o parser.
         */
        public LancamentoResponse comApelido(String nome) {
            return new LancamentoResponse(id, dataCompra, descricao, descricaoNormalizada,
                    nome, valor, portadorNome,
                    final4, parcelaAtual, parcelaTotal, tipo, responsavelId, responsavelNome,
                    origemAtribuicao, meu, minhaParte, divisao, disputantes, jaFoiSeu);
        }

        /** Marca "você já comprou aqui antes" — só vale para quem está olhando. */
        public LancamentoResponse comHistorico(boolean conhecido) {
            return new LancamentoResponse(id, dataCompra, descricao, descricaoNormalizada,
                    apelido, valor, portadorNome,
                    final4, parcelaAtual, parcelaTotal, tipo, responsavelId, responsavelNome,
                    origemAtribuicao, meu, minhaParte, divisao, disputantes, conhecido);
        }

        /**
         * Versão sem o dono, para o pool: o utilizador não precisa saber de quem
         * era antes, e esconder isso evita influenciar a reivindicação.
         */
        public LancamentoResponse anonimo() {
            return new LancamentoResponse(id, dataCompra, descricao, descricaoNormalizada,
                    apelido, valor, portadorNome,
                    final4, parcelaAtual, parcelaTotal, tipo, null, null, null, false,
                    null, List.of(), null, jaFoiSeu);
        }
    }

    /** Um apelido de estabelecimento, para a tela de manutenção do admin. */
    public record ApelidoResponse(Long id, String descricaoNormalizada, String apelido,
                                  LocalDateTime atualizadoEm, String atualizadoPor) {
        public static ApelidoResponse de(br.com.jcard.model.ApelidoEstabelecimento a) {
            return new ApelidoResponse(a.id, a.descricaoNormalizada, a.apelido, a.atualizadoEm,
                    a.atualizadoPor == null ? null : a.atualizadoPor.nome);
        }
    }

    // -------------------------------------------------------------- acerto --

    /**
     * Uma transferência declarada, dentro de um acerto.
     *
     * @param temComprovante o anexo nunca vem embutido: é binário, e a lista do
     *                       admin carrega os pagamentos de todos os acertos da
     *                       fatura de uma vez
     */
    public record PagamentoResponse(Long id, BigDecimal valor, LocalDate pagoEm,
                                    String observacao, LocalDateTime informadoEm,
                                    LocalDateTime confirmadoEm, boolean temComprovante) {
        public static PagamentoResponse de(PagamentoAcerto p, boolean temComprovante) {
            return new PagamentoResponse(p.id, p.valor, p.pagoEm, p.observacao,
                    p.informadoEm, p.confirmadoEm, temComprovante);
        }
    }

    /**
     * Quanto a pessoa deve, quanto já pagou e em que ponto está a quitação.
     *
     * @param valorPago  a soma das transferências declaradas, confirmadas ou não
     * @param saldo      {@code valorDevido - valorPago}; derivado, nunca gravado,
     *                   porque o valor devido muda a cada recálculo do rateio
     * @param pagamentos as transferências, cada uma com o comprovante dela
     */
    public record AcertoResponse(Long id, Long faturaId, LocalDate competencia,
                                 Long usuarioId, String usuarioNome, BigDecimal valorDevido,
                                 StatusAcerto status, LocalDateTime aceitoEm,
                                 LocalDate pagoEm, LocalDateTime informadoEm,
                                 LocalDateTime confirmadoEm, String observacao,
                                 BigDecimal valorPago, BigDecimal saldo,
                                 List<PagamentoResponse> pagamentos) {

        /** Duas consultas por acerto: use {@link #daFatura} para listas de uma fatura. */
        public static AcertoResponse de(Acerto a) {
            List<PagamentoAcerto> pagamentos = PagamentoAcerto.doAcerto(a.id);
            return montar(a, pagamentos, ComprovantePagamento.pagamentosComComprovante(
                    pagamentos.stream().map(p -> p.id).toList()));
        }

        /**
         * Todos os acertos da fatura com os pagamentos e os comprovantes, em
         * três consultas fixas — e não três por pessoa.
         */
        public static List<AcertoResponse> daFatura(Long faturaId, List<Acerto> acertos) {
            Map<Long, List<PagamentoAcerto>> porAcerto = new java.util.HashMap<>();
            for (PagamentoAcerto p : PagamentoAcerto.<PagamentoAcerto>list(
                    "acerto.fatura.id = ?1 order by pagoEm, id", faturaId)) {
                porAcerto.computeIfAbsent(p.acerto.getId(), k -> new java.util.ArrayList<>()).add(p);
            }
            Set<Long> comComprovante =
                    ComprovantePagamento.pagamentosComComprovanteDaFatura(faturaId);
            return acertos.stream()
                    .map(a -> montar(a, porAcerto.getOrDefault(a.id, List.of()), comComprovante))
                    .toList();
        }

        private static AcertoResponse montar(Acerto a, List<PagamentoAcerto> pagamentos,
                                             Set<Long> comComprovante) {
            BigDecimal pago = pagamentos.stream()
                    .map(p -> p.valor)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            return new AcertoResponse(a.id, a.fatura.getId(), a.fatura.competencia,
                    a.usuario.getId(), a.usuario.nome, a.valorDevido, a.status,
                    a.aceitoEm, a.pagoEm, a.informadoEm, a.confirmadoEm, a.observacao,
                    pago, a.valorDevido.subtract(pago),
                    pagamentos.stream()
                            .map(p -> PagamentoResponse.de(p, comComprovante.contains(p.id)))
                            .toList());
        }
    }

    /**
     * A tela principal do utilizador numa fatura.
     *
     * @param pool          lançamentos sem dono — o que ele pode assumir
     * @param meus          o que já é dele, cada um com a parte dele
     * @param encargos      IOF, anuidade e afins, com a fatia que coube a ele; só
     *                      aparecem para quem usou o cartão no mês
     * @param totalCompras  soma das partes dele nos lançamentos
     * @param totalEncargos a fatia dele nos encargos
     * @param total         quanto ele deve hoje nessa fatura (a soma dos dois)
     */
    public record MinhasContas(FaturaResponse fatura,
                               List<LancamentoResponse> pool,
                               List<LancamentoResponse> meus,
                               List<LancamentoResponse> encargos,
                               BigDecimal totalCompras,
                               BigDecimal totalEncargos,
                               BigDecimal total,
                               AcertoResponse acerto,
                               PixResponse pix) {
    }

    /**
     * A conta de uma pessoa aberta para o admin conferir, linha a linha.
     *
     * <p>Serve para responder "isso foi rateado com ela ou não?" sem ter de
     * abrir o banco. Sai do <b>mesmo</b> rateio que a tela da pessoa e que a
     * conciliação — se viesse de outra conta, conferir aqui não provaria nada.
     *
     * @param participante  se ela conta como "usou o cartão no mês"; é isso que
     *                      decide se os encargos são divididos com ela
     * @param participantes entre quem os encargos estão sendo divididos, na
     *                      ordem em que os centavos de sobra caem
     * @param compras       o que é dela por ter assumido ou por parte de conta
     *                      dividida, cada uma com a fatia dela
     * @param encargos      os encargos da fatura com a fatia dela; vazio quando
     *                      ela não é participante
     * @param acerto        o acerto <b>gravado</b>, para comparar com o rateio
     *                      recalculado agora
     * @param diferencaAcerto {@code acerto - total}: zero quando bate, e é o
     *                      número que denuncia acerto congelado (quem já pagou
     *                      não é recalculado, de propósito). {@code null} quando
     *                      ainda não há acerto
     */
    public record DetalheDoUtilizador(Pessoa usuario,
                                      boolean participante,
                                      List<Pessoa> participantes,
                                      List<LancamentoResponse> compras,
                                      List<LancamentoResponse> encargos,
                                      BigDecimal totalCompras,
                                      BigDecimal totalEncargos,
                                      BigDecimal total,
                                      AcertoResponse acerto,
                                      BigDecimal diferencaAcerto) {
    }

    /** De onde saiu a chave PIX que está valendo. */
    public enum OrigemPix {
        /** Salva pelo admin na tela; a partir daí é ela que vale. */
        APP,
        /** Ainda vem de {@code JCARD_PIX_CHAVE}, o valor inicial da instalação. */
        AMBIENTE,
        /** Ninguém configurou, nem por um caminho nem por outro. */
        NENHUMA
    }

    /**
     * A chave para onde o dinheiro vai. Vem da configuração, não do código.
     *
     * @param configurada se existe chave para mostrar. Vem separado do texto
     *                    porque a tela precisa saber a diferença entre "esta é a
     *                    chave, copie" e "ninguém configurou ainda": oferecer o
     *                    botão de copiar num aviso de configuração manda a
     *                    pessoa pagar para lugar nenhum. Quando é {@code false},
     *                    {@code chave} vem vazia.
     * @param origem      qual das duas fontes respondeu. Só a tela do admin usa,
     *                    mas viaja junto para não existir um segundo endpoint
     *                    que responda quase a mesma coisa: é informação sobre a
     *                    própria chave, que todo mundo já vê.
     */
    public record PixResponse(String tipo, String chave, String titular,
                              boolean configurada, OrigemPix origem) {
    }
}
