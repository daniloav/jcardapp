#!/usr/bin/env bash
# ============================================================
# Prepara uma VM Ubuntu 24.04 aarch64 (Oracle Cloud A1) para o JcardApp.
# Rode NA VM, uma única vez, no primeiro acesso SSH:
#   ssh -i ~/.ssh/jcard_deploy ubuntu@<IP>
#   curl -fsSL https://raw.githubusercontent.com/daniloav/jcardapp/main/scripts/oci-bootstrap.sh | bash -s -- app
#
# Argumento: "app" (padrão) ou "db" — o db libera 5432 só para o IP da app.
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

# A imagem da Oracle vem com a política padrão do iptables bloqueando quase tudo;
# a Security List da subnet sozinha não basta.
echo "▶ Ajustando o firewall da VM (papel: $PAPEL)..."
if [[ "$PAPEL" == "db" ]]; then
  if [[ -z "${JCARD_APP_IP:-}" ]]; then
    echo "⚠  Defina JCARD_APP_IP com o IP PRIVADO da jcard-app antes de rodar com 'db'."
    echo "   ex.: JCARD_APP_IP=10.1.1.20 bash oci-bootstrap.sh db"
    exit 1
  fi
  # Postgres só do IP privado da VM de app — segunda camada, junto da Security List.
  sudo iptables -I INPUT 6 -p tcp -s "$JCARD_APP_IP/32" --dport 5432 -j ACCEPT
  sudo iptables -A INPUT -p tcp --dport 5432 -j DROP
else
  sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
  sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
fi

sudo DEBIAN_FRONTEND=noninteractive apt-get install -y iptables-persistent || true
sudo netfilter-persistent save 2>/dev/null || true

# Sem swap: a A1 tem 12 GB de RAM. O swap de 3 GB do projeto EBD existia porque
# aquelas VMs tinham 1 GB.

echo
echo "✅ VM preparada como '$PAPEL'."
echo "   Saia e entre de novo no SSH para o grupo 'docker' valer:"
echo "     exit && ssh -i ~/.ssh/jcard_deploy $USER@<IP>"
echo
echo "Próximo passo: cadastre os secrets OCI_* no GitHub e rode o workflow CD."
