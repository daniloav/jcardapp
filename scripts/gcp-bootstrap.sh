#!/usr/bin/env bash
# ============================================================
# Prepara a VM Ubuntu 24.04 (GCP e2-micro) para o JcardApp.
# Rode NA VM, uma vez só:
#
#   ssh -i ~/.ssh/jcard_deploy ubuntu@<IP>
#   curl -fsSL https://raw.githubusercontent.com/daniloav/jcardapp/main/scripts/gcp-bootstrap.sh | bash
# ============================================================
set -euo pipefail

echo "▶ Atualizando pacotes..."
sudo apt-get update -y
sudo apt-get install -y git curl ca-certificates rsync openssl

echo "▶ Instalando Docker + plugin compose..."
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sudo sh
fi
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER" || true

# ------------------------------------------------------------------ swap --
# A e2-micro tem 1 GB. O Postgres está fora (no Neon), então sobra bem mais que
# na tentativa na Oracle — mas o Quarkus ainda tem picos na subida e no parse de
# PDF. 2 GB de swap é a rede de segurança contra OOM kill.
RAM_MB=$(free -m | awk '/^Mem:/{print $2}')
echo "▶ RAM de ${RAM_MB} MB — criando 2 GB de swap..."
if ! sudo swapon --show | grep -q '/swapfile'; then
  sudo fallocate -l 2G /swapfile || sudo dd if=/dev/zero of=/swapfile bs=1M count=2048
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
  grep -q '/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
fi
# Manter as páginas quentes na RAM: swap é rede de segurança, não uso corrente.
echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-jcard.conf >/dev/null
sudo sysctl -p /etc/sysctl.d/99-jcard.conf >/dev/null
sudo swapon --show

# No GCP o firewall é do projeto (regra jcard-web, criada pelo script de
# provisionamento) e a imagem Ubuntu não vem com iptables restritivo — ao
# contrário da imagem da Oracle. Nada a fazer aqui.

echo
echo "✅ VM preparada."
echo "   Saia e entre de novo no SSH para o grupo 'docker' valer:"
echo "     exit && ssh -i ~/.ssh/jcard_deploy $USER@<IP>"
echo
echo "Próximo: DuckDNS, secrets DEPLOY_* no GitHub e o workflow CD."
