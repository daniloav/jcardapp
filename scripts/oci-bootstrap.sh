#!/usr/bin/env bash
# ============================================================
# Prepara uma VM Ubuntu 24.04 aarch64 (Oracle Cloud A1, 1 OCPU / 1 GB) para o
# JcardApp. Rode NA VM, uma única vez, no primeiro acesso SSH.
#
#   # na jcard-app
#   curl -fsSL https://raw.githubusercontent.com/daniloav/jcardapp/main/scripts/oci-bootstrap.sh | bash -s -- app
#
#   # na jcard-db (precisa do IP PRIVADO da jcard-app)
#   curl -fsSL .../oci-bootstrap.sh | JCARD_APP_IP=10.1.1.x bash -s -- db
# ============================================================
set -euo pipefail

PAPEL="${1:-app}"

echo "▶ Atualizando pacotes..."
sudo apt-get update -y

echo "▶ Instalando utilitários..."
sudo apt-get install -y git curl ca-certificates rsync openssl

echo "▶ Instalando Docker + plugin compose..."
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sudo sh
fi
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER" || true

# ------------------------------------------------------------------ swap --
# Obrigatório neste shape: 1 GB de RAM tem de servir ao SO, ao Docker e aos
# containers. Sem swap, o primeiro pico de memória do Quarkus (ou um VACUUM do
# Postgres) leva OOM kill. É a mesma configuração do ebd-samambaia, que roda
# neste shape em produção.
echo "▶ Criando 3 GB de swap (essencial em VM de 1 GB)..."
if ! sudo swapon --show | grep -q '/swapfile'; then
  sudo fallocate -l 3G /swapfile || sudo dd if=/dev/zero of=/swapfile bs=1M count=3072
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
  grep -q '/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
fi
# Preferir manter as páginas quentes na RAM: o swap é rede de segurança, não
# memória de uso corrente — swappiness alto deixaria o app lento sem necessidade.
echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-jcard.conf >/dev/null
sudo sysctl -p /etc/sysctl.d/99-jcard.conf >/dev/null
sudo swapon --show

# -------------------------------------------------------------- firewall --
# A imagem da Oracle vem com a política padrão do iptables bloqueando quase
# tudo; a Security List da subnet sozinha não basta.
echo "▶ Ajustando o firewall da VM (papel: $PAPEL)..."
if [[ "$PAPEL" == "db" ]]; then
  if [[ -z "${JCARD_APP_IP:-}" ]]; then
    echo "❌ Defina JCARD_APP_IP com o IP PRIVADO da jcard-app."
    echo "   ex.: JCARD_APP_IP=10.1.1.20 bash oci-bootstrap.sh db"
    exit 1
  fi
  # Postgres só do IP privado da VM de app — segunda camada, junto da Security
  # List. Nesta topologia o 5432 trafega entre máquinas, então vale o cinto e a
  # suspensória.
  sudo iptables -I INPUT 6 -p tcp -s "$JCARD_APP_IP/32" --dport 5432 -j ACCEPT
  sudo iptables -A INPUT -p tcp --dport 5432 -j DROP
  echo "   5432 liberada só para $JCARD_APP_IP"
else
  sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
  sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
  echo "   80 e 443 liberadas"
fi

sudo DEBIAN_FRONTEND=noninteractive apt-get install -y iptables-persistent || true
sudo netfilter-persistent save 2>/dev/null || true

echo
echo "✅ VM preparada como '$PAPEL'."
echo "   Saia e entre de novo no SSH para o grupo 'docker' valer:"
echo "     exit && ssh -i ~/.ssh/jcard_deploy $USER@<IP>"
echo
if [[ "$PAPEL" == "db" ]]; then
  echo "Na jcard-db, suba o Postgres:"
  echo "  cd ~/jcardapp && docker compose -f docker-compose.db.yml --env-file .env up -d"
else
  echo "Próximos passos (do Mac):"
  echo "  1) DuckDNS:  DUCKDNS_DOMINIO=jcardapp DUCKDNS_TOKEN=<token> bash scripts/duckdns-update.sh"
  echo "  2) Secrets OCI_* no GitHub (bash scripts/oci-descobrir.sh mostra quais)"
  echo "  3) Rode o workflow CD."
fi
