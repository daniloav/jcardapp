-- ============================================================================
-- JcardApp — schema inicial
--
-- Domínio: o titular (pai) empresta o cartão; a fatura é quebrada lançamento a
-- lançamento e cada utilizador assume o que é seu. Duas regras estruturais:
--   1) as contas sempre batem  -> ver jcard.ConciliacaoService
--   2) parcelamento gruda      -> tabela compromisso_parcelado
-- ============================================================================

-- ---------------------------------------------------------------- usuários --
-- Papéis são FLAGS, não uma coluna "role": uma pessoa pode ser admin E utilizador
-- (o pai usa o próprio cartão). Evita a migração de consolidação que o EBD precisou.
CREATE TABLE usuario (
    id                    BIGSERIAL PRIMARY KEY,
    nome                  VARCHAR(120)  NOT NULL,
    login                 VARCHAR(60)   NOT NULL UNIQUE,
    email                 VARCHAR(160)  NOT NULL UNIQUE,
    senha_hash            VARCHAR(120)  NOT NULL,
    admin                 BOOLEAN       NOT NULL DEFAULT FALSE,
    utilizador            BOOLEAN       NOT NULL DEFAULT TRUE,
    ativo                 BOOLEAN       NOT NULL DEFAULT TRUE,
    precisa_trocar_senha  BOOLEAN       NOT NULL DEFAULT TRUE,
    recebe_notificacoes   BOOLEAN       NOT NULL DEFAULT TRUE,
    criado_em             TIMESTAMP     NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_usuario_ativo ON usuario (ativo) WHERE ativo;

-- ----------------------------------------------------------------- cartões --
-- A fatura do Itaú vem seccionada por portador (cartão adicional). Quando um
-- adicional é sempre da mesma pessoa, dono_padrao_id atribui automaticamente.
CREATE TABLE cartao (
    id              BIGSERIAL PRIMARY KEY,
    apelido         VARCHAR(80)  NOT NULL,
    final_4         VARCHAR(4)   NOT NULL UNIQUE,
    portador_nome   VARCHAR(120),
    dono_padrao_id  BIGINT       REFERENCES usuario (id) ON DELETE SET NULL,
    titular         BOOLEAN      NOT NULL DEFAULT FALSE,
    ativo           BOOLEAN      NOT NULL DEFAULT TRUE,
    criado_em       TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ----------------------------------------------------------------- faturas --
-- hash_pdf é UNIQUE: reimportar o mesmo arquivo é rejeitado (idempotência).
-- O PDF original NÃO é persistido (dado sensível); guardamos o texto extraído,
-- que basta para reprocessar sem pedir o arquivo de novo.
CREATE TABLE fatura (
    id              BIGSERIAL PRIMARY KEY,
    competencia     DATE          NOT NULL,
    vencimento      DATE,
    valor_total     NUMERIC(12,2) NOT NULL,
    valor_lancado   NUMERIC(12,2) NOT NULL DEFAULT 0,
    status          VARCHAR(20)   NOT NULL DEFAULT 'IMPORTADA',
    hash_pdf        CHAR(64)      NOT NULL UNIQUE,
    nome_arquivo    VARCHAR(255),
    texto_extraido  TEXT,
    emissor         VARCHAR(30)   NOT NULL DEFAULT 'ITAU',
    importada_em    TIMESTAMP     NOT NULL DEFAULT NOW(),
    importada_por   BIGINT        REFERENCES usuario (id) ON DELETE SET NULL,
    fechada_em      TIMESTAMP,
    CONSTRAINT ck_fatura_status CHECK (
        status IN ('IMPORTADA','DIVERGENTE','EM_AVALIACAO','CONCILIADA','FECHADA')
    )
);
CREATE INDEX idx_fatura_competencia ON fatura (competencia DESC);

-- -------------------------------------------------------------- lançamentos --
CREATE TABLE lancamento (
    id                     BIGSERIAL PRIMARY KEY,
    fatura_id              BIGINT        NOT NULL REFERENCES fatura (id) ON DELETE CASCADE,
    data_compra            DATE          NOT NULL,
    descricao              VARCHAR(255)  NOT NULL,
    descricao_normalizada  VARCHAR(255)  NOT NULL,
    valor                  NUMERIC(12,2) NOT NULL,
    moeda                  VARCHAR(3)    NOT NULL DEFAULT 'BRL',
    portador_nome          VARCHAR(120),
    final_4                VARCHAR(4),
    parcela_atual          INT,
    parcela_total          INT,
    chave_parcelamento     CHAR(64),
    tipo                   VARCHAR(20)   NOT NULL DEFAULT 'COMPRA',
    responsavel_id         BIGINT        REFERENCES usuario (id) ON DELETE SET NULL,
    origem_atribuicao      VARCHAR(20),
    atribuido_em           TIMESTAMP,
    linha_original         VARCHAR(400),
    CONSTRAINT ck_lancamento_tipo CHECK (
        tipo IN ('COMPRA','ESTORNO','ENCARGO','PAGAMENTO','IOF','ANUIDADE','AJUSTE')
    ),
    CONSTRAINT ck_lancamento_origem CHECK (
        origem_atribuicao IS NULL
        OR origem_atribuicao IN ('MANUAL','HERDADA_PARCELA','REGRA_CARTAO','ADMIN')
    ),
    -- responsável e origem andam juntos: ou os dois preenchidos, ou nenhum
    CONSTRAINT ck_lancamento_atribuicao CHECK (
        (responsavel_id IS NULL AND origem_atribuicao IS NULL)
        OR (responsavel_id IS NOT NULL AND origem_atribuicao IS NOT NULL)
    ),
    CONSTRAINT ck_lancamento_parcela CHECK (
        (parcela_atual IS NULL AND parcela_total IS NULL)
        OR (parcela_atual >= 1 AND parcela_total >= 1 AND parcela_atual <= parcela_total)
    )
);
CREATE INDEX idx_lancamento_fatura      ON lancamento (fatura_id);
CREATE INDEX idx_lancamento_responsavel ON lancamento (responsavel_id);
CREATE INDEX idx_lancamento_chave       ON lancamento (chave_parcelamento);
-- O "pool" (lançamentos sem dono) é a consulta mais quente da tela do utilizador.
CREATE INDEX idx_lancamento_pool ON lancamento (fatura_id) WHERE responsavel_id IS NULL;

-- ------------------------------------------------------------ reivindicações --
-- Um utilizador reivindica um lançamento. 2+ PENDENTE no mesmo lançamento = conflito,
-- que cai na fila do admin para arbitrar.
CREATE TABLE reivindicacao (
    id             BIGSERIAL PRIMARY KEY,
    lancamento_id  BIGINT      NOT NULL REFERENCES lancamento (id) ON DELETE CASCADE,
    usuario_id     BIGINT      NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    status         VARCHAR(15) NOT NULL DEFAULT 'PENDENTE',
    observacao     VARCHAR(400),
    criado_em      TIMESTAMP   NOT NULL DEFAULT NOW(),
    resolvido_em   TIMESTAMP,
    resolvido_por  BIGINT      REFERENCES usuario (id) ON DELETE SET NULL,
    CONSTRAINT uq_reivindicacao UNIQUE (lancamento_id, usuario_id),
    CONSTRAINT ck_reivindicacao_status CHECK (status IN ('PENDENTE','ACEITA','REJEITADA'))
);
CREATE INDEX idx_reivindicacao_pendente ON reivindicacao (lancamento_id) WHERE status = 'PENDENTE';

-- ------------------------------------------------------ compromisso parcelado --
-- REGRA: assumiu a parcela 1/10 -> as 9 seguintes já entram atribuídas.
-- A chave casa o mesmo parcelamento entre faturas de meses diferentes.
CREATE TABLE compromisso_parcelado (
    id                     BIGSERIAL PRIMARY KEY,
    chave_parcelamento     CHAR(64)      NOT NULL UNIQUE,
    usuario_id             BIGINT        NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    descricao_normalizada  VARCHAR(255)  NOT NULL,
    final_4                VARCHAR(4),
    parcela_total          INT           NOT NULL,
    valor_parcela          NUMERIC(12,2) NOT NULL,
    ultima_parcela_vista   INT           NOT NULL DEFAULT 0,
    ativo                  BOOLEAN       NOT NULL DEFAULT TRUE,
    criado_em              TIMESTAMP     NOT NULL DEFAULT NOW(),
    origem_lancamento_id   BIGINT        REFERENCES lancamento (id) ON DELETE SET NULL
);
CREATE INDEX idx_compromisso_ativo ON compromisso_parcelado (chave_parcelamento) WHERE ativo;

-- ------------------------------------------------------------------ acertos --
-- Quanto cada utilizador deve numa fatura + o ciclo de quitação.
CREATE TABLE acerto (
    id             BIGSERIAL PRIMARY KEY,
    fatura_id      BIGINT        NOT NULL REFERENCES fatura (id) ON DELETE CASCADE,
    usuario_id     BIGINT        NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    valor_devido   NUMERIC(12,2) NOT NULL DEFAULT 0,
    status         VARCHAR(15)   NOT NULL DEFAULT 'ABERTO',
    observacao     VARCHAR(400),
    informado_em   TIMESTAMP,
    confirmado_em  TIMESTAMP,
    confirmado_por BIGINT        REFERENCES usuario (id) ON DELETE SET NULL,
    CONSTRAINT uq_acerto UNIQUE (fatura_id, usuario_id),
    CONSTRAINT ck_acerto_status CHECK (status IN ('ABERTO','INFORMADO','CONFIRMADO'))
);
CREATE INDEX idx_acerto_usuario ON acerto (usuario_id);

-- ---------------------------------------------------------------- auditoria --
CREATE TABLE auditoria (
    id           BIGSERIAL PRIMARY KEY,
    usuario_id   BIGINT       REFERENCES usuario (id) ON DELETE SET NULL,
    usuario_nome VARCHAR(120),
    acao         VARCHAR(30)  NOT NULL,
    entidade     VARCHAR(40)  NOT NULL,
    entidade_id  BIGINT,
    detalhe      VARCHAR(600),
    criado_em    TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_auditoria_criado ON auditoria (criado_em DESC);
