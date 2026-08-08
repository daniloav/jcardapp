#!/usr/bin/env bash
# ============================================================
# Prepara a VM Ubuntu 24.04 aarch64 (Oracle Cloud A1) para o JcardApp.
# Rode NA VM, uma única vez, no primeiro acesso SSH:
#   ssh -i ~/.ssh/jcard_deploy ubuntu@<IP>
#   curl -fsSL https://raw.githubusercontent.com/daniloav/jcardapp/main/scripts/oci-bootstrap.sh | bash
# ============================================================
set -euo pipefail

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

# A imagem da Oracle vem com a política padrão do iptables bloqueando quase
# tudo; a Security List da subnet sozinha não basta.
echo "▶ Liberando 80 e 443 no firewall da VM..."
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y iptables-persistent || true
sudo netfilter-persistent save 2>/dev/null || true

# Nada a fazer para o Postgres: ele roda na rede interna do compose, sem porta
# publicada no host — não há o que liberar nem o que proteger no firewall.

# Sem swap: a A1 tem 6–12 GB. O swap de 3 GB do projeto EBD existia porque
# aquelas VMs tinham 1 GB.

echo
echo "✅ VM preparada."
echo "   Saia e entre de novo no SSH para o grupo 'docker' valer:"
echo "     exit && ssh -i ~/.ssh/jcard_deploy $USER@<IP>"
echo
echo "Próximos passos (do Mac):"
echo "  1) DuckDNS:  DUCKDNS_DOMINIO=jcardapp DUCKDNS_TOKEN=<token> bash scripts/duckdns-update.sh"
echo "  2) Cadastre os secrets OCI_* no GitHub (bash scripts/oci-descobrir.sh mostra quais)"
echo "  3) Rode o workflow CD — ele faz o resto."
