-- ============================================================================
-- JcardApp — V3: reabrir a avaliação e apelidar o estabelecimento
--
-- Duas mudanças, as duas nascidas do mesmo problema: a indicação é boa para
-- quem já entendeu o app e áspera para quem abre pela primeira vez no celular
-- no fim do mês (ROADMAP §2).
--
--   1) desfazer uma indicação feita por engano DEPOIS da conciliação
--      -> a fatura precisa voltar de CONCILIADA para EM_AVALIACAO, e para isso
--         a conciliação tem de saber quais lançamentos ela mesma deu ao titular
--         por falta de dono -> nova origem SOBRA_CONCILIACAO
--   2) o lançamento aparece como o banco imprime ("DL*UberRides")
--      -> apelido por estabelecimento, definido uma vez e reaproveitado sempre
-- ============================================================================

-- --------------------------------------------- origem: sobra da conciliação --
-- Até aqui, o que ninguém assumiu virava ADMIN na conciliação — a mesma origem
-- da arbitragem. Indistinguíveis, e é justamente essa distinção que a
-- reabertura precisa: devolver ao pool o que só ficou com o titular por falta
-- de dono, sem desfazer o que o admin decidiu de propósito.
ALTER TABLE lancamento DROP CONSTRAINT ck_lancamento_origem;
ALTER TABLE lancamento ADD  CONSTRAINT ck_lancamento_origem CHECK (
    origem_atribuicao IS NULL
    OR origem_atribuicao IN ('MANUAL','HERDADA_PARCELA','REGRA_CARTAO','ADMIN','SOBRA_CONCILIACAO')
);

-- Faturas já conciliadas antes desta migration: o que está no titular com
-- origem ADMIN e sem reivindicação nenhuma só pode ter vindo da conciliação —
-- arbitragem pressupõe alguém disputando.
UPDATE lancamento l
   SET origem_atribuicao = 'SOBRA_CONCILIACAO'
 WHERE l.origem_atribuicao = 'ADMIN'
   AND NOT EXISTS (SELECT 1 FROM reivindicacao r WHERE r.lancamento_id = l.id);

-- ------------------------------------------------ apelido de estabelecimento --
-- Chaveado pela descricao_normalizada (sem acento, caixa alta, sem o sufixo de
-- parcela) — a mesma chave que o parcelamento usa para casar a mesma compra
-- entre faturas. Assim o apelido dado uma vez vale para os meses seguintes.
--
-- É vocabulário compartilhado, não preferência pessoal: quem apelida
-- "DL*UberRides" de "Uber" está descrevendo a loja para todo mundo. Por isso
-- uma linha por estabelecimento, e não uma por (pessoa, estabelecimento) — e
-- por isso quem alterou fica registrado.
CREATE TABLE apelido_estabelecimento (
    id                     BIGSERIAL PRIMARY KEY,
    descricao_normalizada  VARCHAR(255) NOT NULL UNIQUE,
    apelido                VARCHAR(120) NOT NULL,
    criado_em              TIMESTAMP    NOT NULL DEFAULT NOW(),
    atualizado_em          TIMESTAMP    NOT NULL DEFAULT NOW(),
    atualizado_por         BIGINT       REFERENCES usuario (id) ON DELETE SET NULL
);
