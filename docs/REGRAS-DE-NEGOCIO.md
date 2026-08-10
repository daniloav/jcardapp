# Regras de negócio

Catálogo do que o app garante. Cada regra aponta para onde ela vive no código e,
quando existe, para o teste que a protege (`RegrasDeNegocioTest`).

## 1. As contas sempre batem

### 1.1 A leitura tem de reproduzir o total impresso

`Σ lancamento.valor == fatura.valorTotal`

Se não bate, a fatura vai para **`DIVERGENTE`**, ninguém é notificado e nada
avança. É deliberado: uma linha perdida pelo parser significa que o rateio seria
feito sobre dado incompleto, cobrando errado de alguém.

`ConciliacaoService.validarLeitura` · testes `somaBateLiberaAvaliacao`,
`centavoFaltandoTrava`, `divergenteNaoConcilia`

### 1.2 O rateio tem de reproduzir o total

`Σ acerto.valorDevido == fatura.valorTotal`

Na conciliação, todo lançamento **reivindicável** que ficou sem dono é atribuído
ao **titular**. Se a soma ainda assim não fechar, a transação é abortada — nada é
gravado.

Quem monta o rateio é o `RateioService`, em três caminhos: encargo rateado (§1.4),
conta dividida (§1.5) e lançamento comum (inteiro para o responsável, ou para o
titular se não tiver).

`ConciliacaoService.conciliar` · `RateioService.ratear` · testes
`sobraVaiParaOTitular`, `encargoERateadoEntreQuemUsou`, `contaDivididaEntreTres`

### 1.3 A divisão em partes não perde nem inventa centavo

R$ 10,00 entre três sai **3,34 + 3,33 + 3,33**, nunca três vezes 3,33.
Arredondar cada parte isoladamente sumiria com um centavo, e aí a invariante 1.2
cairia e o fechamento seria abortado.

A sobra vai para o **titular**: quando não divide exato, o dono do cartão absorve,
em vez de o centavo cair em quem o app achar primeiro.

`RateioService.dividir` · teste `divisaoEmPartesFechaSempre`,
`sobraDeCentavosVaiParaOTitular`

### 1.4 Encargo é de todo mundo que usou o cartão

`ENCARGO`, `IOF`, `ANUIDADE` e `AJUSTE` são divididos entre **todos os
participantes** da fatura, sempre — ninguém reivindica encargo. Não há como um
só ser o culpado pelo IOF de uma fatura que várias pessoas movimentaram.

**Participante** é quem tem pelo menos um lançamento reivindicável na fatura,
assumido sozinho ou como parte de uma conta dividida. Se ninguém assumiu nada, o
titular fica com tudo.

Por isso o encargo **continua sem responsável** depois da conciliação: dar dono a
ele desligaria o rateio. `PAGAMENTO` (quitação da fatura anterior) fica de fora —
é ato do titular, não gasto de ninguém.

`TipoLancamento.rateavel` · `RateioService.participantes` · testes
`encargoERateadoEntreQuemUsou`, `encargoNaoVaiParaOTitularNaConciliacao`,
`pagamentoAnteriorFicaComOTitular`

### 1.5 A soma das partes reproduz o valor do lançamento

Uma conta dividida grava uma linha por pessoa em `divisao_lancamento`, e a soma
tem de bater com o valor do lançamento **ao centavo**. Fora disso a API recusa
com 422 dizendo quanto falta ou sobra: aceitar criaria dinheiro que a fatura não
tem, e o erro só apareceria lá na frente, abortando o fechamento inteiro.

`DivisaoService.validar` · teste `divisaoQueNaoFechaERecusada`

### 1.6 Créditos entram negativos

Estorno e pagamento reduzem o total. O parser inverte o sinal mesmo quando o PDF
imprime sem ele, senão a invariante 1.1 nunca fecharia.

`ItauFaturaParser.montar` · testes `estornoAbateDaConta`, `classificacao`

## 2. Parcelamento gruda

### 2.1 Assumir uma parcela vale para as seguintes

