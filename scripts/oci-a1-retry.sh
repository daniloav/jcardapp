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

# Escada de tamanhos, do melhor para o mínimo viável. A cada rodada tenta TODOS,
# porque capacidade Ampere aparece em fatias: pedir menos aumenta muito a chance
# de encaixar num host com pouco espaço livre.
#
# O piso é 1 OCPU / 3 GB. A A1.Flex aceita até 1 GB (igual às VMs do EBD), mas
# 1 GB não roda a stack inteira numa máquina só — foi justamente por isso que o
# EBD precisou de DUAS VMs de 1 GB, e ainda com 3 GB de swap. Aqui rodam juntos
# Postgres + Quarkus + nginx + Caddy: abaixo de ~2 GB o backend não sobe.
#
# Pegar o que aparecer vale mais que esperar o ideal: a A1.Flex é
# redimensionável depois (parar a instância → editar o shape → ligar).
ESCADA=(${ESCADA:-"2:12" "1:6" "1:4" "1:3"})
tentativa=0

if [[ -n "$(oci compute instance list -c "$COMPARTMENT_ID" --display-name "$NOME" \
      --query "data[?\"lifecycle-state\"!='TERMINATED'] | [0].id" --raw-output 2>/dev/null)" ]]; then
  echo "✅ $NOME já existe. Nada a fazer."
  exit 0
fi

echo "🚀 Criando '$NOME' (A1.Flex). Ctrl+C para parar."

while true; do
  tentativa=$((tentativa + 1))

  for passo in "${ESCADA[@]}"; do
    OCPUS="${passo%%:*}" ; MEM="${passo##*:}"
    printf '[%s] #%s %s OCPU / %s GB ... ' "$(date '+%F %T')" "$tentativa" "$OCPUS" "$MEM"

    if id=$(oci compute instance launch \
        --compartment-id "$COMPARTMENT_ID" --availability-domain "$AD" \
        --shape VM.Standard.A1.Flex \
        --shape-config "{\"ocpus\":${OCPUS},\"memoryInGBs\":${MEM}}" \
        --image-id "$IMAGE_ID" --subnet-id "$SUBNET_ID" \
        --boot-volume-size-in-gbs 50 --assign-public-ip true \
        --display-name "$NOME" --ssh-authorized-keys-file "$SSH_KEY" \
        --query 'data.id' --raw-output 2>/dev/null); then
      echo "✅"
      echo "[$(date '+%F %T')] $NOME criada com ${OCPUS} OCPU / ${MEM} GB: $id"
      if [[ "$MEM" -lt 4 ]]; then
        echo "⚠️  Com ${MEM} GB, rode o bootstrap com swap:  bash scripts/oci-bootstrap.sh --swap"
      fi
      echo "Próximo passo: bash scripts/oci-descobrir.sh"
      exit 0
    fi
    echo "sem capacidade"
    sleep 5
  done

  sleep "$INTERVALO"
done
