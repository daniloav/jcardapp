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
   o titular. Guardião: `ConciliacaoService`.
2. **Parcelamento gruda.** Assumir a parcela 1/10 cria um `CompromissoParcelado`
   que faz as nove seguintes já nascerem no nome da pessoa nas próximas faturas.

Toda fatura importada dispara e-mail a **todos** os utilizadores ativos pedindo
avaliação de responsabilidade.

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
│       ├── model/              ← entidades (EntidadeBase, Fatura, Lancamento, ...)
│       ├── parser/             ← ItauCsvParser (preferido) · ItauFaturaParser (PDF) · ChaveParcelamento
│       ├── service/            ← Conciliacao, Atribuicao, Reivindicacao, Acerto, Notificacao
│       ├── resource/           ← endpoints REST + ErrorMapper
│       ├── dto/ · security/ · bootstrap/
│       └── resources/db/migration/V1__schema.sql
├── frontend/                   ← Angular 17
│   ├── Dockerfile.runtime · nginx.conf
│   └── src/{sw.js, manifest.webmanifest, app/{core,layout,pages}}
├── .github/workflows/          ← ci.yml (build+testes+segurança) · cd.yml (GHCR → pull na VM)
├── scripts/                    ← gcp-provisionar · gcp-bootstrap · duckdns-update · gen-jwt-keys
│                                  verificar-custo-zero · oci-* (plano B, ver §8)
└── docs/                       ← ARQUITETURA · REGRAS-DE-NEGOCIO · CICD · parser-itau · topologia
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

- **Backend**: `cd backend && mvn -B verify` — 53 testes, incluindo as duas
  invariantes de conciliação, a herança de parcela, o fluxo da API por HTTP e os
  dois leitores de fatura contra fixtures anonimizados
  (`fatura-itau-exemplo.csv` e `.txt`).
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
  extraído (permite reprocessar sem pedir o arquivo de novo).
- **CSV é o caminho principal; PDF é o plano B.** Numa fatura real o CSV leu
  514 de 514 lançamentos sem sobra, enquanto o PDF fechava 2 de 5 cartões: ele
  tem duas colunas, descrições cortadas na largura e cinco tipos de bloco
  misturados. O leitor é escolhido pela assinatura `%PDF`, não pela extensão.
- **O CSV não traz o total nem o cartão.** O total é informado por quem importa
  (a conciliação confere contra a soma, então a invariante continua valendo) e
  os lançamentos nascem no pool, que é o fluxo normal.
- **Regexes do parser na configuração** (`jcard.parser.itau.*`): layout de banco
  muda, e calibrar não pode exigir recompilar.
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

- ✅ Backend completo, 26 testes verdes (`mvn verify`).
- ✅ Frontend Angular 17 + PWA compilando (84 kB no bundle inicial).
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
