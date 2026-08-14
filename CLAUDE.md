# CLAUDE.md — contexto do projeto

> Lido no início de cada sessão. Resume o que é o app, como rodar, como está
> organizado e o que já foi decidido. Mantenha atualizado ao evoluir o código.

## 1. O que é

**JcardApp** — o pai do Danilo empresta o cartão de crédito dele para outras
pessoas, e no fechamento da fatura fica impossível separar quem gastou o quê.
O app resolve isso: o admin sobe a **fatura do Itaú em CSV** (ou o PDF), uma
rotina quebra a fatura lançamento a lançamento, e cada **utilizador** assume o
que reconhece como seu para depois pagar.

Dono/desenvolvedor: **Danilo** (`danilo.av@gmail.com`). Idioma do domínio, do
código e da UI: **português**.

### As duas regras que sustentam tudo

1. **As contas sempre batem.** A soma dos lançamentos tem de reproduzir o total
   impresso na fatura; se não bate, a fatura vai para `DIVERGENTE` e **nada
   avança** — ratear em cima de leitura errada cobraria valor errado de alguém.
   E a soma dos acertos tem de reproduzir o total: o que ninguém assume fica com
   o titular, e nem a conta dividida nem o encargo rateado podem perder um
   centavo no arredondamento. Guardiões: `ConciliacaoService` e `RateioService`.
2. **Parcelamento gruda.** Assumir a parcela 1/10 cria um `CompromissoParcelado`
   que faz as nove seguintes já nascerem no nome da pessoa nas próximas faturas.

Três coisas decorrem disso e valem repetir:

- **Uma conta pode ser de várias pessoas.** Quem assumiu racha entre duas ou mais,
  com valor livre; a soma das partes tem de fechar com o lançamento.
- **Encargo é de todo mundo que usou o cartão no mês** (IOF, anuidade, juros,
  ajuste), dividido entre os participantes, com a sobra de centavos no titular.
  Por isso ele fica **sem responsável** mesmo depois da conciliação.
- **Pagar exige aceitar antes.** `ABERTO → ACEITO → INFORMADO → CONFIRMADO`, com
  comprovante obrigatório do PIX. O aceite só abre com a fatura conciliada, que é
  quando o valor para de mudar. A quitação pode vir em **várias transferências**:
  cada uma com valor e comprovante, e o acerto só fecha com saldo zero. Quem
  pagou e não mandou o print recebe a **baixa manual** do admin — sem
  comprovante, no nome dele e com aviso à pessoa.

Toda fatura importada dispara e-mail a **todos** os utilizadores ativos pedindo
avaliação de responsabilidade.

### A prévia: o mês feito aos poucos

A fatura só existe no dia em que fecha, e aí chegam 514 linhas de uma vez. O
Itaú deixa baixar a **fatura em aberto** a qualquer momento, e é isso que a
**prévia** carrega: o CSV parcial do mês em curso, subido quantas vezes o admin
quiser. Cada subida sobrescreve a anterior, e **o que alguém já assumiu continua
dele** — na prévia seguinte e, no fim, na fatura de verdade, que consome a
prévia ao ser importada. O trabalho deixa de ser um mutirão no vencimento.

A prévia usa o mesmo pool, a mesma reivindicação, o mesmo conflito, a mesma
divisão e o mesmo apelido. O que ela **não** faz: acerto, conciliação,
fechamento, divergência e e-mail — ninguém deve nada por uma parcial.

### Premissa inegociável: custo US$ 0

**Tudo tem de ser gratuito.** Nenhuma decisão de infra pode gerar cobrança, nem
"uns centavos". Isso restringe escolhas de shape, storage, registry e CI, e
qualquer mudança nessas áreas precisa ser conferida antes de subir:

```bash
bash scripts/verificar-custo-zero.sh
```

Os tetos e o que foi feito para caber neles estão na §8.

