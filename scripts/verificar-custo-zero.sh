#!/usr/bin/env bash
# ============================================================
# Confere se a infraestrutura do JcardApp continua 100% gratuita.
#
#   bash scripts/verificar-custo-zero.sh
#
# POR QUE ISTO EXISTE: nenhum provedor bloqueia quando você passa do gratuito —
# eles deixam criar e cobram. No GCP o risco é concreto e silencioso: a mesma
# e2-micro é grátis em us-central1 e COBRADA em qualquer outra região, e um IP
# estático reservado passa a custar assim que fica ocioso.
#
# Sai com código 1 se achou algo fora do gratuito.
# ============================================================
set -uo pipefail

PROJETO="${GCP_PROJECT:-$(gcloud config get-value project 2>/dev/null)}"
falhas=0
ok()    { printf '  %-38s %s\n' "$1" "✅ $2"; }
falha() { printf '  %-38s %s\n' "$1" "❌ $2"; falhas=$((falhas + 1)); }

echo
echo "Google Cloud — Always Free"
echo "──────────────────────────────────────────────────────────────────────"

if ! command -v gcloud >/dev/null 2>&1; then
  echo "  gcloud não instalado — pulei a verificação do GCP."
  echo "  Instale com: brew install --cask google-cloud-sdk"
else
  # --- instâncias: shape e região decidem se é grátis --------------------
  gcloud compute instances list --project "$PROJETO" \
    --format='value(name,zone,machineType,status)' 2>/dev/null \
  | while read -r nome zona tipo estado; do
      [[ -z "$nome" ]] && continue
      regiao="${zona%-*}"
      if [[ "$tipo" != "e2-micro" ]]; then
        falha "$nome ($tipo)" "só e2-micro é gratuita"
      elif [[ "$regiao" != "us-west1" && "$regiao" != "us-central1" && "$regiao" != "us-east1" ]]; then
        falha "$nome em $regiao" "e2-micro só é grátis em us-west1/central1/east1"
      else
        ok "$nome · e2-micro · $regiao" "$estado"
      fi
    done

  QTD=$(gcloud compute instances list --project "$PROJETO" --format='value(name)' 2>/dev/null | wc -l | tr -d ' ')
  if [[ "${QTD:-0}" -gt 1 ]]; then
    falha "Instâncias e2-micro" "$QTD encontradas — o gratuito cobre 1"
  else
    ok "Quantidade de instâncias" "${QTD:-0} / 1"
  fi

  # --- disco: 30 GB de pd-standard ---------------------------------------
  DISCO=$(gcloud compute disks list --project "$PROJETO" \
    --format='value(sizeGb)' 2>/dev/null | awk '{s+=$1} END{print s+0}')
  if [[ "${DISCO:-0}" -gt 30 ]]; then
    falha "Disco persistente" "${DISCO} GB / 30 GB"
  else
    ok "Disco persistente" "${DISCO:-0} GB / 30 GB"
  fi

  # --- IP estático ocioso é cobrado; o efêmero da VM não ------------------
  IPS=$(gcloud compute addresses list --project "$PROJETO" --format='value(name)' 2>/dev/null | wc -l | tr -d ' ')
  if [[ "${IPS:-0}" -gt 0 ]]; then
    falha "IPs estáticos reservados" "$IPS — são cobrados; use o efêmero da VM"
  else
    ok "IPs estáticos reservados" "0 (a VM usa IP efêmero)"
  fi

  # --- serviços que cobram e não deveriam existir aqui --------------------
  for svc in "sql instances:Cloud SQL" "forwarding-rules:Load balancer"; do
    cmd="${svc%%:*}"; rotulo="${svc##*:}"
    n=$(gcloud ${cmd/sql instances/sql instances} list --project "$PROJETO" --format='value(name)' 2>/dev/null | wc -l | tr -d ' ')
    if [[ "${n:-0}" -gt 0 ]]; then falha "$rotulo" "$n encontrado(s) — cobram"; else ok "$rotulo" "0"; fi
  done
fi

echo
echo "Neon (Postgres) — plano gratuito"
echo "──────────────────────────────────────────────────────────────────────"
echo "  Confira em console.neon.tech → Usage:"
printf '  %-38s %s\n' "Storage" "0,5 GB por projeto"
printf '  %-38s %s\n' "Compute" "100 CU-hours/mês"
echo "  O pool do app é min-size=0 para o banco hibernar (~5 min ocioso)."
echo "  Se o consumo de CU passar de ~50/mês, algo está segurando conexão."

echo
echo "GitHub (plano Free)"
echo "──────────────────────────────────────────────────────────────────────"
printf '  %-38s %s\n' "Actions (repo privado)" "2.000 min/mês · ilimitado se público"
printf '  %-38s %s\n' "GHCR (repo privado)" "500 MB · o CD apaga versões antigas"

echo
if [[ "$falhas" -gt 0 ]]; then
  echo "❌ $falhas item(ns) fora do gratuito — isso está gerando cobrança."
  exit 1
fi
echo "✅ Nada fora do gratuito. Custo: US\$ 0."
