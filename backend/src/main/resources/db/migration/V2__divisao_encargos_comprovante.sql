-- ============================================================================
-- JcardApp — V2: conta dividida, encargo rateado e comprovante de pagamento
--
-- Três mudanças de domínio que a V1 não previa:
--   1) uma compra pode ser de MAIS DE UMA pessoa  -> divisao_lancamento
--   2) encargo (IOF, anuidade, juros...) é de TODO MUNDO que usou o cartão
--      no mês -> não vira coluna: é calculado no ConciliacaoService a partir
--      de quem tem lançamento na fatura, para não guardar dado derivado que
--      possa divergir do rateio real
--   3) declarar o pagamento passa a exigir aceite do valor + comprovante
--      -> acerto.aceito_em / pago_em + tabela comprovante_pagamento
-- ============================================================================

-- ------------------------------------------------------- conta dividida --
-- Quando existe divisão, ela é a VERDADE do rateio daquele lançamento e o
-- lancamento.responsavel_id passa a significar apenas "quem organizou a
-- divisão" — continua preenchido porque é dele que a regra de parcelamento
-- (compromisso_parcelado) tira o dono das parcelas seguintes.
--
-- A invariante "as contas sempre batem" exige que a soma das partes reproduza
-- o valor do lançamento. Isso não cabe num CHECK de linha; quem garante é o
-- DivisaoService, e o teste de conciliação cobre.
CREATE TABLE divisao_lancamento (
    id             BIGSERIAL PRIMARY KEY,
    lancamento_id  BIGINT        NOT NULL REFERENCES lancamento (id) ON DELETE CASCADE,
    usuario_id     BIGINT        NOT NULL REFERENCES usuario (id)    ON DELETE CASCADE,
    valor          NUMERIC(12,2) NOT NULL,
    criado_em      TIMESTAMP     NOT NULL DEFAULT NOW(),
    criado_por     BIGINT        REFERENCES usuario (id) ON DELETE SET NULL,
    -- uma parte por pessoa: dividir duas vezes com a mesma pessoa é erro de UI,
    -- não um caso de uso
    CONSTRAINT uq_divisao UNIQUE (lancamento_id, usuario_id)
);
CREATE INDEX idx_divisao_lancamento ON divisao_lancamento (lancamento_id);
CREATE INDEX idx_divisao_usuario    ON divisao_lancamento (usuario_id);

-- --------------------------------------------------- aceite do acerto --
-- O ciclo ganha uma etapa: ABERTO -> ACEITO -> INFORMADO -> CONFIRMADO.
-- ACEITO é a pessoa dizendo "conferi, o valor está certo"; só depois dele o
-- app abre o formulário de pagamento. Sem esse passo, a discussão sobre o
-- valor acontecia DEPOIS do dinheiro sair.
ALTER TABLE acerto
    ADD COLUMN aceito_em TIMESTAMP,
    ADD COLUMN pago_em   DATE;

ALTER TABLE acerto DROP CONSTRAINT ck_acerto_status;
ALTER TABLE acerto ADD  CONSTRAINT ck_acerto_status
    CHECK (status IN ('ABERTO','ACEITO','INFORMADO','CONFIRMADO'));

-- ----------------------------------------------------- comprovante --
-- Tabela separada de propósito: o bytea não pode viajar junto em toda
-- consulta de acerto (a lista do admin lê todos os acertos da fatura).
-- Guardar no Postgres e não em disco é o que sobrevive à VM ser recriada,
-- e entra de graça no backup point-in-time do Neon. O tamanho é limitado no
-- serviço; ~300 KB por comprovante contra 0,5 GB de plano gratuito.
CREATE TABLE comprovante_pagamento (
    id          BIGSERIAL PRIMARY KEY,
    acerto_id   BIGINT       NOT NULL UNIQUE REFERENCES acerto (id) ON DELETE CASCADE,
    nome        VARCHAR(255) NOT NULL,
    tipo        VARCHAR(100) NOT NULL,
    tamanho     INT          NOT NULL,
    conteudo    BYTEA        NOT NULL,
    enviado_em  TIMESTAMP    NOT NULL DEFAULT NOW()
);
