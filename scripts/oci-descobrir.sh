#!/usr/bin/env bash
# Mostra o IP da VM do JcardApp e os secrets que faltam cadastrar no GitHub.
set -euo pipefail
export SUPPRESS_LABEL_WARNING=True

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "$DIR/.oci-launch.env"
NOME="${DISPLAY_NAME:-jcard-server}"

id="$(oci compute instance list -c "$COMPARTMENT_ID" --display-name "$NOME" \
      --query "data[?\"lifecycle-state\"=='RUNNING'] | [0].id" --raw-output 2>/dev/null)"

if [[ -z "$id" || "$id" == "null" ]]; then
  echo "⏳ $NOME ainda não está RUNNING."
  echo "   O retry de capacidade continua: nohup bash scripts/oci-a1-retry.sh &"
  exit 1
fi

oci compute instance get --instance-id "$id" \
  --query 'data.{shape:shape,ocpus:"shape-config".ocpus,mem:"shape-config"."memory-in-gbs"}' \
  --output json 2>/dev/null \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print(f\"  shape: {d['shape']} · {d['ocpus']} OCPU · {d['mem']} GB\")"

IP="$(oci compute instance list-vnics --instance-id "$id" \
      --query 'data[0]."public-ip"' --raw-output 2>/dev/null)"
echo "  IP público: $IP"

cat <<FIM

Secrets a cadastrar (GitHub → Settings → Secrets and variables → Actions):

  OCI_SSH_HOST            $IP
  OCI_SSH_USER            ubuntu
  OCI_SSH_KEY             conteúdo de ~/.ssh/jcard_deploy  (a chave PRIVADA)
  OCI_ENV_FILE            o .env de produção inteiro (modelo em .env.example)
  JCARD_JWT_PRIVATE_KEY   openssl genrsa -out privateKey.pem 2048
  JCARD_JWT_PUBLIC_KEY    openssl rsa -in privateKey.pem -pubout
  JCARD_GHCR_USER         daniloav
  JCARD_GHCR_PAT          PAT classic com read:packages

Atalho para cadastrar tudo de uma vez (com o gh CLI):

  gh secret set OCI_SSH_HOST -b "$IP"
  gh secret set OCI_SSH_USER -b "ubuntu"
  gh secret set OCI_SSH_KEY < ~/.ssh/jcard_deploy
  gh secret set OCI_ENV_FILE < .env
FIM