> O risco é silencioso: nenhum provedor bloqueia quando você passa do gratuito —
> eles deixam criar e cobram. No GCP a armadilha mais fácil é a **região**: a
> mesma `e2-micro` é grátis em `us-central1` e cobrada em qualquer outra.

## 2. Stack

| Camada | Tecnologia |
|---|---|
| Backend | Java **17** · **Quarkus 3.15** · Hibernate Panache · **Flyway** · JWT RS256 · **PDFBox** |
| Frontend | **Angular 17** standalone + signals · SCSS próprio · PWA com service worker escrito à mão |
| Banco | **PostgreSQL 16** |
| Infra | Docker Compose · **1 VM GCP e2-micro (amd64)** · Caddy (HTTPS) + nginx · imagens no GHCR |
| Cloud | Google Cloud `us-central1` · **Postgres no Neon** · DuckDNS · SMTP do Gmail |

Ambiente do Danilo: **Node 18.13** (por isso Angular 17, não 18+), Maven 3.9,
Java 19 rodando target 17. Shell **zsh** (os scripts usam shebang bash).
**Docker local não funciona neste Mac** — para dev use o Postgres nativo
(`brew services start postgresql@16`), não o `docker-compose.dev.yml`.

## 3. Mapa do repositório

```
jcardapp/
├── CLAUDE.md · README.md · .env.example
├── docker-compose.yml          ← PROD: caddy + frontend + backend (banco é o Neon)
├── docker-compose.dev.yml      ← só Postgres (dev)
├── Caddyfile                   ← HTTPS Let's Encrypt em jcardapp.duckdns.org
├── backend/                    ← Quarkus, pacote br.com.jcard
│   ├── Dockerfile.runtime      ← SÓ empacota (o build é no runner) — ver §6
│   └── src/main/java/br/com/jcard/
│       ├── model/              ← entidades (EntidadeBase, Fatura, Lancamento, DivisaoLancamento, ...)
│       ├── parser/             ← ItauCsvParser (preferido) · ItauFaturaParser (PDF) · ChaveParcelamento
│       ├── service/            ← Conciliacao, Rateio, Divisao, Atribuicao, Reivindicacao, Acerto,
│       │                          Previa, Apelido, Notificacao
│       ├── resource/           ← endpoints REST + ErrorMapper
│       ├── dto/ · security/ · bootstrap/
│       └── resources/db/migration/  ← V1__schema · V2__divisao_encargos_comprovante
│                                       V3__reabertura_e_apelidos
│                                       V4__pagamento_parcial
│                                       V5__chave_pix_no_banco
│                                       V6__previa_de_fatura
│                                       V7__baixa_manual
├── frontend/                   ← Angular 17
│   ├── Dockerfile.runtime · nginx.conf
│   └── src/
│       ├── {sw.js, manifest.webmanifest, app/{core,layout,pages}}
│       └── assets/tutoriais/  ← guias em HTML puro, fora do Angular (ver §6)
│                                 index · o-mes-inteiro · primeiro-acesso
│                                 assumir-conta · dividir-conta · aceitar-e-pagar
│                                 player.js + player.css (engine compartilhada)
├── .github/workflows/          ← ci.yml (build+testes+segurança) · cd.yml (GHCR → pull na VM)
├── scripts/                    ← gcp-provisionar · gcp-bootstrap · duckdns-update · gen-jwt-keys
│                                  verificar-custo-zero · oci-* (plano B, ver §8)
└── docs/                       ← ARQUITETURA · REGRAS-DE-NEGOCIO · ROADMAP · CICD
                                   parser-itau · topologia
```

## 4. Como rodar (dev)

