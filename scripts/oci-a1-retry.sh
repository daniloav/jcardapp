#!/usr/bin/env bash
# ============================================================
# Cria as DUAS VMs do JcardApp (A1.Flex, Always Free) contornando o
# "Out of host capacity", que é o normal em sa-saopaulo-1: a Oracle libera
# capacidade Ampere aos poucos.
#
# Topologia igual à do projeto ebd-samambaia, que roda assim em produção hoje:
#   jcard-app  -> caddy + frontend + backend
#   jcard-db   -> Postgres
# Cada uma com 1 OCPU / 1 GB, o menor pedido possível — o que maximiza a chance
# de encaixar num host cheio. Ambas precisam de swap (o bootstrap cria).
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
VMS=(${VMS:-jcard-app jcard-db})
SSH_KEY="${SSH_KEY_FILE:-$HOME/.ssh/jcard_deploy.pub}"
INTERVALO="${SLEEP_SECONDS:-60}"

# 1 OCPU / 1 GB por VM — o mínimo que a A1.Flex aceita, e o mesmo tamanho das
# VMs do EBD. Usa 2 OCPU / 2 GB dos 4 OCPU / 24 GB do Always Free, então sobra
# cota de sobra; o limite que aperta é o disco (50 GB de boot por VM, 200 GB no
# total da conta -> ficaremos em 194 GB com o EBD junto).
#
# Se um dia precisar de mais, a A1.Flex é redimensionável: parar a instância,
# editar o shape, ligar de novo.
OCPUS="${OCPUS:-1}" ; MEM="${MEM_GB:-1}"
tentativa=0

existe() {
  oci compute instance list -c "$COMPARTMENT_ID" --display-name "$1" \
    --query "data[?\"lifecycle-state\"!='TERMINATED'] | [0].id" --raw-output 2>/dev/null
}

echo "🚀 Criando ${VMS[*]} (A1.Flex · ${OCPUS} OCPU / ${MEM} GB cada). Ctrl+C para parar."

while true; do
  tentativa=$((tentativa + 1))
  faltam=0

  for nome in "${VMS[@]}"; do
    if [[ -n "$(existe "$nome")" ]]; then continue; fi
    faltam=1
    printf '[%s] #%s %s ... ' "$(date '+%F %T')" "$tentativa" "$nome"

    if id=$(oci compute instance launch \
        --compartment-id "$COMPARTMENT_ID" --availability-domain "$AD" \
        --shape VM.Standard.A1.Flex \
        --shape-config "{\"ocpus\":${OCPUS},\"memoryInGBs\":${MEM}}" \
        --image-id "$IMAGE_ID" --subnet-id "$SUBNET_ID" \
        --boot-volume-size-in-gbs 50 --assign-public-ip true \
        --display-name "$nome" --ssh-authorized-keys-file "$SSH_KEY" \
        --query 'data.id' --raw-output 2>/dev/null); then
      echo "✅ criada: $id"
    else
      echo "sem capacidade"
    fi
    sleep 5
  done

  if [[ "$faltam" -eq 0 ]]; then
    echo "[$(date '+%F %T')] ✅ ${VMS[*]} existem."
    echo "Próximo passo: bash scripts/oci-descobrir.sh"
    exit 0
  fi
  sleep "$INTERVALO"
done
