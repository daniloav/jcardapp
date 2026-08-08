#!/usr/bin/env bash
# ============================================================
# Aponta o subdomínio DuckDNS para o IP público desta VM e instala um cron
# que mantém o registro atualizado.
#
# Rode NA VM jcard-app:
#   DUCKDNS_DOMINIO=jcardapp DUCKDNS_TOKEN=<seu-token> bash duckdns-update.sh
#
# O IP público da OCI é estável enquanto a VM existir, mas o DuckDNS expira
# registros sem atualização em ~30 dias — por isso o cron.
# ============================================================
set -euo pipefail

: "${DUCKDNS_DOMINIO:?defina DUCKDNS_DOMINIO (só o nome, sem .duckdns.org)}"
: "${DUCKDNS_TOKEN:?defina DUCKDNS_TOKEN}"

DIR="$HOME/.duckdns"
mkdir -p "$DIR"
chmod 700 "$DIR"

cat > "$DIR/atualizar.sh" <<EOF
#!/usr/bin/env bash
# Atualiza $DUCKDNS_DOMINIO.duckdns.org com o IP público atual desta VM.
curl -fsS "https://www.duckdns.org/update?domains=$DUCKDNS_DOMINIO&token=$DUCKDNS_TOKEN&ip=" \\
  -o "$DIR/ultimo.log" 2>&1
EOF
chmod 700 "$DIR/atualizar.sh"

echo "▶ Atualizando agora..."
bash "$DIR/atualizar.sh"
RESULTADO="$(cat "$DIR/ultimo.log")"
if [[ "$RESULTADO" != "OK" ]]; then
  echo "❌ DuckDNS respondeu: $RESULTADO"
  echo "   Confira o domínio e o token."
  exit 1
fi

# Idempotente: não duplica a linha se rodar de novo.
CRON="*/30 * * * * $DIR/atualizar.sh >/dev/null 2>&1"
( crontab -l 2>/dev/null | grep -vF "$DIR/atualizar.sh" ; echo "$CRON" ) | crontab -

echo "✅ $DUCKDNS_DOMINIO.duckdns.org aponta para esta VM (cron a cada 30 min)."
echo "   O Caddy provisiona o certificado Let's Encrypt sozinho no primeiro acesso."
