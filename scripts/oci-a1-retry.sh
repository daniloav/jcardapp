#!/usr/bin/env bash
# ============================================================
# Cria as duas VMs A1.Flex (Always Free) contornando o "Out of host capacity",
# que é o normal em sa-saopaulo-1: a Oracle libera capacidade Ampere aos poucos.
#
# Rode do Mac e deixe em segundo plano:
#   nohup bash scripts/oci-a1-retry.sh > /tmp/a1.log 2>&1 &
#
# Configuração em scripts/.oci-launch.env (NÃO versionado):
#   cp scripts/.oci-launch.env.example scripts/.oci-launch.env
# ============================================================
set -uo pipefail
export SUPPRESS_LABEL_WARNING=True

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$DIR/.oci-launch.env"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "❌ Falta $ENV_FILE. Crie a partir do .oci-launch.env.example."
  exit 1
fi
# shellcheck source=/dev/null
source "$ENV_FILE"

: "${COMPARTMENT_ID:?}" ; : "${SUBNET_ID:?}" ; : "${IMAGE_ID:?}" ; : "${AD:?}"
SSH_KEY="${SSH_KEY_FILE:-$HOME/.ssh/jcard_deploy.pub}"
INTERVALO="${SLEEP_SECONDS:-60}"

# Total sempre dentro do teto Always Free (4 OCPU / 24 GB).
OCPUS=2 ; MEM=12 ; tentativa=0

existe() {
  oci compute instance list -c "$COMPARTMENT_ID" --display-name "$1" \
    --query "data[?\"lifecycle-state\"!='TERMINATED'] | [0].id" --raw-output 2>/dev/null
}

while true; do
  tentativa=$((tentativa + 1))

  # Depois de ~40 min sem capacidade, pede menos: 2 VMs de 1 OCPU/6 GB ainda são
  # 6x a RAM das VMs do projeto EBD, e subir agora vale mais que esperar o ideal.
  if [[ "$tentativa" -eq 40 && "$OCPUS" -eq 2 ]]; then
    OCPUS=1 ; MEM=6
    echo "[$(date '+%F %T')] sem capacidade em 2 OCPU/12GB — tentando 1 OCPU/6GB"
  fi

  faltam=0
  for nome in jcard-app jcard-db; do
    [[ -n "$(existe "$nome")" ]] && continue
    faltam=1
    echo "[$(date '+%F %T')] #$tentativa criando $nome (${OCPUS} OCPU / ${MEM} GB)"
    if id=$(oci compute instance launch \
        --compartment-id "$COMPARTMENT_ID" --availability-domain "$AD" \
        --shape VM.Standard.A1.Flex \
        --shape-config "{\"ocpus\":${OCPUS},\"memoryInGBs\":${MEM}}" \
        --image-id "$IMAGE_ID" --subnet-id "$SUBNET_ID" \
        --boot-volume-size-in-gbs 50 --assign-public-ip true \
        --display-name "$nome" --ssh-authorized-keys-file "$SSH_KEY" \
        --query 'data.id' --raw-output 2>/dev/null); then
      echo "[$(date '+%F %T')] ✅ $nome criada: $id"
    fi
  done

  if [[ "$faltam" -eq 0 ]]; then
    echo "[$(date '+%F %T')] ✅ jcard-app e jcard-db existem."
    echo "Descubra os IPs com: bash scripts/oci-descobrir.sh"
    exit 0
  fi
  sleep "$INTERVALO"
done
