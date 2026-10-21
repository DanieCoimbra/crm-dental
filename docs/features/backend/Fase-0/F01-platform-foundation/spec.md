# Spec — F01 Platform Foundation

Complementa `docs/global/backend/spec.md` (seções 1, 3, 6, 7 e 8). Em caso de conflito, vale o spec global.

## 1. Visão técnica
- **Feature:** F01 Platform Foundation.
- **Stack:** Spring Boot 4.1.1, Spring Web MVC, Bean Validation, Actuator, springdoc (dev), Maven, Docker.
- **Abordagem:** configuração transversal em `crm_data.shared`; nenhuma regra de negócio.

## 2. Dados e estado
- **Banco:** nenhuma tabela.
- **Configuração (propriedades):** `server.port=${PORT:8080}`, `server.forward-headers-strategy=native`, `spring.jpa.hibernate.ddl-auto=validate`, `management.endpoints.web.exposure.include=health` (prod), `management.endpoint.health.probes.enabled=true`.
- **Validação de startup:** componente que checa as variáveis obrigatórias do perfil `prod` (lista no contract) e aborta com mensagem listando as ausentes (sem imprimir valores).

## 3. Componentes
| Componente | Responsabilidade |
|---|---|
| `GlobalExceptionHandler` (`shared/error`) | Mapeia exceções para `ProblemDetail`; tipos `validation`, `unauthorized`, `forbidden`, `not-found`, `conflict`, `business-rule`, `rate-limited`, `internal`, `unavailable` |
| `BusinessException` + `ProblemTypes` | Exceção de domínio com tipo e status; evita usar `RuntimeException` genérica |
| `RequestIdFilter` | Lê `X-Request-Id` (se válido, ≤ 64 caracteres alfanuméricos/hífen) ou gera UUID; coloca no MDC e na resposta |
| `CorsConfig` | Origens vindas de `CRM_CORS_ORIGINS`; métodos `GET, POST, PUT, PATCH, DELETE, OPTIONS`; cabeçalhos `Authorization, Content-Type, X-Request-Id`; sem `*` |
| `SecurityHeadersConfig` | `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Strict-Transport-Security` (prod), `Cache-Control: no-store` em `/api/v1/auth/**` |
| `StartupConfigValidator` | Fail-fast de variáveis obrigatórias |
| `FileStorage` (interface) | `save`, `load`, `delete` por chave com prefixo de tenant; sem implementação nesta feature |

## 4. Lógica
- **Tratamento de erro:** exceção → handler → `ProblemDetail` com `type`, `title`, `status`, `detail`, `instance`; validação adiciona `errors[]`. Exceções inesperadas são logadas com o `X-Request-Id` e respondem 500 `internal` sem detalhe.
- **Build:** Maven com `maven-failsafe-plugin` para `*IT`; `spring-boot-maven-plugin` com `build-info`.
- **Docker:** build multi-stage (Maven + JDK 21 → JRE 21), `USER` não-root, `HEALTHCHECK` chamando `/actuator/health/liveness` (não consulta o banco, para o contêiner não ser reiniciado por falha do banco). `/actuator/health` continua público, para o health check do Render, e `/actuator/health/readiness` inclui o banco.
- **docker-compose (dev):** `app` + `postgres:16` com volume, healthcheck e `depends_on: service_healthy`; variáveis do `.env`. O Postgres local aplica `database/bootstrap` e `database/migrations` (função banco) na subida; as senhas dos papéis locais vêm do `.env`.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Variável obrigatória ausente (prod) | Aplicação não sobe; mensagem lista os nomes |
| `X-Request-Id` inválido ou longo | Ignorado; gera novo UUID |
| Origem CORS não permitida | Requisição bloqueada pelo navegador; sem cabeçalhos CORS na resposta |
| Corpo JSON malformado | 400 `validation` |
| Rota inexistente | 404 `not-found` |
| Método não suportado | 405 em `ProblemDetail` |

## 6. Segurança e desempenho
- Nenhum segredo no repositório; `.env` no `.gitignore`.
- Tempo de startup ≤ 60 s no container; imagem final sem Maven nem JDK.
- OpenAPI e Actuator extra apenas fora de `prod`.
