#!/usr/bin/env bash
# Gera o verificador SCRAM-SHA-256 do PostgreSQL a partir de uma senha, para que a senha
# em texto claro NUNCA vá dentro de uma instrução SQL (os logs do servidor registram o texto
# de comandos que falham). Requer OpenSSL 3 (runners do GitHub e imagem postgres o têm).
#
# Uso : SCRAM_PASSWORD='...' scram_verifier.sh      (a senha vem do ambiente, não de argumento)
# Saída: SCRAM-SHA-256$4096:<salt base64>$<StoredKey base64>:<ServerKey base64>
set -euo pipefail

: "${SCRAM_PASSWORD:?defina SCRAM_PASSWORD}"
if [ "${#SCRAM_PASSWORD}" -lt 24 ]; then echo "senha com menos de 24 caracteres" >&2; exit 2; fi
ITER=4096

hex_to_bin() { sed 's/../\\x&/g' | xargs -0 printf; }          # hex (stdin) -> bytes
hex_to_b64() { printf '%s' "$1" | hex_to_bin | base64 | tr -d '\n'; }
hmac_hex()   { printf '%s' "$2" | openssl dgst -sha256 -mac HMAC -macopt "hexkey:$1" -hex | sed 's/^.*= //'; }

salt_hex=$(openssl rand -hex 16)

# SaltedPassword = PBKDF2-HMAC-SHA256(senha, salt, 4096, 32 bytes)
salted_hex=$(openssl kdf -keylen 32 -kdfopt digest:SHA256 \
  -kdfopt "pass:${SCRAM_PASSWORD}" -kdfopt "hexsalt:${salt_hex}" -kdfopt "iter:${ITER}" PBKDF2 \
  | tr -d ':' | tr 'A-F' 'a-f')

client_key=$(hmac_hex "$salted_hex" "Client Key")
stored_key=$(printf '%s' "$client_key" | hex_to_bin | openssl dgst -sha256 -hex | sed 's/^.*= //')
server_key=$(hmac_hex "$salted_hex" "Server Key")

printf 'SCRAM-SHA-256$%s:%s$%s:%s\n' "$ITER" "$(hex_to_b64 "$salt_hex")" \
  "$(hex_to_b64 "$stored_key")" "$(hex_to_b64 "$server_key")"
