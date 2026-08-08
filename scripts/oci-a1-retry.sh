#!/usr/bin/env bash
# ============================================================
# Cria a VM jcard-server (A1.Flex, Always Free) contornando o
# "Out of host capacity", que é o normal em sa-saopaulo-1: a Oracle libera
# capacidade Ampere aos poucos.
#
# Rode do Mac e deixe em segundo plano:
#   nohup bash scripts/oci-a1-retry.sh > /tmp/a1.log 2>&1 &
#
# Configuração em scripts/.oci-launch.env (NÃO versionado):
#   cp scripts/.oci-launch.env.example scripts/.oci-launch.env
#
# ⚠️  A conta é Pay-As-You-Go: a Oracle PERMITE criar além do Always Free e
#     COBRA por isso. Os tetos gratuitos são 4 OCPU + 24 GB de A1 no total da
#     tenancy e 200 GB de block storage. Este script fica dentro deles.
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
NOME="${DISPLAY_NAME:-jcard-server}"
SSH_KEY="${SSH_KEY_FILE:-$HOME/.ssh/jcard_deploy.pub}"
INTERVALO="${SLEEP_SECONDS:-60}"

# Uma VM só, com a stack inteira. Começa em 2 OCPU/12 GB (confortável e ainda
# dentro do gratuito) e, se a capacidade não aparecer, desce para 1 OCPU/6 GB —
# que já sobra para ~10 pessoas e é MUITO mais fácil de alocar.
# A A1.Flex pode ser redimensionada depois: parar a instância, editar o shape,
# ligar de novo. Então pegar capacidade agora vale mais que esperar o ideal.
OCPUS="${OCPUS:-2}" ; MEM="${MEM_GB:-12}" ; tentativa=0
DEGRADA_APOS="${DEGRADA_APOS:-10}"

if [[ -n "$(oci compute instance list -c "$COMPARTMENT_ID" --display-name "$NOME" \
      --query "data[?\"lifecycle-state\"!='TERMINATED'] | [0].id" --raw-output 2>/dev/null)" ]]; then
  echo "✅ $NOME já existe. Nada a fazer."
  exit 0
fi

echo "🚀 Criando '$NOME' (A1.Flex). Ctrl+C para parar."

while true; do
  tentativa=$((tentativa + 1))

  if [[ "$tentativa" -eq "$DEGRADA_APOS" && "$OCPUS" -ne 1 ]]; then
    OCPUS=1 ; MEM=6
    echo "[$(date '+%F %T')] sem capacidade em 2 OCPU/12GB — passando a pedir 1 OCPU/6GB"
  fi

  echo "[$(date '+%F %T')] #$tentativa tentando ${OCPUS} OCPU / ${MEM} GB"
  if id=$(oci compute instance launch \
      --compartment-id "$COMPARTMENT_ID" --availability-domain "$AD" \
      --shape VM.Standard.A1.Flex \
      --shape-config "{\"ocpus\":${OCPUS},\"memoryInGBs\":${MEM}}" \
      --image-id "$IMAGE_ID" --subnet-id "$SUBNET_ID" \
      --boot-volume-size-in-gbs 50 --assign-public-ip true \
      --display-name "$NOME" --ssh-authorized-keys-file "$SSH_KEY" \
      --query 'data.id' --raw-output 2>/dev/null); then
    echo "[$(date '+%F %T')] ✅ $NOME criada (${OCPUS} OCPU / ${MEM} GB): $id"
    echo "Próximo passo: bash scripts/oci-descobrir.sh"
    exit 0
  fi

  sleep "$INTERVALO"
done
