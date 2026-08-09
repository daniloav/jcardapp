#!/usr/bin/env bash
# ============================================================
# Cria a VM do JcardApp no Google Cloud, dentro do Always Free.
#
#   bash scripts/gcp-provisionar.sh
#
# Pré-requisitos (uma vez só, feitos por você):
#   1) conta no Google Cloud com faturamento ativo (exige cartão, mas o que
#      criamos aqui é sempre-gratuito)
#   2) gcloud instalado:  brew install --cask google-cloud-sdk
#   3) gcloud init && gcloud auth login
#
# ⚠️ O QUE MANTÉM ISTO GRATUITO — não altere sem conferir:
#   • shape e2-micro                (o único always-free)
#   • região us-west1/central1/east1 (fora delas, e2-micro é COBRADA)
#   • disco padrão (pd-standard) até 30 GB
#   • 1 IP externo efêmero          (IP estático reservado e ocioso é cobrado)
# ============================================================
set -euo pipefail

PROJETO="${GCP_PROJECT:-}"
ZONA="${GCP_ZONE:-us-central1-a}"
NOME="${GCP_VM:-jcard-server}"
DISCO_GB="${GCP_DISK_GB:-30}"

if ! command -v gcloud >/dev/null 2>&1; then
  echo "❌ gcloud não encontrado. Instale com:"
  echo "     brew install --cask google-cloud-sdk"
  exit 1
fi

if [[ -z "$PROJETO" ]]; then
  PROJETO="$(gcloud config get-value project 2>/dev/null)"
fi
if [[ -z "$PROJETO" || "$PROJETO" == "(unset)" ]]; then
  echo "❌ Defina o projeto:  export GCP_PROJECT=meu-projeto"
  echo "   ou:               gcloud config set project meu-projeto"
  exit 1
fi

# A região decide se é grátis ou cobrado — vale barrar antes de criar.
case "$ZONA" in
  us-west1-*|us-central1-*|us-east1-*) ;;
  *)
    echo "❌ Zona '$ZONA' NÃO é elegível ao Always Free da e2-micro."
    echo "   Use us-west1-*, us-central1-* ou us-east1-*."
    exit 1
    ;;
esac

echo "▶ Projeto: $PROJETO · Zona: $ZONA · VM: $NOME"

echo "▶ Habilitando a API do Compute Engine (idempotente)..."
gcloud services enable compute.googleapis.com --project "$PROJETO" -q

echo "▶ Regra de firewall para HTTP/HTTPS..."
if ! gcloud compute firewall-rules describe jcard-web --project "$PROJETO" >/dev/null 2>&1; then
  gcloud compute firewall-rules create jcard-web \
    --project "$PROJETO" --allow tcp:80,tcp:443 \
    --target-tags jcard --description "JcardApp: HTTP/HTTPS" -q
fi

if gcloud compute instances describe "$NOME" --zone "$ZONA" --project "$PROJETO" >/dev/null 2>&1; then
  echo "✅ $NOME já existe."
else
  echo "▶ Criando a VM (e2-micro, sempre gratuita)..."
  gcloud compute instances create "$NOME" \
    --project "$PROJETO" --zone "$ZONA" \
    --machine-type e2-micro \
    --image-family ubuntu-2404-lts-amd64 --image-project ubuntu-os-cloud \
    --boot-disk-size "${DISCO_GB}GB" --boot-disk-type pd-standard \
    --tags jcard \
    --metadata-from-file ssh-keys=<(echo "ubuntu:$(cat "$HOME/.ssh/jcard_deploy.pub")") \
    -q
fi

IP="$(gcloud compute instances describe "$NOME" --zone "$ZONA" --project "$PROJETO" \
      --format='get(networkInterfaces[0].accessConfigs[0].natIP)')"

cat <<FIM

✅ VM pronta — IP público: $IP

Próximos passos:

  1) Preparar a VM (Docker, firewall, swap):
     ssh -i ~/.ssh/jcard_deploy ubuntu@$IP \\
       'curl -fsSL https://raw.githubusercontent.com/daniloav/jcardapp/main/scripts/gcp-bootstrap.sh | bash'

  2) Apontar o DuckDNS para $IP

  3) Cadastrar os secrets:
     gh secret set DEPLOY_SSH_HOST -b "$IP"
     gh secret set DEPLOY_SSH_USER -b "ubuntu"
     gh secret set DEPLOY_SSH_KEY  < ~/.ssh/jcard_deploy
     gh secret set DEPLOY_ENV_FILE < .env

  4) Rodar o workflow CD.

Confira o custo depois:  bash scripts/verificar-custo-zero.sh
FIM