```bash
# 1) Banco (Postgres nativo — Docker não funciona neste Mac)
brew services start postgresql@16
psql -d postgres -c "CREATE ROLE jcard LOGIN PASSWORD 'jcard';"
psql -d postgres -c "CREATE DATABASE jcard OWNER jcard;"
psql -d postgres -c "CREATE DATABASE jcard_test OWNER jcard;"   # os testes limpam este

# 2) Chaves JWT (só na 1ª vez) + backend em :8080
./scripts/gen-jwt-keys.sh
cd backend && mvn quarkus:dev        # Swagger: http://localhost:8080/q/swagger-ui

# 3) Frontend em :4200 (proxy de /api para o :8080)
cd frontend && npm install && npm start
```

Primeiro acesso: **`admin` / `admin123`**, criado no 1º boot pelo `DataInitializer`,
com troca de senha obrigatória.

## 5. Como validar mudanças

- **Backend**: `cd backend && mvn -B verify` — 142 testes, incluindo as duas
  invariantes de conciliação, a atribuição em massa, a divisão de contas, o
  rateio de encargos, a prévia que sobrescreve sem perder o que foi assumido e a
  fatura que herda dela, o ciclo
  de pagamento com comprovante, a baixa manual do admin e o desfazer dela,
  a herança de parcela, a reabertura da avaliação,
  o fluxo da API por HTTP, as contagens agregadas da listagem e os dois leitores
  de fatura contra fixtures anonimizados (`fatura-itau-exemplo.csv` e `.txt`).
- **Frontend**: `cd frontend && npx ng build`.
- O perfil `%test` usa o banco **`jcard_test`** com `flyway.clean-at-start` — nunca
  aponte para o banco de dev, ele é apagado a cada execução.

## 6. Decisões que não são óbvias no código

- **O build não está no Dockerfile.** A e2-micro tem 1 GB e uma vCPU
  compartilhada: um build de Maven/Node ali levaria muito tempo e provavelmente
  estouraria a memória. O `cd.yml` compila no runner e os `Dockerfile.runtime`
  só fazem `COPY` do artefato pronto.
- **Pool com `min-size=0`.** O Neon hiberna após ~5 min SEM CONEXÃO. Um pool
  segurando conexão ociosa manteria o banco acordado 24/7 (~180 CU-hours) e
  estouraria as 100 CU-hours do plano gratuito.
- **`EntidadeBase` com `IDENTITY` e `getId()`.** O schema é `BIGSERIAL`; o
  `PanacheEntity` padrão usa `AUTO`, que no Hibernate 6 vira sequence
  `<entidade>_seq` e quebra. E ler `entidade.associacao.id` num proxy lazy devolve
  **null silenciosamente** — por isso `getId()`. Use sempre o getter em associações.
- **Serviços recebem `Long id`, não entidade.** Evita `detached entity passed to
  persist` quando o chamador vem de outra transação.
- **A chave de parcelamento ignora o valor.** R$ 100 em 3x sai 33,34 + 33,33 +
  33,33; travar no centavo quebraria o casamento entre faturas. A proteção contra
  colisão é a checagem de valor aproximado (±R$ 1,00 ou 5%) no `AtribuicaoService`,
  que **na dúvida deixa no pool** em vez de cobrar a pessoa errada.
- **Conflito devolve ao pool.** Um pretendente leva na hora; o segundo tira o
  lançamento de quem estava com ele e chama o admin. "Quem chegou primeiro" nunca
  decide uma cobrança contestada.
- **O PDF não é persistido.** Só o SHA-256 (idempotência da importação) e o texto
  extraído (permite reprocessar sem pedir o arquivo de novo). O **comprovante do
  PIX**, ao contrário, é guardado — ele *é* a prova do acerto. Fica em tabela
  própria (`comprovante_pagamento`) e não numa coluna de `acerto`, senão o
  `byte[]` viajaria junto em toda listagem de acertos do admin.
