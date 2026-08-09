# Roadmap

O que ficou para depois, e por quê. Cada item diz **o problema** antes da
solução — a ideia é que daqui a três meses dê para lembrar o motivo, não só a
tarefa.

Ordem = prioridade. O bloco 1 é dívida da entrega da conta dividida; o bloco 2 é
o que o Danilo pediu para melhorar no fluxo de indicação.

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

## 2. Fluxo de indicação (pedido do Danilo)

O ponto em comum dos quatro: hoje a indicação é boa para quem já entendeu o app,
e áspera para quem abre pela primeira vez no celular no fim do mês.

### 2.1 Desfazer uma indicação feita por engano

**Problema**: marcar "foi minha" no lançamento errado é o erro mais fácil de
cometer — a lista é longa, os nomes de estabelecimento são parecidos e o botão é
grande. Hoje dá para devolver ao pool com "não foi minha", **mas só enquanto a
fatura está em `EM_AVALIACAO`**. Depois de conciliada, o utilizador não tem saída
nenhuma: precisa falar com o admin, que só consegue resolver reabrindo o acerto.

**Ideia**: um caminho explícito de "voltar ao estágio de indicação" —
provavelmente uma ação do admin que devolve a fatura de `CONCILIADA` para
`EM_AVALIACAO`, recalculando os acertos e anulando os aceites (quem já pagou
fica como está, como na regra §5). Precisa decidir o que acontece com acerto
`INFORMADO` no meio do caminho.

**Cuidado**: é a operação que mais mexe em dinheiro já combinado. Tem de ser
auditada e avisar por e-mail quem for afetado.

### 2.2 UX da página de indicação

**Problema**: a tela lista o pool cru, na ordem da fatura. Numa fatura real são
**514 lançamentos** — a lista fica impraticável no celular, que é onde as pessoas
vão usar.

**Ideias, em ordem de retorno**:

- **busca por descrição** e filtro por faixa de data — resolve 80% do caso "sei
  o que procuro";
- **agrupar por dia**, com subtotal, em vez de uma lista contínua;
- destacar o que é **parcela** (a decisão vale para os meses seguintes, então
  merece mais atenção que uma compra avulsa);
- **desfazer imediato** no toast depois de assumir, para o engano do §2.1 não
  precisar virar processo;
- lembrar o que a pessoa assumiu **no mês anterior** na mesma loja — a maior
  parte das compras se repete, e isso transforma leitura em confirmação.

### 2.3 Indicação melhor apresentada

**Problema**: o lançamento aparece como veio da fatura (`DL*UberRides`,
`PARTMED E ODONTOCO`) — o nome que o banco imprime, não o que a pessoa lembra.
Reconhecer a compra é o trabalho todo do app, e hoje ele é 100% do utilizador.

**Ideias**: apelido por estabelecimento (definido uma vez, reaproveitado sempre),
mostrar o portador/final do cartão quando existir, e agrupar compras repetidas da
mesma loja no mês.

### 2.4 Admin atribuir lançamento a alguém, de forma mandatória

**Problema**: o `ReivindicacaoService.arbitrar` já atribui a quem o admin
escolher — mas na tela ele **só aparece na fila de conflitos**. Se ninguém
reivindicou um lançamento, o admin não tem como dizer "isso foi do João": a única
saída hoje é conciliar e o lançamento cair no titular.

**Ideia**: ação de atribuir direto na lista "Todos os lançamentos" da tela de
conciliação, usando o `arbitrar` que já existe (origem `ADMIN`), com o utilizador
sendo notificado — atribuição mandatória sem aviso é cobrança silenciosa, que é
justamente o que o app evita.

**Decidir**: se o utilizador pode contestar depois. A regra §3.3 diz que "quem
chegou primeiro" nunca decide cobrança contestada; a decisão do admin é o
desempate, mas atribuir sem disputa nenhuma é outra coisa — provavelmente deveria
ser contestável enquanto a fatura estiver aberta.

---

## 3. Infra (já sabido, sem data)

- VM do GCP e projeto no Neon ainda não provisionados — ver §9 do `CLAUDE.md`.
- Pendências de conta do Danilo: GCP, Neon, token do DuckDNS, senha de app do
  Gmail, PAT com `read:packages`.
