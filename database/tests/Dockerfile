# Imagem do harness de testes do banco: psql/pg_dump/pg_restore 16 + openssl (postgres:16), age (backup) e Flyway Community
# (copiado da imagem oficial). A mesma imagem roda localmente (docker compose --profile test) e nos workflows, então não há
# deriva entre os dois.
FROM flyway/flyway:11.20.3 AS flyway

FROM postgres:16
RUN apt-get update \
    && apt-get install -y --no-install-recommends default-jre-headless age \
    && rm -rf /var/lib/apt/lists/*
COPY --from=flyway /flyway /flyway
ENV PATH="/flyway:${PATH}"
