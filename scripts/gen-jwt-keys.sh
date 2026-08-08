#!/usr/bin/env bash
# Gera o par de chaves RS256 do JWT para desenvolvimento.
# As chaves NÃO são versionadas (.gitignore). Em produção elas vêm dos secrets
# JCARD_JWT_* e são montadas no volume /keys pelo CD.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/backend/src/main/resources"
mkdir -p "$DIR"

if [[ -f "$DIR/privateKey.pem" && -f "$DIR/publicKey.pem" ]]; then
  echo "✔ Chaves já existem em $DIR — nada a fazer."
  exit 0
fi

openssl genrsa -out "$DIR/privateKey.pem" 2048 2>/dev/null
openssl rsa -in "$DIR/privateKey.pem" -pubout -out "$DIR/publicKey.pem" 2>/dev/null
chmod 600 "$DIR/privateKey.pem"

echo "✔ Chaves JWT geradas em $DIR"
echo "  privateKey.pem (600) e publicKey.pem — ambas ignoradas pelo git."
