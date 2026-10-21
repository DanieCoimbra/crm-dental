# Imagem do harness de testes do banco: psql/pg_dump/pg_restore 16 + openssl (postgres:16) e age (backup). A mesma imagem
# roda localmente (docker compose --profile test); não há deriva entre ambientes.
FROM postgres:16
RUN apt-get update     && apt-get install -y --no-install-recommends age     && rm -rf /var/lib/apt/lists/*
