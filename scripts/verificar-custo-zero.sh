#!/usr/bin/env bash
# ============================================================
# Confere se a infraestrutura do JcardApp continua 100% dentro do Always Free.
#
#   bash scripts/verificar-custo-zero.sh
#
# POR QUE ISTO EXISTE: a conta é Pay-As-You-Go, não Free Trial. A Oracle NÃO
# bloqueia quando você passa do gratuito — ela deixa criar e cobra. Sem uma
# checagem explícita, um shape um pouco maior ou um volume esquecido viram
# fatura no fim do mês sem nenhum aviso.
#
# Sai com código 1 se algo estourou o teto.
# ============================================================
set -uo pipefail
export SUPPRESS_LABEL_WARNING=True

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "$DIR/.oci-launch.env" ]]; then
  # shellcheck source=/dev/null
  source "$DIR/.oci-launch.env"
fi
T="${COMPARTMENT_ID:?defina COMPARTMENT_ID em scripts/.oci-launch.env}"
AD="${AD:-xiXO:SA-SAOPAULO-1-AD-1}"

falhas=0

linha() { printf '  %-34s %-18s %s\n' "$1" "$2" "$3"; }
avalia() { # $1=rótulo $2=usado $3=teto $4=unidade
  local usado="${2:-0}" teto="$3"
  if awk "BEGIN{exit !($usado > $teto)}"; then
    linha "$1" "$usado / $teto $4" "❌ PASSOU DO GRATUITO"
    falhas=$((falhas + 1))
  else
    linha "$1" "$usado / $teto $4" "✅"
  fi
}

echo
echo "Always Free — tenancy inteira (inclui o projeto ebd-samambaia)"
echo "───────────────────────────────────────────────────────────────────────"

# --- Compute Ampere A1 -------------------------------------------------------
A1_CPU=$(oci limits resource-availability get --compartment-id "$T" --service-name compute \
  --limit-name standard-a1-core-count --availability-domain "$AD" \
  --query 'data.used' --raw-output 2>/dev/null || echo 0)
A1_MEM=$(oci limits resource-availability get --compartment-id "$T" --service-name compute \
  --limit-name standard-a1-memory-count --availability-domain "$AD" \
  --query 'data.used' --raw-output 2>/dev/null || echo 0)
avalia "Ampere A1 · OCPU"     "$A1_CPU" 4   "OCPU"
avalia "Ampere A1 · memória"  "$A1_MEM" 24  "GB"

# --- Micro AMD ---------------------------------------------------------------
MICRO=$(oci limits resource-availability get --compartment-id "$T" --service-name compute \
  --limit-name vm-standard-e2-1-micro-count --availability-domain "$AD" \
  --query 'data.used' --raw-output 2>/dev/null || echo 0)
avalia "VM.Standard.E2.1.Micro" "$MICRO" 2 "instâncias"

# --- Block storage (boot + block) -------------------------------------------
BOOT=$(oci bv boot-volume list -c "$T" --availability-domain "$AD" \
  --query 'sum(data[].{s:"size-in-gbs"}[].s)' --raw-output 2>/dev/null || echo 0)
BLOCK=$(oci bv volume list -c "$T" --availability-domain "$AD" \
  --query 'sum(data[].{s:"size-in-gbs"}[].s)' --raw-output 2>/dev/null || echo 0)
TOTAL_ST=$(awk "BEGIN{print ${BOOT:-0} + ${BLOCK:-0}}")
avalia "Block storage (boot+block)" "$TOTAL_ST" 200 "GB"

# --- Coisas que cobram e não deviam existir ---------------------------------
echo
echo "Recursos que geram cobrança — devem estar zerados"
echo "───────────────────────────────────────────────────────────────────────"

LB=$(oci lb load-balancer list -c "$T" --query 'length(data)' --raw-output 2>/dev/null || echo 0)
avalia "Load balancers"           "${LB:-0}" 1 "(1 é grátis, 10 Mbps)"

# Backups de volume contam separado do storage e passam despercebidos.
BKP=$(oci bv boot-volume-backup list -c "$T" --query 'length(data)' --raw-output 2>/dev/null || echo 0)
avalia "Backups de boot volume"   "${BKP:-0}" 5 "(5 grátis)"

echo
echo "GitHub (plano Free) — verificar em github.com/settings/billing"
echo "───────────────────────────────────────────────────────────────────────"
linha "Actions em repo privado" "2.000 min/mês" "CI ~6 min + CD ~3 min por push"
linha "GHCR em repo privado"    "500 MB"        "o CD apaga versões antigas"

echo
if [[ "$falhas" -gt 0 ]]; then
  echo "❌ $falhas item(ns) fora do Always Free — isso está gerando cobrança."
  exit 1
fi
echo "✅ Tudo dentro do Always Free. Custo: US\$ 0."
