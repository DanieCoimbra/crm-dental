# Contract — F01 Platform Foundation

## 1. Resumo
- **Feature:** F01-platform-foundation
- **Versão:** 1.0.0
- **Consumidores:** todas as features de backend (F02–F05 e fases 1–4), Frontend React, operação (Render, Docker).

## 2. Entradas

### 2.1 Variáveis de ambiente
| Variável | Tipo | Obrigatória em `prod` | Regra |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `dev` \| `test` \| `prod` | sim | |
| `PORT` | inteiro 1–65535 | não (padrão 8080) | |
| `DB_URL` | URL JDBC | sim | TLS exigido em `prod` |
| `DB_APP_USER`, `DB_APP_PASSWORD` | string | sim | Papel de aplicação do banco |
| `CRM_JWT_SECRET` | string ≥ 32 bytes | sim | Validado pela F04 |
| `CRM_AES_KEYS`, `CRM_AES_ACTIVE_VERSION` | string | sim | Validado pela F03 |
| `CRM_CORS_ORIGINS` | lista separada por vírgula de origens `https://...` | sim | Sem `*` |
| `CRM_STORAGE_URL`, `CRM_STORAGE_SERVICE_KEY`, `CRM_STORAGE_BUCKET` | URL, string, string | não até a Fase 1 | Supabase Storage (bucket privado); a service key só no backend |

### 2.2 Cabeçalho de requisição
- `X-Request-Id` (opcional): `^[A-Za-z0-9-]{1,64}$`.

## 3. Saídas

### 3.1 Endpoints operacionais
| Método | Rota | Acesso | Resposta |
|---|---|---|---|
| GET | `/actuator/health/liveness` | público | `200 {"status":"UP"}` |
| GET | `/actuator/health/readiness` | público | `200 {"status":"UP"}` ou `503` |

### 3.2 Formato de erro (`application/problem+json`)
```json
{
  "type": "https://crm.local/problems/<slug>",
  "title": "string",
  "status": 400,
  "detail": "string",
  "instance": "/api/v1/...",
  "errors": [{ "field": "string", "message": "string" }]
}
```
`errors` só existe em `validation`. Slugs e status: `validation` 400, `unauthorized` 401, `forbidden` 403, `not-found` 404, `conflict` 409, `business-rule` 422, `rate-limited` 429, `internal` 500, `unavailable` 503.

### 3.3 Cabeçalhos de resposta
`X-Request-Id` em toda resposta; `X-Content-Type-Options: nosniff`; `X-Frame-Options: DENY`; `Cache-Control: no-store` em `/api/v1/auth/**`; `Strict-Transport-Security` em `prod`.

### 3.4 Contratos internos oferecidos às features
- `BusinessException(tipo, mensagem)` — única forma de sinalizar erro de regra; o handler define o status.
- `FileStorage` — interface com `save(chave, conteúdo)`, `load(chave)`, `delete(chave)`; a chave sempre começa com `<clinic_id>/`.

## 4. Regras e limites
- Nenhum erro expõe stack trace, SQL, nome de classe ou mensagem de exceção interna.
- Nenhuma feature define seu próprio formato de erro.
- Em `prod`: OpenAPI desligado; apenas `health` no Actuator.
- Logs não contêm senha, token, hash ou conteúdo clínico.
- CORS nunca usa `*`.

## 5. Eventos emitidos
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] `mvn verify` passa em máquina limpa.
- [ ] Imagem Docker sobe e `/actuator/health/readiness` retorna `UP` em ≤ 60 s.
- [ ] Faltando `CRM_JWT_SECRET` em `prod`, a aplicação aborta e cita o nome da variável.
- [ ] JSON malformado retorna 400 `validation`; rota inexistente retorna 404; ambos em `application/problem+json`.
- [ ] Origem fora de `CRM_CORS_ORIGINS` não recebe cabeçalhos CORS.
- [ ] Toda resposta contém `X-Request-Id`.
- [ ] Em `prod`, `/v3/api-docs` e `/actuator/env` não existem: 404 com token válido e 401 sem token (toda rota fora de `/actuator/health/**` e `/api/v1/auth/**` exige autenticação, então a ausência da rota não é revelada a anônimos).
