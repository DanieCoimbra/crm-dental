# Spec — F04 Autenticação

Complementa `docs/global/backend/spec.md` (seções 2, 4.1 a 4.4, 4.8 e 5).

## 1. Visão técnica
- **Feature:** F04 Autenticação.
- **Stack:** Spring Security (stateless), `oauth2-resource-server` (Nimbus `JwtEncoder`/`JwtDecoder`, HS256), BCrypt, Bucket4j, JPA.
- **Abordagem:** módulo `crm_data.auth` (`web`, `service`, `domain`, `repository`) + `shared/security`.

## 2. Dados

### Objetos de banco (responsabilidade da função banco)
Esta feature **não cria migrations**. Consome, conforme o contrato da seção 2 do spec global:
- Tabelas `clinics`, `users` e `refresh_tokens`, com RLS.
- Funções de bootstrap `core.find_user_for_login(varchar)` e `core.find_refresh_token(varchar)` (nomes definitivos; contrato em `docs/features/banco/F04-bootstrap-functions/contract.md`).
- A limpeza de refresh tokens vencidos **não** é do backend: roda no workflow de manutenção do banco.
- O mapeamento JPA deve bater com o schema (`ddl-auto=validate`).

### Estado em memória
Buckets do rate limiter por chave (IP e IP+e-mail), com expiração.

## 3. Componentes
| Componente | Responsabilidade |
|---|---|
| `AuthController` | `register`, `login`, `refresh`, `logout`; apenas DTOs |
| `RegistrationService` | Fluxo da seção 4.1 do spec global |
| `LoginService` | Login com comparação de tempo constante |
| `RefreshTokenService` | Emissão, rotação, detecção de reuso, revogação |
| `JwtService` | Emite o access token e valida o decoder (`iss`, `exp`, algoritmo HS256 fixo) |
| `LoginRateLimiter` | Limites por IP e IP+e-mail |
| `User`, `Clinic`, `RefreshToken` | Entidades (`User` e `RefreshToken` estendem `TenantAwareEntity`; `Clinic` tem regra própria de tenant) |
| `UserRepository`, `ClinicRepository`, `RefreshTokenRepository`, `AuthLookupRepository` | `AuthLookupRepository` chama as funções de bootstrap com `JdbcClient`/`JdbcTemplate` **fora de transação JPA** (toda transação JPA exige tenant e falha com 401 sem ele; ver F02). O refresh dentro de transação usa `TenantRunner` com o `clinic_id` do token |
| `SecurityConfig` | Cadeia stateless; rotas públicas; conversão de claims em `TokenPrincipal` |

## 4. Lógica
Segue literalmente as seções 4.1 (registro), 4.2 (login), 4.3 (refresh) e 4.4 (logout) do spec global. Pontos de implementação:
- **Claims:** `iss=crm-clinica`, `sub`, `clinic_id`, `role`, `jti`, `iat`, `exp` (+15 min). O decoder rejeita qualquer algoritmo diferente de HS256.
- **Refresh:** 32 bytes de `SecureRandom` em base64url; guarda SHA-256 hexadecimal.
- **Normalização:** e-mail com `trim` e minúsculas antes de qualquer busca ou gravação.
- **Tempo constante:** e-mail inexistente executa BCrypt contra hash fictício de mesmo custo.
- **Reuso:** `used_at` preenchido → `UPDATE refresh_tokens SET revoked_at = now() WHERE family_id = ?` (dentro de transação com o tenant do token) e 401.
- **Chaves de rate limit:** IP vem de `X-Forwarded-For` confiável (`forward-headers-strategy=native`).

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Registros simultâneos com o mesmo e-mail | `ux_users_email` decide; perdedor recebe 409 |
| Dois refresh simultâneos com o mesmo token | `UPDATE ... WHERE used_at IS NULL` atômico; o segundo é tratado como reuso |
| Token com `alg` diferente de HS256 / `none` | 401 |
| Usuário inativado com access token ainda válido | O access vale até expirar (≤ 15 min); refresh bloqueado |
| Falha ao gravar o refresh após criar a clínica | Transação única: nada é persistido |
| Rate limit excedido | 429 `rate-limited` com `Retry-After` |
| Senha > 72 bytes | Bloqueada na validação (máx. 64 caracteres) |

## 6. Segurança e desempenho
- Respostas de auth com `Cache-Control: no-store`.
- Logs: nunca senha, token, hash ou e-mail completo em eventos de falha (usar prefixo mascarado).
- BCrypt custo 12 (decisão mantida em 2026-10-19). Reduzir o custo não resolve a meta antiga de 50 concorrentes (custo 10 ainda dá p95 ~420 ms), então a meta foi redefinida para até 4 concorrentes. O custo é configurável por `crm.auth.bcrypt-strength`. O `LoginPerformanceIT` usa limite de 300 ms localmente e 600 ms quando a variável `CI` está definida (runner de 4 CPUs; decisão de 2026-10-20); `-Dperf.limit-ms` sobrescreve.
- Índices únicos garantem busca por e-mail e hash em tempo constante.