- **Chave PIX que falta é uma flag, não um texto.** O default de
  `jcard.pix.chave` era o próprio recado ("defina JCARD_PIX_CHAVE no .env"), e a
  tela o exibia como chave, com botão de copiar — copiar aquilo manda a pessoa
  pagar para lugar nenhum. Hoje a resposta traz `configurada`, e sem chave a tela
  avisa em vez de oferecer cópia. O tipo é `Optional<String>`: para o SmallRye
  valor vazio é *ausente*, e ausente numa `String` não é falta de valor, é erro
  de configuração — o app nem sobe, justamente no caso normal de ninguém ter
  configurado ainda (é o que `PixSemChaveTest` defende).
- **A chave PIX tem duas origens, e o banco vence o ambiente.** O admin define a
  chave em `/admin/pix` (tabela `configuracao_pix`, V5) e `JCARD_PIX_CHAVE` fica
  como valor inicial, para a instalação nova ter algo antes da primeira tela. A
  ordem não é arbitrária: se o ambiente vencesse, o admin trocaria a chave, veria
  a antiga continuar aparecendo para todo mundo e o dinheiro iria para a conta
  errada. Só ambiente obrigava a entrar por ssh na VM para trocar a chave — o
  dono do cartão não abre terminal. O CPF continua fora do git nos dois casos.
  Linha única garantida pelo banco, e toda troca vai para a auditoria com a chave
  velha e a nova: é a operação que mais mexe em dinheiro dos outros.
- **O acerto é quitado por N transferências, e o saldo é derivado.** O app
  registrava quanto a pessoa deve e nunca quanto ela pagou: um comprovante por
  acerto, e reenviar substituía. Aí a pessoa paga R$ 100, o total dela sobe no
  fechamento e ela manda a diferença — o segundo print apagava a prova do
  primeiro. Agora cada transferência tem valor, data e comprovante próprios; o
  saldo (`devido - Σ pagos`) é calculado a cada leitura, nunca gravado; o admin
  confirma uma de cada vez; e o acerto só fecha com tudo confirmado E saldo
  zero. Reabrir mantém os pagamentos: o dinheiro saiu.
- **A baixa manual guarda quem a registrou, e é a única apagável.** O
  comprovante continua obrigatório para o utilizador declarar o pagamento, mas
  parte das pessoas paga o PIX e nunca abre o app: o acerto delas ficaria
  `ABERTO` para sempre e a fatura não fecharia com o dinheiro já na conta — e o
  admin acabaria confirmando de cabeça. Ele registra a transferência em nome
  dela, já confirmada (quem registra é quem está olhando o extrato), e o
  pagamento carrega `registrado_por`: sem comprovante, essa é a única prova, e as
  duas origens não podem ficar indistinguíveis na tela. A pessoa recebe e-mail,
  porque lançamento sem prova que ela não fica sabendo não é contestável. E é a
  única que dá para apagar — no pagamento declarado, apagar é apagar a prova de
  que o dinheiro saiu; nele o caminho é reabrir o acerto.
- **Divisão manda no rateio.** Havendo linhas em `divisao_lancamento`, elas são a
  verdade daquele lançamento e o `responsavel` passa a valer só como "quem
  organizou" — continua preenchido porque é dele que o compromisso parcelado tira
  o dono das parcelas seguintes.
- **Encargo rateado não é gravado.** O rateio é calculado no `RateioService` a
  cada leitura, em vez de virar linhas no banco: dado derivado persistido
  divergiria do rateio real assim que alguém assumisse mais um lançamento. Por
  isso a tela do utilizador e a conciliação chamam o **mesmo** método — o número
  que a pessoa confere é, ao centavo, o que ela vai pagar.
- **A sobra da conciliação tem origem própria.** O que ninguém assume e cai no
  titular é marcado `SOBRA_CONCILIACAO`, não `ADMIN`. Sem essa distinção,
  reabrir a avaliação não teria como devolver ao pool só o que ficou com o
  titular por falta de dono, sem desfazer junto o que o admin arbitrou.
