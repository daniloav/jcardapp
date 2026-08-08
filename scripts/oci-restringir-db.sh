#!/usr/bin/env bash
# ============================================================
# Fecha a porta 5432 no /32 da VM de app.
#
#   bash scripts/oci-restringir-db.sh 10.1.1.20
#
# Ao criar a rede, a regra do Postgres nasce aberta para a subnet inteira
# (10.1.1.0/24), porque o IP privado da jcard-app ainda não existia. Depois que
# as VMs sobem, vale apertar: qualquer coisa que apareça na subnet no futuro
# deixa de alcançar o banco.
#
# É a camada de rede; o iptables da jcard-db (posto pelo oci-bootstrap.sh db)
# é a segunda.
# ============================================================
set -euo pipefail
export SUPPRESS_LABEL_WARNING=True

APP_IP="${1:?informe o IP PRIVADO da jcard-app, ex.: 10.1.1.20}"

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "$DIR/.oci-launch.env"
: "${SECURITY_LIST_ID:?defina SECURITY_LIST_ID em scripts/.oci-launch.env}"

REGRAS="$(mktemp)"
trap 'rm -f "$REGRAS"' EXIT

cat > "$REGRAS" <<JSON
[
  {"protocol":"6","source":"0.0.0.0/0","sourceType":"CIDR_BLOCK","isStateless":false,
   "description":"SSH (deploy via GitHub Actions)",
   "tcpOptions":{"destinationPortRange":{"min":22,"max":22}}},
  {"protocol":"6","source":"0.0.0.0/0","sourceType":"CIDR_BLOCK","isStateless":false,
   "description":"HTTP (redirect + ACME Let's Encrypt)",
   "tcpOptions":{"destinationPortRange":{"min":80,"max":80}}},
  {"protocol":"6","source":"0.0.0.0/0","sourceType":"CIDR_BLOCK","isStateless":false,
   "description":"HTTPS",
   "tcpOptions":{"destinationPortRange":{"min":443,"max":443}}},
  {"protocol":"6","source":"$APP_IP/32","sourceType":"CIDR_BLOCK","isStateless":false,
   "description":"Postgres somente da jcard-app",
   "tcpOptions":{"destinationPortRange":{"min":5432,"max":5432}}},
  {"protocol":"1","source":"0.0.0.0/0","sourceType":"CIDR_BLOCK","isStateless":false,
   "description":"ICMP path MTU discovery","icmpOptions":{"type":3,"code":4}}
]
JSON

oci network security-list update --security-list-id "$SECURITY_LIST_ID" --force \
  --ingress-security-rules "file://$REGRAS" \
  --query 'data."lifecycle-state"' --raw-output

echo "✅ 5432 agora aceita só $APP_IP/32."
