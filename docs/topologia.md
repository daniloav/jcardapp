# Topologia de produção

Referência autoritativa da infraestrutura. **Uma VM sempre-gratuita no Google
Cloud** + **Postgres no Neon**, com as imagens buildadas no CI e publicadas no GHCR.

```mermaid
flowchart LR
  U[Navegador / PWA] -->|HTTPS| CADDY
  subgraph VM["GCP e2-micro · us-central1 · 1 vCPU / 1 GB"]
    CADDY[Caddy 443/80] --> FE[nginx + Angular · 96m]
    FE -->|/api| BE[Quarkus · 700m]
    KEYS[("volume ./keys — JWT")] -.-> BE
  end
  BE -->|TLS| NEON[("Neon · Postgres serverless<br/>hiberna após 5 min")]
  GHCR[("GHCR · imagens amd64")] -.->|docker pull| VM
```

## Como chegamos aqui

A primeira escolha foi Oracle Cloud, que tem a melhor oferta gratuita do mercado
(4 OCPU / 24 GB de Ampere A1). O problema não foi cota: foi **capacidade**. Em
`sa-saopaulo-1` a Oracle respondeu `Out of host capacity` em **todas** as
tentativas, ao longo de dois dias, inclusive no menor shape possível
(1 OCPU / 1 GB). Como recursos Always Free só existem na *home region*, mudar de
região sairia do gratuito.

O GCP resolve porque a `e2-micro` está **disponível agora** — e tirar o Postgres
da máquina (indo para o Neon) elimina a razão que nos obrigava a duas VMs na
Oracle: 1 GB não comporta banco e app juntos, mas comporta o app sozinho.

Os scripts da Oracle seguem no repositório (`scripts/oci-*`) como plano B, caso
a capacidade Ampere apareça.

## A VM

| | **jcard-server** |
|---|---|
| Provedor | Google Cloud · **e2-micro** (Always Free) |
| Região | `us-central1` (grátis só em us-west1 / us-central1 / us-east1) |
| SO | Ubuntu 24.04 **amd64** · 2 GB swap |
| Disco | 30 GB `pd-standard` (o teto gratuito) |
| Roda | `caddy` + `frontend` (nginx) + `backend` (Quarkus) |
| Compose | `docker-compose.yml`, em `~/jcardapp` |

Limites: backend **700m**, frontend e Caddy **96m**. O JVM usa
`MaxRAMPercentage=60` e **SerialGC** — com uma vCPU compartilhada, um coletor
paralelo só disputaria CPU com a própria aplicação.

Latência de ~150 ms do Brasil. Para um app de formulário usado algumas vezes por
mês, na virada da fatura, é irrelevante.

## O banco

**Neon**, plano gratuito permanente: 0,5 GB de storage e 100 CU-hours/mês. Não
expira nem pausa como o Supabase (que desde fev/2026 pausa após 1 semana ociosa
e exige religar na mão — péssimo para um app mensal). O Neon **hiberna** após
~5 min sem conexão e volta em ~1 s.

> ⚠️ **Detalhe que decide se cabe no gratuito.** Se o app segurar uma conexão
> ociosa, o compute do Neon nunca hiberna: 0,25 CU × 730 h ≈ **180 CU-hours**,
> quase o dobro do limite. Por isso o `application.properties` fixa
> `min-size=0`, `initial-size=0` e `idle-removal-interval=2M` — o pool solta as
> conexões e o banco dorme. Com ~10 pessoas usando algumas vezes por mês, o
> consumo real fica em poucas CU-hours.

O `acquisition-timeout=30S` dá folga para o primeiro acesso do dia, que paga o
~1 s de despertar.

**Backup** é do Neon (point-in-time restore no plano gratuito), então não há cron
de `pg_dump` para manter.

## O que mantém tudo gratuito

| Recurso | Teto | Uso |
|---|---|---|
| GCP e2-micro | 1 instância, us-west1/central1/east1 | 1 em us-central1 |
| GCP disco | 30 GB pd-standard | 30 GB |
| GCP IP estático | cobrado se reservado | **nenhum** — usamos o efêmero da VM |
| Neon storage | 0,5 GB | alguns MB |
| Neon compute | 100 CU-hours/mês | poucas horas (hiberna) |
| GHCR (repo privado) | 500 MB | o CD guarda 5 versões |
| Actions (repo privado) | 2.000 min/mês | ~9 min por push |

Confira antes de mudar qualquer coisa:

```bash
bash scripts/verificar-custo-zero.sh
```

O script barra os dois erros mais fáceis de cometer: subir a VM numa região
errada (mesma máquina, mas cobrada) e reservar um IP estático (cobrado quando
ocioso).

> A conta do GCP exige cartão de crédito. Vale criar um **orçamento com alerta
> em R$ 0** no console de faturamento — o script confere o que existe, mas o
> alerta é a rede de segurança.

## Segurança

- HTTPS com **Caddy + Let's Encrypt** em `jcardapp.duckdns.org`, renovação automática.
- Firewall do projeto (`jcard-web`) libera só 80 e 443; SSH usa a chave
  `jcard_deploy`, sem senha.
- **Postgres nunca exposto**: o Neon só aceita TLS e a credencial vive no `.env`
  da VM (`chmod 600`), enviado pelo CD a partir do secret `DEPLOY_ENV_FILE`.
- Chaves **JWT** montadas em runtime (`./keys` → `/keys`): a imagem não carrega
  segredo e os tokens sobrevivem ao deploy.

## Operação

```bash
ssh -i ~/.ssh/jcard_deploy ubuntu@<IP> 'cd ~/jcardapp && docker compose ps'
ssh -i ~/.ssh/jcard_deploy ubuntu@<IP> 'cd ~/jcardapp && docker compose logs -f backend'
ssh -i ~/.ssh/jcard_deploy ubuntu@<IP> 'free -h && docker stats --no-stream'
curl -s https://jcardapp.duckdns.org/q/health

# banco: pelo psql local, com a string do painel do Neon
psql 'postgresql://USER:SENHA@ep-xxx.neon.tech/jcard?sslmode=require'
```

## Passo a passo do provisionamento

```bash
brew install --cask google-cloud-sdk
gcloud init && gcloud auth login
export GCP_PROJECT=<seu-projeto>
bash scripts/gcp-provisionar.sh          # cria a VM e o firewall
# na VM:
curl -fsSL https://raw.githubusercontent.com/daniloav/jcardapp/main/scripts/gcp-bootstrap.sh | bash
# de volta no Mac:
DUCKDNS_DOMINIO=jcardapp DUCKDNS_TOKEN=<token> bash scripts/duckdns-update.sh
gh secret set DEPLOY_SSH_HOST -b "<IP>"
gh secret set DEPLOY_SSH_USER -b "ubuntu"
gh secret set DEPLOY_SSH_KEY  < ~/.ssh/jcard_deploy
gh secret set DEPLOY_ENV_FILE < .env
```

## Pendente

- [ ] Conta no GCP e projeto criado (exige cartão; orçamento com alerta em R$ 0)
- [ ] Projeto no Neon + connection string no `.env`
- [ ] `gcp-provisionar.sh` e `gcp-bootstrap.sh`
- [ ] DuckDNS apontado
- [ ] Secrets `DEPLOY_*` → o CD sai do modo mock
- [ ] Calibrar o parser com um PDF real do Itaú