- **Reabrir a avaliação para com acerto confirmado.** Acerto `CONFIRMADO` nunca
  é recalculado; congelado no meio de um rateio que mudou, ele faria a soma dos
  acertos deixar de reproduzir o total e a fatura ficaria impossível de
  conciliar de novo. O admin reabre aquele acerto antes — decisão explícita
  sobre dinheiro já pago, em vez de um 500 na próxima conciliação.
- **O apelido do estabelecimento é global e chaveado pela descrição
  normalizada.** Vale para os meses seguintes de graça (é a mesma chave que casa
  o parcelamento) e para todo mundo, porque nomear a loja é descrever a loja, não
  registrar preferência. A descrição do banco continua na tela: é ela que casa
  com o extrato.
- **A exclusão de fatura apaga o compromisso parcelado à mão.** O resto cai por
  cascata, mas a FK do compromisso é `ON DELETE SET NULL`: ele sobreviveria órfão
  e seguiria atribuindo parcelas a partir de uma fatura que não existe mais.
- **A prévia é um status da fatura, não uma tabela nova.** Uma linha da prévia é
  um lançamento igual a qualquer outro e é assumida pelo mesmo caminho; um
  modelo paralelo para o mês em aberto duplicaria pool, reivindicação, conflito,
  divisão e apelido — que são o app. `StatusFatura.PREVIA` fica fora do ciclo
  das outras e nunca vira nenhum deles. Índice único parcial garante **uma
  prévia por competência**: duas seriam duas telas do mesmo mês, com gente
  assumindo conta em cada uma.
- **Sobrescrever a prévia não pode custar o trabalho de ninguém.** É a promessa
  que sustenta o recurso: se a segunda subida desfizesse o que a primeira
  rendeu, a família aprendia em uma semana a não mexer na prévia. As atribuições
  são recapturadas antes de apagar e reaplicadas na leitura nova
  (`PreviaService.consumir`), e a fatura de verdade herda pelo **mesmo** caminho
  — é para isso que a prévia existe. Só o que foi decidido por gente (`MANUAL` e
  `ADMIN`) viaja: as origens automáticas são recalculadas da própria fonte, e
  uma cópia velha faria uma regra desligada no meio do mês continuar valendo.
- **A chave que casa os dois arquivos é estrita: data + loja + valor + parcela.**
  Compra que aparece com outro valor no arquivo novo (a gorjeta entrou, a
  pré-autorização virou débito) **não** é a mesma compra e volta ao pool. É a
  mesma regra do `AtribuicaoService`: na dúvida, deixa no pool — reassumir custa
  um toque, herdar errado cobra de alguém um valor que ela não conferiu. A tela
  diz quantas voltaram, porque isso o admin não vê acontecer. A fila por chave
  (e não um valor por chave) existe porque três corridas de R$ 7,35 no mesmo dia
  podem ser de três pessoas.
- **A prévia lê o compromisso de parcelamento, mas nunca escreve nele.** Ela é
  reescrita a cada subida: um compromisso nascido ali apontaria para um
  lançamento apagado, e registrar a parcela encerraria o compromisso quando a
  última aparecesse numa parcial — deixando a fatura de verdade, que vem depois,
  sem de onde herdar. O compromisso nasce na importação da fatura, a partir da
  atribuição herdada.
- **A prévia não manda e-mail.** Ela pode subir todo dia; um aviso por subida
  viraria ruído e treinaria a família a ignorar justamente o e-mail da fatura de
  verdade. Quem abre o app vê a prévia no primeiro cartão da tela inicial.
- **A prévia fica fora do gráfico e dos totais da tela inicial.** É um número
  que ainda vai crescer: somá-lo a "em andamento" anunciaria uma dívida que não
  existe. Ela tem cartão próprio, no topo, dizendo o que é.
- **CSV é o caminho principal; PDF é o plano B.** Numa fatura real o CSV leu
  514 de 514 lançamentos sem sobra, enquanto o PDF fechava 2 de 5 cartões: ele
  tem duas colunas, descrições cortadas na largura e cinco tipos de bloco
  misturados. O leitor é escolhido pela assinatura `%PDF`, não pela extensão.
