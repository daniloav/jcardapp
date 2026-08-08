#!/usr/bin/env bash
# Mostra os IPs público e privado das VMs do JcardApp e os secrets a cadastrar.
set -euo pipefail
export SUPPRESS_LABEL_WARNING=True

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "$DIR/.oci-launch.env"

for nome in jcard-app jcard-db; do
  id="$(oci compute instance list -c "$COMPARTMENT_ID" --display-name "$nome" \
        --query "data[?\"lifecycle-state\"=='RUNNING'] | [0].id" --raw-output 2>/dev/null)"
  if [[ -z "$id" || "$id" == "null" ]]; then
    echo "$nome: ainda não está RUNNING"
    continue
  fi
  oci compute instance list-vnics --instance-id "$id" \
    --query "data[0].{publico:\"public-ip\",privado:\"private-ip\"}" --output json 2>/dev/null \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(f\"$nome: público={d['publico']} privado={d['privado']}\")"
done

cat <<'FIM'

Secrets a cadastrar no GitHub (Settings → Secrets → Actions):
  OCI_SSH_HOST            IP público da jcard-app
  OCI_SSH_USER            ubuntu
  OCI_SSH_KEY             conteúdo de ~/.ssh/jcard_deploy (a chave PRIVADA)
  OCI_ENV_FILE            o .env de produção inteiro (com JCARD_DB_HOST = IP privado da jcard-db)
  JCARD_JWT_PRIVATE_KEY   openssl genrsa 2048
  JCARD_JWT_PUBLIC_KEY    a pública correspondente
  JCARD_GHCR_USER         daniloav
  JCARD_GHCR_PAT          PAT classic com read:packages
FIM
