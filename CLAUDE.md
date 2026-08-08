# CLAUDE.md — contexto do projeto

> Lido no início de cada sessão. Resume o que é o app, como rodar, como está
> organizado e o que já foi decidido. Mantenha atualizado ao evoluir o código.

## 1. O que é

**JcardApp** — o pai do Danilo empresta o cartão de crédito dele para outras
pessoas, e no fechamento da fatura fica impossível separar quem gastou o quê.
O app resolve isso: o admin sobe o **PDF da fatura do Itaú**, uma rotina quebra
a fatura lançamento a lançamento, e cada **utilizador** assume o que reconhece
como seu para depois pagar.

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

## 2. Stack

| Camada | Tecnologia |
|---|---|
| Backend | Java **17** · **Quarkus 3.15** · Hibernate Panache · **Flyway** · JWT RS256 · **PDFBox** |
| Frontend | **Angular 17** standalone + signals · SCSS próprio · PWA com service worker escrito à mão |
| Banco | **PostgreSQL 16** |
| Infra | Docker Compose · **2 VMs OCI Ampere A1 (ARM64)** · Caddy (HTTPS) + nginx · imagens no GHCR |
| Cloud | Oracle Cloud, região `sa-saopaulo-1` · DuckDNS · SMTP do Gmail |

Ambiente do Danilo: **Node 18.13** (por isso Angular 17, não 18+), Maven 3.9,
Java 19 rodando target 17. Shell **zsh** (os scripts usam shebang bash).
**Docker local não funciona neste Mac** — para dev use o Postgres nativo
(`brew services start postgresql@16`), não o `docker-compose.dev.yml`.

## 3. Mapa do repositório

```
jcardapp/
├── CLAUDE.md · README.md · .env.example
├── docker-compose.app.yml      ← PROD VM app: caddy + frontend + backend (imagens do GHCR)
├── docker-compose.db.yml       ← PROD VM banco: só Postgres, bind no IP privado
├── docker-compose.dev.yml      ← só Postgres (dev)
├── Caddyfile                   ← HTTPS Let's Encrypt em jcardapp.duckdns.org
├── backend/                    ← Quarkus, pacote br.com.jcard
│   ├── Dockerfile.runtime      ← SÓ empacota (o build é no runner) — ver §6
│   └── src/main/java/br/com/jcard/
│       ├── model/              ← entidades (EntidadeBase, Fatura, Lancamento, ...)
│       ├── parser/             ← ItauFaturaParser, TextoFatura, ChaveParcelamento
│       ├── service/            ← Conciliacao, Atribuicao, Reivindicacao, Acerto, Notificacao
│       ├── resource/           ← endpoints REST + ErrorMapper
│       ├── dto/ · security/ · bootstrap/
│       └── resources/db/migration/V1__schema.sql
├── frontend/                   ← Angular 17
│   ├── Dockerfile.runtime · nginx.conf
│   └── src/{sw.js, manifest.webmanifest, app/{core,layout,pages}}
├── .github/workflows/          ← ci.yml (build+testes+segurança) · cd.yml (GHCR → pull na VM)
├── scripts/                    ← oci-a1-retry · oci-bootstrap · oci-descobrir · duckdns-update · gen-jwt-keys
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

- **Backend**: `cd backend && mvn -B verify` — 26 testes, incluindo as duas
  invariantes de conciliação, a herança de parcela e o parser do Itaú contra o
  fixture `src/test/resources/fatura-itau-exemplo.txt`.
- **Frontend**: `cd frontend && npx ng build`.
- O perfil `%test` usa o banco **`jcard_test`** com `flyway.clean-at-start` — nunca
  aponte para o banco de dev, ele é apagado a cada execução.

## 6. Decisões que não são óbvias no código

- **Imagens ARM sem emulação.** As VMs são Ampere A1 (aarch64). O `cd.yml`
  compila no runner amd64 (`mvn package`, `ng build`) e os `Dockerfile.runtime`
  só fazem `COPY` do artefato numa base arm64. Buildar Maven sob QEMU custaria ~5x.
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

- **Rede** (criada em 07/08/2026): VCN `jcard-vcn` `10.1.0.0/16`, subnet pública
  `jcard-subnet` `10.1.1.0/24`, IGW e security list `jcard-sl`. OCIDs em
  `scripts/.oci-launch.env` (não versionado).
- **VMs**: `jcard-app` e `jcard-db`, A1.Flex 2 OCPU / 12 GB cada = o teto exato do
  Always Free (4 OCPU / 24 GB) → **US$ 0**.
  ⚠️ O par `E2.1.Micro` do Always Free já está **todo em uso pelo projeto
  `ebd-samambaia`** (`available: 0`) — daí a escolha por Ampere.
- **Capacidade A1 é o gargalo**: `sa-saopaulo-1` responde "Out of host capacity"
  com frequência. `scripts/oci-a1-retry.sh` insiste em segundo plano e, depois de
  ~40 min, cai para 1 OCPU/6 GB por VM.
- **Deploy**: merge na `main` → CI → CD publica no GHCR privado e a VM só faz
  `pull` + `up`. Rollback: `JCARD_IMAGE_TAG=<sha>` no `.env`.

## 9. Estado do projeto

- ✅ Backend completo, 26 testes verdes (`mvn verify`).
- ✅ Frontend Angular 17 + PWA compilando (84 kB no bundle inicial).
- ✅ CI/CD escritos; o CD roda em **modo mock** enquanto os secrets `OCI_*`
  estiverem vazios — a esteira fica verde antes de as VMs existirem.
- ✅ Rede OCI provisionada.
- ⏳ **VMs A1 aguardando capacidade** na Oracle (retry rodando).
- ⏳ **Parser do Itaú precisa ser calibrado com um PDF real.** A estrutura e os
  testes estão prontos contra um fixture anonimizado; as regexes foram escritas
  a partir do layout típico do Itaú e podem precisar de ajuste. Guia:
  `docs/parser-itau.md`.
- ⏳ Pendências do Danilo: token do DuckDNS, senha de app do Gmail, PAT com
  `read:packages`.