- **O CSV não traz o total nem o cartão.** O total é informado por quem importa
  (a conciliação confere contra a soma, então a invariante continua valendo) e
  os lançamentos nascem no pool, que é o fluxo normal.
- **Regexes do parser na configuração** (`jcard.parser.itau.*`): layout de banco
  muda, e calibrar não pode exigir recompilar.
- **A listagem de faturas não carrega faturas.** `GET /api/faturas` alimenta a
  lista e o gráfico da tela inicial, e responde em **três consultas fixas**: o
  cabeçalho de todos os meses via projeção (`ResumoFatura`, que deixa o
  `texto_extraido` no banco — eram 525 kB de texto por requisição num ano de
  faturas) mais duas agregações (`Lancamento.contagensPorFatura` e
  `Reivindicacao.conflitosPorFatura`). Antes eram três consultas **por fatura**, e
  uma delas carregava o pool inteiro só para chamar `size()`. Com o banco no Neon
  cada ida e volta custa latência de rede: era isso que fazia a primeira tela
  demorar. As contagens agregadas têm de responder o mesmo que o caminho por
  fatura — é o que `ListagemDeFaturasTest` defende.
- **A atribuição em massa manda um e-mail e recalcula uma vez.** Mandar o
  resultado de uma busca inteiro para uma pessoa é uma decisão só: quarenta
  avisos separados seriam ignorados em bloco — e é o aviso que torna a
  atribuição contestável —, e recalcular os acertos linha a linha multiplicaria
  a latência do Neon sem mudar o resultado. Encargo que cair na busca é pulado
  em silêncio em vez de derrubar o lote, e os ids vão da tela para o backend:
  refazer o filtro no servidor faria o lote pegar linha que o admin não viu.
- **O divisor do encargo muda na conciliação — de propósito.** Quem não assumiu
  nada não usou o cartão, então antes de conciliar o encargo é dividido só entre
  quem assumiu. A conciliação dá as sobras ao titular, ele passa a ter lançamento
  e passa a dividir o encargo: um IOF 50/50 vira 1/3 para cada um dos três. O
  total que a pessoa vê antes pode cair depois, e é isso que o aviso da tela dela
  ("enquanto houver lançamento sem dono") anuncia. Contar o titular como
  participante desde já foi considerado e recusado: ele ainda não usou o cartão.
- **A conferência da conta sai do mesmo rateio, não de um segundo cálculo.** A
  tela do admin que abre a conta de uma pessoa linha a linha (`GET
  /api/faturas/{id}/utilizadores/{usuarioId}/detalhe`) chama o `RateioService`
  como a tela da pessoa: um segundo cálculo só provaria que os dois concordam
  entre si. Ela responde para **qualquer** utilizador, inclusive quem não tem
  acerto — é aí que mora a pergunta "esse encargo foi rateado com ela?" —, e
  compara o acerto gravado com o rateio de agora, que é o que denuncia acerto
  congelado.
- **Os treinamentos são HTML puro, fora do Angular.** Quem chega pelo link do
  login ainda não entrou no app, então o guia não pode depender de rota
  autenticada — e tutorial não pode engordar o bundle de quem só quer pagar a
  conta. São páginas estáticas em `assets/tutoriais/`, com uma engine
  compartilhada (`player.js` toca as cenas, `player.css` desenha o mockup do
  app). Cada guia declara `CAPTIONS` e `DURS` e marca as `<section class="scene">`.
  O mockup é retratado numa tela **clara nos dois temas**: por isso a tinta sai
  de `--ap-ink`, e não de `--ink`, que no tema escuro sumiria no branco do cartão.
