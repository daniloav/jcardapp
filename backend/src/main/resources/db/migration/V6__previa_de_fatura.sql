-- ============================================================================
-- JcardApp — V6: prévia da fatura
--
-- O problema: a fatura só existe no dia em que fecha, e aí chegam 514 linhas de
-- uma vez. Todo mundo precisa reconhecer um mês inteiro de compras de memória,
-- no mesmo fim de semana, para o dinheiro sair a tempo.
--
-- O Itaú deixa baixar a fatura EM ABERTO a qualquer momento. A prévia é isso:
-- o CSV parcial do mês em curso, subido quantas vezes o admin quiser. Cada
-- subida sobrescreve a anterior, e o que alguém já assumiu continua dele na
-- prévia seguinte — e, no fim, na fatura de verdade. O trabalho deixa de ser um
-- mutirão no vencimento e vira meia dúzia de toques por semana.
--
-- Por que um status, e não uma tabela separada: uma linha da prévia é um
-- lançamento igual a qualquer outro, e é assumida pelo mesmo caminho (pool,
-- reivindicação, conflito, divisão, apelido). Duplicar o modelo para o mês que
-- ainda não fechou duplicaria também essas cinco regras — e elas são o app.
--
-- O que a prévia NÃO faz, e por isso ela não polui o resto:
--   * não gera acerto        -> ninguém deve nada por uma parcial
--   * não concilia nem fecha -> não há total impresso contra o qual conferir
--   * não vira DIVERGENTE    -> o total dela É a soma; não há o que divergir
--   * não mexe no compromisso de parcelamento -> ver AtribuicaoService
-- ============================================================================

ALTER TABLE fatura DROP CONSTRAINT ck_fatura_status;
ALTER TABLE fatura ADD  CONSTRAINT ck_fatura_status CHECK (
    status IN ('PREVIA','IMPORTADA','DIVERGENTE','EM_AVALIACAO','CONCILIADA','FECHADA')
);

-- Uma prévia por mês, garantido pelo banco: "a prévia de agosto" é singular por
-- definição, e duas linhas aqui seriam duas respostas para a mesma pergunta —
-- com as pessoas assumindo contas em telas diferentes do mesmo mês. Subir de
-- novo apaga a anterior (PreviaService.consumir) em vez de acumular.
CREATE UNIQUE INDEX uq_fatura_previa_competencia
    ON fatura (competencia) WHERE status = 'PREVIA';
