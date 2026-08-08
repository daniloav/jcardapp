# Esteira CI/CD

## CI — `.github/workflows/ci.yml`

Roda em push e PR para `main` e `develop`.

| Job | O que faz |
|---|---|
| `backend` | `mvn verify` contra um Postgres de serviço — inclui as invariantes de conciliação, a herança de parcela e o parser |
| `frontend` | `npm ci && ng build` |
| `migrations` | Sobe o app de verdade contra Postgres e espera o `/q/health` ficar `UP`. Pega migration quebrada e entidade divergente do schema, que o teste unitário não pega |
| `sast` | Semgrep (security-audit, secrets, java, typescript, dockerfile) |
| `trivy` | Dependências e IaC. Relatório em HIGH+CRITICAL; **falha** em CRITICAL |
| `gitleaks` | Segredos no código |

## CD — `.github/workflows/cd.yml`

Dispara quando o CI passa na `main`, ou manualmente (`workflow_dispatch`).

### Por que o build não está no Dockerfile

As VMs são **Ampere A1, aarch64** — e, com 1 GB, nem comportariam um build. O caminho óbvio — `docker build` multi-stage
com `--platform linux/arm64` — rodaria Maven e Node sob emulação QEMU, cerca de
5x mais lento. E runner ARM gratuito do GitHub só existe em repositório público;
este é privado, porque guarda dado financeiro.

A saída aproveita que **bytecode Java e bundle JS são portáveis**: só a imagem
base precisa ser ARM.

```
job build (ubuntu-latest, amd64):
  mvn package          → backend/target/quarkus-app/     (nativo, rápido)
  ng build             → frontend/dist/                  (nativo, rápido)
  buildx --platform linux/arm64 com Dockerfile.runtime   (só COPY)
  push  ghcr.io/daniloav/jcard-{backend,frontend}:{latest,<sha>}
```

Por isso existem `backend/Dockerfile.runtime` e `frontend/Dockerfile.runtime`
sem estágio de build — eles **esperam** o artefato já compilado no contexto.
Buildar localmente exige rodar `mvn package` / `ng build` antes.

### Deploy

A VM nunca builda: `docker login ghcr` → `compose pull` → `up -d`. Leva ~2
minutos. O job também envia o `.env`, o `Caddyfile` e os composes, e grava as
chaves JWT em `./keys` a partir dos secrets — a imagem não carrega segredo e os
tokens sobrevivem ao deploy.

O deploy toca **só a `jcard-app`**. O Postgres vive na `jcard-db` e não muda a
cada release; mexer nele no deploy seria risco sem ganho.

### Modo mock

Enquanto `OCI_SSH_HOST` estiver vazio ou `CHANGEME`, o job de deploy só emite um
aviso e passa. As imagens continuam sendo publicadas no GHCR. Isso mantém a
esteira verde antes de as VMs A1 existirem.

## Secrets

| Secret | O que é |
|---|---|
| `OCI_SSH_HOST` | IP público da `jcard-app` |
| `OCI_SSH_USER` | `ubuntu` |
| `OCI_SSH_KEY` | conteúdo de `~/.ssh/jcard_deploy` (chave **privada**) |
| `OCI_ENV_FILE` | o `.env` de produção inteiro (ver `.env.example`) |
| `JCARD_JWT_PRIVATE_KEY` / `JCARD_JWT_PUBLIC_KEY` | par RS256 de produção |
| `JCARD_GHCR_USER` | `daniloav` |
| `JCARD_GHCR_PAT` | PAT classic com `read:packages` (a VM usa para o `pull`) |

Gere o par JWT de produção com:

```bash
openssl genrsa -out privateKey.pem 2048 && openssl rsa -in privateKey.pem -pubout -out publicKey.pem
```

## Rollback

Todo build publica também a tag `<sha>`. Para voltar:

```bash
ssh -i ~/.ssh/jcard_deploy ubuntu@<IP-app> "cd ~/jcardapp && sed -i 's/^JCARD_IMAGE_TAG=.*/JCARD_IMAGE_TAG=<sha>/' .env && docker compose -f docker-compose.app.yml --env-file .env up -d"
```

## Escopo do token `gh`

Push de arquivos em `.github/workflows/` por **HTTPS** exige o escopo `workflow`
no token OAuth. Por **SSH** (que é o protocolo configurado aqui) essa restrição
não se aplica. Se um dia der `refusing to allow an OAuth App to create or update
workflow`, o conserto é:

```bash
gh auth refresh -h github.com -s workflow
```