- **`o-mes-inteiro.html` é o panorama; os outros quatro são o close.** As regras
  existiam só espalhadas por etapa, e ninguém remontava a sequência sozinho —
  ele mostra as seis etapas do mês e **quem faz cada uma**, admin e utilizador
  lado a lado. É o primeiro card da central justamente porque dá sentido aos
  outros.
- **O service worker não cacheia `/api`.** Dado financeiro não pode sobrar no
  disco do navegador nem ser servido desatualizado.
- **Privacidade**: o utilizador vê o pool e as próprias contas — nunca o que
  outra pessoa assumiu.

## 7. Convenções

- Camadas `resource → service → repository`; DTOs são `record`; entidade nunca vai
  direto para a API. Erro de negócio via `WebApplicationException` → `ErrorMapper`
  devolve `{message, status}`.
- Frontend: componentes **standalone**, estado em **signals**, um único
  `ApiService`, retorno via `ToastService`.
- Banco: o schema é do **Flyway**. Toda mudança de modelo exige nova migration
  `V2__...` — nunca editar a `V1` já aplicada.

## 8. Infraestrutura

- **Uma VM `e2-micro` no GCP** (`us-central1`), sempre gratuita, com caddy +
  frontend + backend. **O Postgres está fora**, no Neon — é isso que faz 1 GB
  bastar.
- **Neon**: plano gratuito permanente, 0,5 GB de storage e 100 CU-hours/mês.
  Hiberna sozinho e faz backup (point-in-time), então não há cron de `pg_dump`.
- ⚠️ **O que quebra o custo zero, em ordem de facilidade de errar**:
  1. subir a VM fora de `us-west1`/`us-central1`/`us-east1` — mesma máquina,
     mas **cobrada**;
  2. reservar um **IP estático** (cobrado quando ocioso; usamos o efêmero);
  3. um pool segurando conexão e impedindo o Neon de hibernar;
  4. disco acima de 30 GB.
  `bash scripts/verificar-custo-zero.sh` barra os quatro.
- **Plano B (Oracle)**: os scripts `scripts/oci-*` continuam válidos. A Oracle
  tem oferta melhor (4 OCPU / 24 GB de Ampere A1), mas respondeu
  `Out of host capacity` em **todas** as tentativas ao longo de dois dias em
  `sa-saopaulo-1`, inclusive no menor shape. Always Free só existe na *home
  region*, então mudar de região sairia do gratuito.
- **Deploy**: merge na `main` → CI → CD publica no GHCR e a VM só faz `pull` +
  `up`. Rollback: `JCARD_IMAGE_TAG=<sha>` no `.env`.

## 9. Estado do projeto

- ✅ Backend completo, 142 testes verdes (`mvn verify`).
- ✅ Frontend Angular 17 + PWA compilando (87 kB no bundle inicial).
- ✅ **Tela inicial (`/inicio`)** — é onde o login cai: gráfico das faturas por mês
   (fechada, em andamento, divergente), os dois totais e "precisa de você", que
   muda de conteúdo para admin e para utilizador. Conferida no app rodando, no
   desktop e no tamanho de celular.
- ✅ **Prévia da fatura** — subir o CSV do mês em aberto quantas vezes quiser,
   com o que já foi assumido sobrevivendo à sobrescrita e passando para a fatura
   de verdade quando ela é importada. Marcada como prévia em toda tela onde
   aparece (início, lista, contas da pessoa e conciliação). Conferida no app
   rodando, ponta a ponta: prévia de 7 linhas → 3 atribuições → prévia de 11
   linhas com as 3 mantidas → fatura fechada nascendo com elas, o compromisso de
   parcelamento criado só nessa hora e os acertos somando o total ao centavo.
   Conferida também no tamanho de celular.
- ✅ **Pagamento parcial e complementar** — o acerto aceita várias
   transferências, cada uma com valor e comprovante; a tela da pessoa mostra
   "já pago / ainda falta" e o admin dá o "recebi" uma a uma. Conferido no app
   rodando (R$ 100 + R$ 293,24 fechando R$ 393,24) e no tamanho de celular.
