-- ============================================================================
-- JcardApp — V7: o admin dá baixa no pagamento que não passou pelo app
--
-- O comprovante é obrigatório para o utilizador declarar a transferência, e
-- continua sendo: ele é a prova de que o dinheiro saiu. Só que parte das
-- pessoas paga o PIX e nunca abre o app para mandar o print — e o acerto delas
-- fica ABERTO para sempre, com o admin olhando o extrato e sabendo que entrou.
-- A saída que existia era pedir o print de novo; na prática o admin acabaria
-- confirmando de cabeça, e a fatura nunca fecharia.
--
-- Agora ele registra a transferência em nome da pessoa. O que o registro NÃO
-- pode ser é indistinguível do que a pessoa declarou: sem comprovante, a única
-- prova é a palavra de quem registrou, e isso tem de estar no dado. Daí a
-- coluna — quem registrou a baixa. NULL continua significando "foi a própria
-- pessoa que declarou, e existe comprovante".
-- ============================================================================

ALTER TABLE pagamento_acerto
    ADD COLUMN registrado_por BIGINT REFERENCES usuario (id) ON DELETE SET NULL;

COMMENT ON COLUMN pagamento_acerto.registrado_por IS
    'Admin que deu baixa manual (sem comprovante). NULL = declarado pelo utilizador.';
