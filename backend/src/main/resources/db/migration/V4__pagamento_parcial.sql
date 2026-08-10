-- ============================================================================
-- JcardApp — V4: o acerto passa a ser quitado por N pagamentos
--
-- Até aqui o app registrava quanto a pessoa DEVE e nunca quanto ela PAGOU: um
-- comprovante por acerto, e reenviar substituía o anterior. O caso real que
-- quebra isso é banal — a pessoa paga R$ 100, o valor dela sobe para R$ 130 no
-- fechamento (o divisor do encargo mudou, o admin atribuiu mais um lançamento)
-- e ela faz uma transferência complementar. Com o modelo antigo o segundo
-- comprovante apagava o primeiro, e a prova de que os R$ 100 saíram sumia.
--
-- Agora cada transferência é uma linha com valor, data e comprovante próprios.
-- O saldo é derivado (devido - soma dos pagamentos), nunca gravado: dado
-- derivado persistido divergiria do rateio no primeiro recálculo — a mesma
-- razão pela qual o encargo rateado não vira linha no banco.
-- ============================================================================

CREATE TABLE pagamento_acerto (
    id             BIGSERIAL     PRIMARY KEY,
    acerto_id      BIGINT        NOT NULL REFERENCES acerto (id) ON DELETE CASCADE,
    valor          NUMERIC(12,2) NOT NULL,
    pago_em        DATE          NOT NULL,
    observacao     VARCHAR(400),
    informado_em   TIMESTAMP     NOT NULL DEFAULT NOW(),
    confirmado_em  TIMESTAMP,
    confirmado_por BIGINT        REFERENCES usuario (id) ON DELETE SET NULL,
    CONSTRAINT ck_pagamento_valor CHECK (valor <> 0)
);

CREATE INDEX ix_pagamento_acerto ON pagamento_acerto (acerto_id);

-- ------------------------------------------- o que já existe vira um pagamento --
-- Todo comprovante gravado até aqui é a prova de um pagamento que quitava o
-- acerto inteiro (era a única forma de declarar pagamento). Vira uma linha com
-- o valor devido no momento, preservando a confirmação do admin: apagar isso
-- seria negar que o dinheiro saiu.
INSERT INTO pagamento_acerto (acerto_id, valor, pago_em, observacao,
                              informado_em, confirmado_em, confirmado_por)
SELECT a.id,
       a.valor_devido,
       COALESCE(a.pago_em, CURRENT_DATE),
       a.observacao,
       COALESCE(a.informado_em, NOW()),
       a.confirmado_em,
       a.confirmado_por
  FROM acerto a
 WHERE EXISTS (SELECT 1 FROM comprovante_pagamento c WHERE c.acerto_id = a.id);

-- ------------------------------ o comprovante passa a ser do pagamento, não do acerto --
ALTER TABLE comprovante_pagamento
    ADD COLUMN pagamento_id BIGINT REFERENCES pagamento_acerto (id) ON DELETE CASCADE;

UPDATE comprovante_pagamento c
   SET pagamento_id = p.id
  FROM pagamento_acerto p
 WHERE p.acerto_id = c.acerto_id;

-- Comprovante órfão não deveria existir (a FK do acerto era NOT NULL), mas se
-- existir é melhor perder o anexo do que travar a migration com um NOT NULL.
DELETE FROM comprovante_pagamento WHERE pagamento_id IS NULL;

ALTER TABLE comprovante_pagamento ALTER COLUMN pagamento_id SET NOT NULL;
ALTER TABLE comprovante_pagamento ADD CONSTRAINT uq_comprovante_pagamento UNIQUE (pagamento_id);
ALTER TABLE comprovante_pagamento DROP COLUMN acerto_id;