- ✅ **Baixa manual do admin** — registrar o pagamento de quem pagou e não mandou
   o comprovante, cheio ou parcial, com o nome de quem registrou no lugar da
   prova, e-mail para a pessoa e desfazer para o valor digitado errado. Conferida
   no app rodando: baixa cheia quitando R$ 53,33, a mesma linha aparecendo como
   "registrado por Titular do cartao" na tela do utilizador, baixa parcial de
   R$ 20 deixando "faltam R$ 33,33", e as recusas (fatura em avaliação, apagar
   pagamento com comprovante, utilizador tentando dar baixa). Conferida também no
   tamanho de celular.
- ✅ Conta dividida, encargo rateado, aceite com comprovante de PIX e exclusão de
   fatura — implementados e conferidos no app rodando.
- ✅ **Fluxo de indicação (ROADMAP §2)** — reabrir a avaliação, admin atribuir
   direto, busca/agrupamento/desfazer no pool e apelido de estabelecimento;
   conferidos no app rodando, inclusive no tamanho de celular.
- ✅ **Conferência da conta de cada pessoa (conciliação)** — abre compras e
   encargos linha a linha, com o divisor do encargo, se a pessoa participa do
   rateio e se o acerto gravado bate com o rateio de agora. Vale para quem não
   tem acerto. Conferida no app rodando e no tamanho de celular.
- ✅ **Atribuição em massa na conciliação** — filtrar a lista e mandar o
   resultado inteiro para uma pessoa, com confirmação que diz quantos trocam de
   dono. Conferida no app rodando (11 lançamentos de uma busca real: 8
   atribuídos, 3 já eram da pessoa) e no tamanho de celular.
- ✅ **Chave PIX editável pelo admin** (`/admin/pix`) — antes só existia como
   variável de ambiente, o que fazia trocar a chave exigir ssh na VM. O `.env`
   continua como valor inicial. Conferida no app rodando (salvar pela tela e a
   chave nova aparecendo na tela de pagamento da pessoa) e no tamanho de celular.
- ✅ **Treinamento do fluxo completo (`o-mes-inteiro.html`)** — 12 cenas cobrindo
   importar → assumir → conciliar → aceitar → pagar → confirmar, com selo de quem
   faz cada etapa. Entrou como primeiro card da central de treinamentos, que é
   onde cai o link "Primeira vez? Veja como usar o app" do login. Conferido cena a
   cena no app rodando, no desktop e no tamanho de celular. No caminho corrigiu
   três defeitos do `player.css` que afetavam os **quatro guias antigos**: texto do
   mockup invisível no tema escuro, `.destaque` quebrando linha no meio da frase e
   coluna do mockup estourando a largura no celular.
- ✅ CI/CD escritos; o CD roda em **modo mock** enquanto os secrets `OCI_*`
  estiverem vazios — a esteira fica verde antes de as VMs existirem.
- ⏳ **VM ainda não provisionada.** Migramos de Oracle para GCP + Neon depois de
  2 dias sem capacidade Ampere. Falta criar as contas (GCP e Neon) e rodar
  `scripts/gcp-provisionar.sh`.
- ✅ **Leitura da fatura resolvida via CSV** — 514 de 514 lançamentos de uma
  fatura real, zero ignorados. O PDF fica como plano B e não fecha (2 de 5
  cartões); o diagnóstico completo está em `docs/parser-itau.md`.
- ⏳ Pendências do Danilo: contas GCP e Neon, token do DuckDNS, senha de app do
  Gmail, PAT com `read:packages`.
- 📋 O que ficou para depois — e por quê — está em `docs/ROADMAP.md`. Em
  destaque: definir a chave PIX (pela tela `/admin/pix` ou no `.env` da VM) e
  conferir os acertos abertos quando a V2 subir.
