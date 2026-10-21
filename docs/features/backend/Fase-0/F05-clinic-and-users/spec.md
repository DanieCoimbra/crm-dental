# Spec — F05 Clínica e Usuários

Complementa `docs/global/backend/spec.md` (seções 4.5 e 5).

## 1. Visão técnica
- **Feature:** F05 Clínica e Usuários.
- **Stack:** Spring Web MVC, Spring Data JPA, Bean Validation, Spring Security (`@PreAuthorize`), BCrypt.
- **Abordagem:** módulos `crm_data.clinic` (clínica e usuários) e `crm_data.auth` (perfil `/me`, reutilizando serviços da F04). Sem migrations: usa as tabelas definidas pela função banco.

## 2. Dados
- **Tabelas usadas:** `clinics`, `users`, `refresh_tokens` (função banco). Nenhuma alteração de schema.
- **Dados de saída:** nunca devolver `password_hash`.
- **Estado em memória:** nenhum.

## 3. Componentes
| Componente | Responsabilidade |
|---|---|
| `MeController` | `GET/PUT /me`, `PUT /me/password` |
| `ClinicController` | `GET/PUT /clinic` |
| `UserController` | `GET/POST /users`, `PATCH /users/{id}/active`, `PUT /users/{id}/password` |
| `ProfileService` | Edição de nome/e-mail e troca de senha do usuário do token |
| `ClinicService` | Leitura e edição da clínica do tenant |
| `UserAdminService` | Criação, listagem, ativação e reset de senha, com as regras de papel |
| `RefreshTokenService.revokeAllOfUser` (da F04) | Revoga todos os refresh tokens do usuário dentro da transação do chamador (`MANDATORY`) e emite `Auth.UserSessionsRevoked` |
| DTOs (`records`) | Request/response separados das entidades; sem `clinicId`/`role` de entrada |

## 4. Lógica
- **Editar perfil:** normaliza e-mail; atualiza `name`/`email` do usuário do token; conflito de unicidade → 409.
- **Trocar senha:** confere a atual com BCrypt (401 se errada); rejeita nova igual à atual (422); grava novo hash; revoga todos os refresh tokens do usuário.
- **Editar clínica:** carrega a clínica do tenant (RLS + filtro); atualiza campos; `@Version` protege edição concorrente.
- **Criar recepcionista:** `@PreAuthorize` dentista; força `role = RECEPTIONIST` e `clinic_id` do contexto; BCrypt custo 12; `created_by` = dentista.
- **Listar usuários:** página ordenada por nome, `size` ≤ 50, dentro do tenant.
- **Ativar/desativar:** busca `{id}` no tenant (404 se não achar); 422 se for o próprio usuário ou o dentista; ao desativar, revoga refresh tokens.
- **Resetar senha:** só alvo `RECEPTIONIST`; novo hash; revoga refresh tokens do alvo.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| `{id}` de outra clínica | 404 (RLS + filtro) |
| E-mail duplicado | 409 |
| Recepcionista chama rota de dentista | 403 |
| Dentista tenta desativar a si mesmo ou outro dentista | 422 `business-rule` |
| Reset de senha de um dentista | 422 |
| Edição concorrente | 409 por `@Version` |
| Senha nova fora da política | 400 com `errors[]` |
| Corpo com `clinicId`/`role`/`id` | Ignorados |

## 6. Segurança e desempenho
- Autorização por rota (`SecurityConfig`) e `@PreAuthorize` no service.
- `password_hash` nunca em DTO, log ou exceção.
- Listagem paginada com índice `(clinic_id)`; resposta ≤ 100 ms para 50 usuários.
