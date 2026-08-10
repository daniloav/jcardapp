# Roadmap

O que ficou para depois, e por quê. Cada item diz **o problema** antes da
solução — a ideia é que daqui a três meses dê para lembrar o motivo, não só a
tarefa.

Ordem = prioridade. O bloco 1 é dívida da entrega da conta dividida; o bloco 2 —
o fluxo de indicação — **foi entregue**, e ficou registrado aqui com as decisões
que ele exigiu, porque são elas que explicam o código.

---

## 1. Pendências da entrega de divisão/encargos/comprovante

### 1.1 Decidir onde mora a chave PIX

**Hoje**: `jcard.pix.*` tem placeholder no `application.properties` e no
`.env.example`; o CPF e o nome reais só existem no `.env` da VM.

**Por que ficou assim**: `daniloav/jcardapp` é **público**. CPF e nome completo
versionados viram dado pessoal indexável, e o app não ganha nada com isso —
lê da variável de ambiente do mesmo jeito.

**A decidir**: se o repositório virar privado, dá para trazer os valores reais
para o `.env.example` e poupar um passo do provisionamento. Enquanto for público,
fica como está.

⚠️ **Antes do primeiro deploy**: definir `JCARD_PIX_CHAVE` e `JCARD_PIX_TITULAR`
no `.env` da VM. Sem isso a tela de pagamento mostra
`defina JCARD_PIX_CHAVE no .env` no lugar da chave — o default é feio de
propósito, para o erro aparecer antes de alguém tentar pagar.

### 1.2 Conferir os acertos abertos no primeiro deploy da V2

**Problema**: a migration V2 muda **como o valor é calculado**, não só o schema.
Encargo que antes caía inteiro no titular passa a ser rateado entre quem usou o
cartão, então o "quanto cada um deve" muda na primeira vez que os acertos forem
recalculados.

**O que fazer**: se houver fatura conciliada esperando pagamento quando a V2
subir, conferir os valores antes de cobrar. Fatura `FECHADA` não é afetada
(acerto `CONFIRMADO` nunca é recalculado).

---

## 2. Fluxo de indicação (pedido do Danilo) — ✅ entregue

O ponto em comum dos quatro: a indicação era boa para quem já entendeu o app, e
áspera para quem abre pela primeira vez no celular no fim do mês.

Migration `V3__reabertura_e_apelidos.sql`. As regras estão em
`REGRAS-DE-NEGOCIO.md` §3.4, §3.9, §3.10 e §4.2; aqui fica **o que foi decidido**
em cada ponto que o item deixou em aberto.

### 2.1 Desfazer uma indicação feita por engano — ✅

O admin devolve a fatura de `CONCILIADA` para `EM_AVALIACAO`
(`ConciliacaoService.reabrirAvaliacao`, botão "Voltar para avaliação" na tela de
conciliação). Três decisões que o item deixou em aberto:

- **O que volta ao pool**: só o que ficou com o titular por falta de dono. Para
  distinguir isso do que o admin arbitrou de propósito, a conciliação passou a
  marcar essas sobras com a origem nova **`SOBRA_CONCILIACAO`** — antes as duas
  eram `ADMIN` e ficavam indistinguíveis.
- **Acerto `INFORMADO`** (a pergunta que ficou): **continua `INFORMADO`**, com o
  comprovante. O dinheiro saiu; apagar isso seria negar o pagamento. O valor
  devido dele é recalculado e a diferença é assunto do admin — por isso a pessoa
  e o admin são avisados.
- **Acerto `CONFIRMADO` barra a reabertura** (409). Ele nunca é recalculado, e
  congelado no meio de um rateio que mudou faria a soma dos acertos deixar de
  reproduzir o total: a fatura ficaria impossível de conciliar de novo. O admin
  reabre aquele acerto antes — decisão explícita sobre dinheiro já pago.

Auditada, com motivo, e e-mail para todo mundo com acerto na fatura.

### 2.2 UX da página de indicação — ✅

No pool: **busca** (casa contra o apelido e contra o nome do banco), **faixa de
data**, **agrupamento por dia ou por estabelecimento** com subtotal, filtros
rápidos de "só parcelas" e "só onde já comprei", **parcela em destaque** (com o
aviso de que a decisão vale para os meses seguintes) e **desfazer no próprio
toast** depois de assumir — o engano do §2.1 deixa de precisar virar processo.

O "já comprei aqui" vem do campo `jaFoiSeu`, calculado por utilizador contra as
**outras** faturas (`Lancamento.estabelecimentosDe`): uma consulta por
carregamento, não uma por lançamento — numa fatura de 514 linhas isso importa.

### 2.3 Indicação melhor apresentada — ✅

**Apelido por estabelecimento**, chaveado pela descrição normalizada, então vale
para os meses seguintes. Decisões:

- é **global**, não por pessoa: quem apelida está descrevendo a loja;
- **qualquer utilizador** apelida, não só o admin — quem reconhece a loja é quem
  comprou nela —, e a alteração vai para a auditoria com o nome de quem fez;
- a **descrição original continua na tela** ("na fatura: DL*UberRides"): é ela
  que casa com o extrato do banco e que permite depurar o parser.

Também entraram o portador do cartão na linha do pool e o agrupamento de compras
repetidas da mesma loja no mês (o modo "por estabelecimento").

### 2.4 Admin atribuir lançamento a alguém — ✅

Seletor de dono direto na lista "Todos os lançamentos", usando o `arbitrar` que
já existia (origem `ADMIN`). Sobre a pergunta que ficou — se o utilizador pode
contestar: **pode**, com "não foi minha", enquanto a fatura estiver em avaliação.
Atribuir sem disputa não é um desempate; é o admin dizendo de quem é a conta, e
a §3.3 vale igual para "o admin decidiu". A pessoa recebe e-mail dizendo
exatamente isso.

---

## 3. Infra (já sabido, sem data)

- VM do GCP e projeto no Neon ainda não provisionados — ver §9 do `CLAUDE.md`.
- Pendências de conta do Danilo: GCP, Neon, token do DuckDNS, senha de app do
  Gmail, PAT com `read:packages`.