Aceitar um lançamento com `parcelaTotal > 1` cria um `CompromissoParcelado`. Nas
próximas importações, as parcelas seguintes já nascem atribuídas com origem
`HERDADA_PARCELA` — a pessoa não precisa reivindicar todo mês.

`AtribuicaoService.registrarCompromisso` · teste `parcelaSeguinteHerdaDono`

### 2.2 O compromisso se encerra na última parcela

Sem isso ele seguiria capturando compras futuras parecidas na mesma loja.

`CompromissoParcelado.registrarParcela` · teste `compromissoEncerraNoFim`

### 2.3 Valor destoante não atribui

A chave de casamento ignora o valor (R$ 100 em 3x sai 33,34 + 33,33 + 33,33). A
proteção contra colisão é a tolerância de **R$ 1,00 ou 5%**, o que for maior:
fora disso o lançamento fica no pool para conferência.

`AtribuicaoService.valorCompativel` · testes `valorDestoanteNaoAtribui`,
`arredondamentoDeParcelaAindaCasa`

### 2.4 Desistir desfaz o compromisso

Só quando quem desiste é o dono do lançamento que **originou** o compromisso.

`AtribuicaoService.encerrarCompromisso`

## 3. Quem fica com o lançamento

### 3.1 Precedência na importação

1. `CompromissoParcelado` ativo casando a chave → `HERDADA_PARCELA`
2. `Cartao.donoPadrao` do cartão adicional → `REGRA_CARTAO`
3. nada casou → **pool**, disponível para reivindicação

Na dúvida, pool. Atribuir errado gera cobrança indevida e desgaste entre pessoas
da mesma família; pedir confirmação custa um toque.

`AtribuicaoService.aplicar`

### 3.2 Um pretendente leva na hora

O caso comum não precisa de burocracia: se só uma pessoa reivindica, o lançamento
é dela imediatamente.

`ReivindicacaoService.resolver` · teste `desistirLiberaOOutro`

### 3.3 Dois pretendentes devolvem ao pool

Assim que uma segunda pessoa reivindica, o lançamento **sai** de quem estava com
ele e ninguém fica com ele até o admin arbitrar. "Quem chegou primeiro" nunca
decide uma cobrança contestada. O admin é notificado por e-mail.

`ReivindicacaoService.reivindicar` · teste `segundaReivindicacaoViraConflito`

### 3.4 O admin arbitra — e também atribui sem disputa

A decisão marca o vencedor como `ACEITA`, os demais como `REJEITADA`, atribui com
origem `ADMIN` e cria o compromisso se for parcelado.

