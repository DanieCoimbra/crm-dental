# Estágio 1: build (Maven + JDK 21). Nada deste estágio vai para a imagem final.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package \
    && java -Djarmode=tools -jar target/crm-api-*.jar extract --layers --destination /build/layers

# Estágio 2: runtime (JRE 21 enxuto), usuário não-root.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S crm && adduser -S -G crm crm
WORKDIR /app
COPY --from=build --chown=crm:crm /build/layers/dependencies/ ./
COPY --from=build --chown=crm:crm /build/layers/spring-boot-loader/ ./
COPY --from=build --chown=crm:crm /build/layers/snapshot-dependencies/ ./
COPY --from=build --chown=crm:crm /build/layers/application/ ./
USER crm

# Memória relativa ao limite do contêiner (plano gratuito do Render: 512 MB).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080

# Liveness (sem tocar no banco): o orquestrador usa o health check do serviço.
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -q -O /dev/null "http://127.0.0.1:${PORT:-8080}/actuator/health/liveness" || exit 1

ENTRYPOINT ["sh", "-c", "exec java -jar /app/crm-api-*.jar"]
