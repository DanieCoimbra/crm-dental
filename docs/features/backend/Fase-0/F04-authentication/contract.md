# Contract — F04 Autenticação

## 1. Resumo
- **Feature:** F04-authentication
- **Versão:** 1.0.0
- **Consumidores:** Frontend React, F05 e todas as features autenticadas (consomem o token e o `TenantContext`).

## 2. Entradas

Prefixo `/api/v1/auth`. Todas as rotas são públicas. Corpo `application/json`.

### `POST /register`
```json
{
  "clinicName": "string (2-150, required)",
  "name": "string (2-120, required)",
  "email": "string (email, <=254, required)",
  "password": "string (10-64, >=1 letra e >=1 número, required)"
}
```
### `POST /login`
```json
{ "email": "string (email, required)", "password": "string (required)" }
```
### `POST /refresh` e `POST /logout`
```json
{ "refreshToken": "string (base64url, 43 caracteres, required)" }
```
Nenhum corpo aceita `clinicId`, `role` ou `id`; campos extras são ignorados.

## 3. Saídas

### Sucesso de `register` (201), `login` (200) e `refresh` (200)
```json
{
  "accessToken": "string (JWT)",
  "refreshToken": "string (opaco)",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "user": { "id": "uuid", "name": "string", "email": "string", "role": "DENTIST_ADMIN | RECEPTIONIST", "clinicId": "uuid" }
}
```
`logout` retorna `204` sem corpo.

### Claims do access token (HS256)
| Claim | Tipo | Valor |
|---|---|---|
| `iss` | string | `crm-clinica` |
| `sub` | uuid | id do usuário |
| `clinic_id` | uuid | tenant |
| `role` | string | `DENTIST_ADMIN` ou `RECEPTIONIST` |
| `jti` | uuid | id do token |
| `iat`, `exp` | número | `exp = iat + 900 s` |

### Erros (formato da F01)
| Rota | Status |
|---|---|
| register | 400 `validation`, 409 `conflict`, 429 `rate-limited` |
| login | 400, 401 `unauthorized` (mensagem genérica), 429 |
| refresh | 400, 401 (inexistente, expirado, revogado, reuso ou usuário inativo), 429 |
| logout | 400 |

### Contrato interno oferecido às features
- Cabeçalho `Authorization: Bearer <accessToken>` em todas as rotas fora de `/api/v1/auth/**` e do health.
- `TokenPrincipal { userId, clinicId, role }` disponível por `TokenPrincipal.current()` (lido do `TenantContext`, preenchido a partir do access token). O `principal` do `SecurityContext` continua sendo o `Jwt`.
- Erro de autenticação: 401; papel insuficiente: 403 (ambos `ProblemDetail`).

## 4. Regras e limites
- Access token: 15 min. Refresh: 7 dias, uso único, rotativo.
- Reuso de refresh revoga a cadeia inteira do usuário naquela família.
- E-mail único global, salvo em minúsculas. Um `DENTIST_ADMIN` por clínica.
- Limites: login 5 falhas/min por IP+e-mail e 20 falhas a cada 15 min por e-mail somando só IPs que nunca entraram nessa conta (IP com login bem-sucedido nos últimos 7 dias fica fora dessa soma; zerado no login bem-sucedido); registro, login e refresh 20 requisições/min por IP (cada ação tem o seu contador); logout sem limite; excedido → 429 com `Retry-After`. O limite conta também requisições com corpo inválido e vale antes da validação.
- Concorrência de hash: no máximo `max(2, CPUs)` comparações/geração de BCrypt simultâneas (login e registro). Sem vaga em 3 s → 429 `rate-limited` com `Retry-After: 3`; essa falha não conta como tentativa de login errada. Contrapartida do limite por e-mail: quem entra de um IP novo durante um ataque àquela conta recebe 429 por até 15 min; o dono, do IP de sempre, não é afetado (estado em memória: perdido no restart).
- Resposta de login idêntica para e-mail inexistente, senha errada e usuário inativo.
- Não existe trial, plano ou expiração de conta.
- Senha nunca é registrada em log nem retornada.

## 5. Eventos emitidos (internos, em memória)
| Evento | Gatilho | Payload |
|---|---|---|
| `Auth.ClinicRegistered` | Registro concluído | `{clinicId, userId}` |
| `Auth.RefreshReuseDetected` | Reuso de refresh | `{userId, familyId}` (para auditoria; sem token) |
| `Auth.UserSessionsRevoked` | Troca de senha, desativação ou reset (acionado pela F05) | `{userId}` |

## 6. Critérios de aceite (integração)
- [ ] Registro cria clínica e dentista; segundo registro com o mesmo e-mail retorna 409.
- [ ] Corpo com `clinicId`/`role` extras não altera o resultado.
- [ ] Login com e-mail inexistente, senha errada e usuário inativo retornam o mesmo 401.
- [ ] 6ª falha em 1 min retorna 429 com `Retry-After`.
- [ ] 21ª falha em 15 min contra o mesmo e-mail, vinda de IPs que nunca entraram na conta, retorna 429; o IP que já entrou nela nos últimos 7 dias continua logando.
- [ ] Com todas as vagas de hash ocupadas por mais de 3 s, login e registro retornam 429 sem contar falha de login.
- [ ] Refresh troca o par; reapresentar o antigo retorna 401 e invalida o novo.
- [ ] Logout invalida o refresh; repetir o logout retorna 204.
- [ ] Token expirado, adulterado ou `alg: none` retorna 401 `ProblemDetail`.
- [ ] Rota autenticada sem token retorna 401; com papel insuficiente, 403.
- [ ] Dois registros simultâneos com o mesmo e-mail: um 201 e um 409.
- [ ] Login p95 ≤ 300 ms com até 4 requisições concorrentes (BCrypt custo 12). Decisão registrada em 2026-10-19: BCrypt custo 12 mantido; a meta deixa de ser 50 concorrentes porque o hash é limitado por CPU (medido em 12 CPUs: 1 login 243 ms; 4 concorrentes p95 295 ms; 50 concorrentes p95 ~1,5 s; custo 11 → 0,8 s e custo 10 → 0,42 s, ainda acima de 300 ms). Decisão de 2026-10-20: a meta de 300 ms vale para o ambiente de referência (12 CPUs); no CI (runner do GitHub, 4 CPUs, p95 medido 387 ms) o `LoginPerformanceIT` usa o limite de 600 ms, ativado pela variável `CI` e sobrescrevível por `-Dperf.limit-ms`.