A mesma operação serve para o lançamento que **ninguém** reivindicou ("isso foi
do João"): sem ela, a única saída para o lançamento parado no pool era conciliar
e deixá-lo cair no titular. Nos dois casos a pessoa **recebe e-mail** e pode
devolver ao pool com "não foi minha" enquanto a fatura estiver em avaliação —
atribuição mandatória sem aviso é cobrança silenciosa, e a regra §3.3 ("quem
chegou primeiro nunca decide uma cobrança contestada") vale igual para "o admin
decidiu".

`ReivindicacaoService.arbitrar` · `NotificacaoService.atribuidoPeloAdmin` ·
testes `adminArbitra`, `adminAtribuiSemDisputa`, `atribuicaoDoAdminEContestavel`

### 3.4.1 O admin atribui o resultado de uma busca inteiro

Filtrar por "UBER" na conciliação e dizer "isso tudo é da Maria" é **uma**
decisão; repeti-la nas quarenta linhas só aumenta a chance de errar uma delas.
O lote aplica exatamente o §3.4 a cada lançamento — origem `ADMIN`, disputa
resolvida, compromisso de parcela criado — e cada um continua contestável
individualmente com "não foi minha".

O que o lote faz diferente do caminho de um lançamento:

- **um e-mail, não N** — quarenta avisos separados sobre a mesma decisão seriam
  ignorados em bloco, e é o aviso que torna a atribuição contestável;
- **um recálculo de acertos, não N** — só o estado final importa, e com o banco
  no Neon cada ida e volta custa latência;
- **encargo é pulado em silêncio** (§3.5), em vez de derrubar o lote inteiro:
  quem filtrou por texto não escolheu o encargo, ele só caiu na busca;
- **o que já era da pessoa fica como está** — refazer não mudaria nada e só
  geraria linha de auditoria;
- **uma fatura por vez** (400 se os ids misturarem competências) e só com a
  fatura em avaliação (409), como qualquer atribuição.

Os ids vão da tela para o backend, não o termo da busca: refazer a consulta no
servidor faria o lote pegar linha que o admin não viu na lista.

`ReivindicacaoService.arbitrarEmLote` ·
`NotificacaoService.atribuidosEmLotePeloAdmin` ·
`POST /api/lancamentos/arbitrar-lote` · testes `loteAtribuiTudoAUmaPessoa`,
`loteContinuaContestavelLinhaALinha`, `lotePulaEncargoSemFalhar`,
`lotePulaOQueJaEraDela`, `loteForaDaAvaliacaoERecusado`,
`loteDeDuasFaturasERecusado`

### 3.5 Encargos não se reivindicam nem se arbitram

`ENCARGO`, `ANUIDADE`, `IOF`, `AJUSTE` e `PAGAMENTO` não são reivindicáveis: são
custo do cartão, não compra de alguém. Os quatro primeiros são rateados (§1.4);
o `PAGAMENTO` fica com o titular. Nem reivindicar, nem dividir à mão, nem
arbitrar funciona neles — os três respondem 409.

`TipoLancamento.reivindicavel` · testes `encargoNaoEReivindicavel`,
`encargoNaoSeDivide`

### 3.6 Quem assumiu pode rachar a conta

Quem está com o lançamento (ou o admin) divide entre duas ou mais pessoas, com
valor livre por pessoa. Faz sentido ser ele: é quem sabe quem estava na mesa.

Enquanto existe divisão, ela é a **verdade** do rateio daquele lançamento, e o
`responsavel` passa a valer só como "quem organizou" — é dele que a regra de
parcelamento continua tirando o dono das parcelas seguintes.

`DivisaoService.dividir` · testes `contaDivididaEntreTres`, `soQuemAssumiuDivide`

### 3.7 Recusar uma parte derruba a divisão inteira

Quem recebeu uma parte que não reconhece usa o mesmo "não foi minha": a divisão
cai e o lançamento **volta inteiro para quem o assumiu**, que refaz a conta com
quem concorda (e recebe e-mail avisando). É o princípio do conflito aplicado à
divisão: ninguém carrega cobrança que não aceitou — e ninguém perde o lançamento
por decisão de outro.

Contestar a atribuição (§3.3) ou o admin arbitrar também apagam a divisão: ela
pertencia à atribuição que está sendo discutida.

`ReivindicacaoService.desistir` · teste `recusarParteDesfazADivisao`

### 3.8 Só dá para mexer com a fatura em avaliação

Fora de `EM_AVALIACAO`, reivindicar, desistir e dividir respondem 409. Quando o
engano só aparece depois disso, a saída é o admin **reabrir a avaliação** (§4.2).

`ReivindicacaoService.buscarReivindicavel` · `DivisaoService.carregarDivisivel`

### 3.9 O estabelecimento pode ganhar apelido

`DL*UberRides` vira `Uber`. O apelido é gravado pela **descrição normalizada** —
a mesma chave que casa a compra parcelada entre faturas —, então vale para os
meses seguintes; a descrição original continua na tela e na API, porque é ela que
casa com o extrato do banco.

Vale para **todo mundo**, não por pessoa: quem apelida está descrevendo a loja,
não registrando uma preferência. Por isso qualquer utilizador apelida (quem
reconhece a loja é quem comprou nela) e toda alteração vai para a auditoria.

`ApelidoService` · `ApelidoEstabelecimento` · testes
`apelidoValeParaOsProximosMeses`, `apelidarDeNovoSubstitui`, `removerApelido`

### 3.10 O pool lembra onde a pessoa já comprou

Cada lançamento sem dono vem com `jaFoiSeu`: quem está olhando já assumiu compra
naquela mesma loja em **outra** fatura. A maior parte das compras se repete, e
isso transforma leitura em conferência. É calculado por utilizador e nunca expõe
o histórico de outra pessoa.

`Lancamento.estabelecimentosDe` · testes `historicoEPessoal`,
`historicoIgnoraAFaturaAtual`

## 4. Ciclo da fatura

```
IMPORTADA ──┬─► DIVERGENTE (soma não fecha; trava aqui)
            └─► EM_AVALIACAO ──► CONCILIADA ──► FECHADA
                      ▲               │
                      └── reabrir ────┘  (só o admin, §4.2)
```

- **EM_AVALIACAO** — utilizadores reivindicam e dividem; os acertos são
  recalculados a cada mudança para que o "quanto devo" fique sempre atualizado.
- **CONCILIADA** — o admin fechou o rateio; a sobra reivindicável foi para o
  titular e os valores param de mudar. É aqui que abrem o aceite e o pagamento.
- **FECHADA** — todos os acertos confirmados. Fatura fechada não é reprocessada
  nem excluída: é o histórico do acerto.

`ConciliacaoService.conciliar` / `.fechar`

### 4.1 Excluir a fatura

Fatura **não fechada** pode ser apagada pelo admin — é a saída para o arquivo
errado ou a competência trocada, já que o `hash_pdf` único impede reimportar por
cima. Lançamentos, reivindicações, divisões, acertos e comprovantes caem por
cascata.

O que **não** cai sozinho é o `CompromissoParcelado`: a FK dele é
`ON DELETE SET NULL`, então ele sobreviveria órfão e seguiria atribuindo as
parcelas seguintes a partir de uma fatura que não existe mais. Por isso é
apagado explicitamente.

`FaturaImportService.excluir` · testes `excluirFaturaLimpaTudo`,
`faturaFechadaNaoEExcluida`, `excluirFatura` (HTTP)

### 4.2 Voltar de CONCILIADA para EM_AVALIACAO

Marcar "foi minha" no lançamento errado é o engano mais fácil do app — a lista é
longa, os nomes são parecidos e o botão é grande. Enquanto a fatura está aberta a
própria pessoa desfaz; depois de conciliada ela não tinha saída nenhuma.

O admin reabre, e então:

- o que ficou com o titular **por falta de dono** volta ao pool (origem
  `SOBRA_CONCILIACAO`, criada só para permitir essa distinção); o que o admin
  **arbitrou** fica onde está — era uma decisão, não um padrão;
- os acertos `ACEITO` voltam a `ABERTO`: aceitar é concordar com um número, e o
  número volta a mudar;
- quem já declarou o pagamento segue `INFORMADO`, com o comprovante — o dinheiro
  saiu. O valor devido dele **é** recalculado, e a diferença é assunto do admin;
- todo mundo com acerto na fatura recebe e-mail, com o motivo informado, e a ação
  vai para a auditoria. Sem isso o valor mudaria em silêncio.

Acerto **`CONFIRMADO` barra a operação** (409). Ele nunca é recalculado (§5) e,
congelado no meio de um rateio que mudou, faria a soma dos acertos deixar de
reproduzir o total — a fatura ficaria impossível de conciliar de novo. O caminho
é reabrir aquele acerto antes, que é uma decisão explícita sobre dinheiro já
quitado. Fatura `FECHADA` também não volta: ela é o histórico do acerto.

`ConciliacaoService.reabrirAvaliacao` · `NotificacaoService.avaliacaoReaberta` ·
testes `reabrirDevolveAsSobrasAoPool`, `reabrirPreservaAArbitragem`,
`reabrirAnulaOAceite`, `reabrirPreservaOPagamentoInformado`,
`reabrirComAcertoConfirmadoERecusado`, `reabrirCorrigirEConciliarDeNovo`

## 5. Quitação

```
ABERTO ──aceite──► ACEITO ──pagamento + comprovante──► INFORMADO ──admin──► CONFIRMADO
```

- **Aceite** — a pessoa confere o somatório e concorda com ele. Só existe com a
  fatura **conciliada**: enquanto ela está em avaliação o total ainda muda a cada
  lançamento assumido e a cada encargo rerrateado, e aceitar um número que vai
  mudar não significa nada. O passo existe para a discussão sobre o valor
  acontecer **antes** de o dinheiro sair.
- Se o valor mudar depois (o admin arbitrou, alguém dividiu uma conta), o aceite
  é **anulado** e a pessoa confere de novo. Quem já declarou o pagamento fica como
  está: o dinheiro saiu, e a diferença é assunto do admin — não motivo para apagar
  o comprovante.
- **Pagamento** — exige o **comprovante** do PIX ou da transferência (imagem ou
  PDF, até 3 MB). Sem ele não existe registro de que o dinheiro saiu e a
  confirmação viraria palavra contra palavra. Reenviar substitui, para quem mandou
  o print errado.
- A chave PIX vem da configuração (`jcard.pix.*`), não do código: é dado pessoal
  do titular e muda de dono sem recompilar.
- **Confirmação** é sempre do admin — o app não tem como saber se o PIX caiu.
- Um acerto `CONFIRMADO` **não é recalculado** por mudanças posteriores — reabrir
  apagaria a confirmação do admin. Divergência aí é caso de arbitragem manual.
- O comprovante só é visível para o dono do acerto e para o admin: é documento
  bancário de outra pessoa.

`AcertoService` · `PixConfig` · `ConciliacaoService.recalcularAcertos` · testes
`cicloDePagamento`, `aceiteExigeFaturaConciliada`, `pagamentoExigeAceite`,
`comprovanteEObrigatorio`, `comprovanteEPrivado`

## 6. Pessoas

- **Papéis são flags**, não um campo único: o titular normalmente é `admin` **e**
  `utilizador`, porque também gasta no próprio cartão.
- Cadastro só pelo admin. Login derivado do nome (`nome.sobrenome`, com sufixo
  numérico em colisão), senha padrão e **troca obrigatória no 1º acesso**.
- Quem já tem lançamento ou acerto é **desativado**, nunca excluído — apagar
  deixaria faturas passadas sem dono e quebraria a conciliação.
- Tem de sobrar pelo menos um admin ativo; sem ele o app fica travado.
- Login bloqueia por 15 min após 5 tentativas erradas.

`UsuarioService` · `AuthService`

## 7. E-mails

Todos respeitam `jcard.notificacoes.enabled` e o opt-in do usuário, são enviados
de forma **assíncrona** (EventBus) e **nunca lançam** — falha de e-mail não desfaz
a operação que o disparou.

| Quando | Para quem |
|---|---|
| Fatura importada (e não divergente) | todos os utilizadores ativos |
| Lançamentos ainda sem dono (diário, 12h BRT) | utilizadores ativos |
| Conflito para arbitrar | admins |
| Lançamento atribuído pelo admin | quem recebeu o lançamento |
| Fatura de volta em avaliação | todos com acerto na fatura |
| Parte de conta dividida recusada | quem organizou a divisão |
| Pagamento declarado (com comprovante) | admins |
| Pagamento confirmado | o utilizador |
| Cadastro / reset de senha | o utilizador |

`NotificacaoService` · `EmailDispatcher` · `LembreteService`

## 8. Privacidade e auditoria

- O utilizador vê **o pool e as próprias contas**. O que outra pessoa assumiu não
  é exposto, e no pool o dono anterior é omitido para não influenciar a decisão.
  A exceção é a **conta dividida**: os participantes se veem, porque estavam na
  mesma mesa e precisam conferir a própria parte.
- O seletor de divisão usa `/api/usuarios/pessoas`, que devolve só id e nome — a
  lista completa (login, e-mail, papéis) continua sendo do admin.
- O PDF da fatura **não é armazenado** — só o hash e o texto extraído. O
  **comprovante de pagamento**, ao contrário, é guardado: ele *é* a prova do
  acerto. Fica em tabela própria, servido com `Cache-Control: private, no-store`.
- Toda ação relevante vira registro em `auditoria`, com o nome desnormalizado
  para sobreviver à exclusão do usuário.

`FaturaResource.minhasContas` · `Responses.LancamentoResponse.anonimo` ·
`AuditoriaService`
