-- ============================================================================
-- JcardApp — V5: a chave PIX passa a ser editável pelo admin
--
-- Até aqui a chave só existia como variável de ambiente (JCARD_PIX_CHAVE), o
-- que na prática queria dizer: trocar a chave = entrar por ssh na VM, editar o
-- .env e recriar o container. Mas trocar a chave PIX é decisão do dono do
-- cartão, não de quem administra o servidor — e o titular não abre terminal.
--
-- A variável de ambiente continua valendo: é o valor inicial, usado enquanto
-- esta tabela estiver vazia. Assim o deploy existente não muda de comportamento
-- e nada precisa ser preenchido duas vezes. Salvou pela tela uma vez, o banco
-- passa a mandar — senão editar pelo app e não ver efeito seria pior do que
-- não ter a tela.
--
-- O CPF continua FORA do git: ele mora aqui ou no .env da VM, nunca no
-- repositório, que é público.
-- ============================================================================

CREATE TABLE configuracao_pix (
    id             BIGSERIAL    PRIMARY KEY,
    tipo           VARCHAR(20)  NOT NULL,
    chave          VARCHAR(140) NOT NULL,
    titular        VARCHAR(120) NOT NULL,
    atualizado_em  TIMESTAMP    NOT NULL DEFAULT NOW(),
    atualizado_por BIGINT       REFERENCES usuario (id) ON DELETE SET NULL,

    -- Uma linha só, garantido pelo banco. "A chave para onde o dinheiro vai" é
    -- singular por definição: duas linhas aqui seriam duas respostas possíveis
    -- para a mesma pergunta, e a tela mostraria uma delas por sorte da ordem.
    linha_unica    BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_configuracao_pix_unica UNIQUE (linha_unica),
    CONSTRAINT ck_configuracao_pix_unica CHECK (linha_unica),
    CONSTRAINT ck_configuracao_pix_chave CHECK (length(btrim(chave)) > 0)
);
