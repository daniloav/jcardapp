#!/usr/bin/env bash
# Mostra os IPs das VMs do JcardApp e os secrets que faltam cadastrar no GitHub.
set -euo pipefail
export SUPPRESS_LABEL_WARNING=True

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "$DIR/.oci-launch.env"

APP_PUB="" ; APP_PRIV="" ; DB_PRIV=""

for nome in jcard-app jcard-db; do
  id="$(oci compute instance list -c "$COMPARTMENT_ID" --display-name "$nome" \
        --query "data[?\"lifecycle-state\"=='RUNNING'] | [0].id" --raw-output 2>/dev/null)"
  if [[ -z "$id" || "$id" == "null" ]]; then
    echo "⏳ $nome ainda não está RUNNING"
    continue
  fi
  read -r pub priv < <(oci compute instance list-vnics --instance-id "$id" \
    --query 'data[0].["public-ip","private-ip"]' --raw-output 2>/dev/null \
    | tr -d '[]",' | tr '\n' ' ')
  cfg="$(oci compute instance get --instance-id "$id" \
        --query '"\(data."shape-config".ocpus) OCPU / \(data."shape-config"."memory-in-gbs") GB"' \
        --raw-output 2>/dev/null || echo '')"
  printf '  %-10s público=%-16s privado=%-12s %s\n' "$nome" "$pub" "$priv" "$cfg"
  [[ "$nome" == "jcard-app" ]] && { APP_PUB="$pub"; APP_PRIV="$priv"; }
  [[ "$nome" == "jcard-db"  ]] && DB_PRIV="$priv"
done

if [[ -z "$APP_PUB" || -z "$DB_PRIV" ]]; then
  echo
  echo "As duas VMs precisam estar RUNNING. O retry continua:"
  echo "  nohup bash scripts/oci-a1-retry.sh &"
  exit 1
fi

cat <<FIM

── Preencher no .env de produção ────────────────────────────────────────
  JCARD_DB_HOST=$DB_PRIV        # no .env da jcard-app
  JCARD_DB_BIND_IP=$DB_PRIV     # no .env da jcard-db

── Bootstrap (rodar em cada Vma) ────────────────────────────────────────
  ssh -i ~/.ssh/jcard_deploy ubuntu@$APP_PUB \\
    'curl -fsSL https://raw.githubusercontent.com/daniloav/jcardapp/main/scripts/oci-bootstrap.sh | bash -s -- app'

  # na jcard-db, informando o IP PRIVADO da app para liberar a 5432
  JCARD_APP_IP=$APP_PRIV bash oci-bootstrap.sh db

── Fechar a 5432 no /32 da app (endurece a Security List) ───────────────
  bash scripts/oci-restringir-db.sh $APP_PRIV

── Secrets do GitHub ────────────────────────────────────────────────────
  gh secret set OCI_SSH_HOST -b "$APP_PUB"
  gh secret set OCI_SSH_USER -b "ubuntu"
  gh secret set OCI_SSH_KEY  < ~/.ssh/jcard_deploy
  gh secret set OCI_ENV_FILE < .env
  gh secret set JCARD_JWT_PRIVATE_KEY < privateKey.pem
  gh secret set JCARD_JWT_PUBLIC_KEY  < publicKey.pem
  gh secret set JCARD_GHCR_USER -b "daniloav"
  gh secret set JCARD_GHCR_PAT  -b "<PAT com read:packages>"
FIM
