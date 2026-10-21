# Contract — F05 Clínica e Usuários

## 1. Resumo
- **Feature:** F05-clinic-and-users
- **Versão:** 1.0.0
- **Consumidores:** Frontend React (configurações, perfil, gestão de equipe). Requer `Authorization: Bearer` emitido pela F04.

## 2. Entradas

Prefixo `/api/v1`. Papéis: **D** = `DENTIST_ADMIN`, **R** = `RECEPTIONIST`.

| Método | Rota | Papéis | Corpo / query |
|---|---|---|---|
| GET | `/me` | D, R | — |
| PUT | `/me` | D, R | `{ "name": "string (2-120)", "email": "string (email, <=254)" }` |
| PUT | `/me/password` | D, R | `{ "currentPassword": "string", "newPassword": "string (10-64, letra e número)" }` |
| GET | `/clinic` | D, R | — |
| PUT | `/clinic` | D | `{ "name": "string (2-150, required)", "document": "string (<=20, optional)", "phone": "string (<=20, optional)", "email": "string (email, optional)", "address": "string (<=255, optional)", "version": "integer (required)" }` |
| GET | `/users` | D | `?page=0&size=20` (`size` 1–50) |
| POST | `/users` | D | `{ "name": "string (2-120)", "email": "string (email)", "password": "string (10-64, letra e número)" }` |
| PATCH | `/users/{id}/active` | D | `{ "active": "boolean (required)" }` |
| PUT | `/users/{id}/password` | D | `{ "newPassword": "string (10-64, letra e número)" }` |

`{id}` é uuid. Nenhum corpo aceita `clinicId`, `role` ou `id`.

## 3. Saídas

### Sucesso
- **Usuário:** `{ "id": "uuid", "name": "string", "email": "string", "role": "DENTIST_ADMIN|RECEPTIONIST", "active": true, "clinicId": "uuid" }` (200; 201 em `POST /users`).
- **Clínica:** `{ "id": "uuid", "name": "string", "document": "string|null", "phone": "string|null", "email": "string|null", "address": "string|null", "version": 0 }`.
- **Página de usuários:** `{ "content": [Usuário], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0 }`.
- `PUT /me/password` e `PUT /users/{id}/password` retornam `204`.

### Erros (formato da F01)
| Status | Quando |
|---|---|
| 400 `validation` | Campo inválido ou `size` fora de 1–50 |
| 401 `unauthorized` | Token ausente/inválido; senha atual errada em `/me/password` |
| 403 `forbidden` | Papel insuficiente |
| 404 `not-found` | `{id}` inexistente ou de outra clínica |
| 409 `conflict` | E-mail já usado; `version` desatualizada |
| 422 `business-rule` | Desativar a si mesmo ou o dentista; reset de senha de dentista; nova senha igual à atual |

## 4. Regras e limites
- `POST /users` cria sempre `RECEPTIONIST`; há no máximo um `DENTIST_ADMIN` por clínica.
- O dentista não pode ser desativado nem ter a senha redefinida por esta feature.
- Desativar usuário, trocar senha e redefinir senha revogam os refresh tokens do usuário afetado.
- O `clinic_id` vem apenas do token; ids de rota só valem dentro do tenant.
- `password_hash` nunca é retornado.
- Paginação: `size` máximo 50, ordenação por nome.

## 5. Eventos emitidos (internos)
| Evento | Gatilho | Payload |
|---|---|---|
| `Auth.UserSessionsRevoked` | Troca de senha, desativação ou reset | `{userId}` |
| `Clinic.Updated` | Edição da clínica | `{clinicId}` |
| `User.Created` | Criação de recepcionista | `{userId, clinicId}` |

## 6. Critérios de aceite (integração)
- [ ] Matriz papel × endpoint: R recebe 403 em `PUT /clinic` e em todas as rotas `/users`.
- [ ] `PATCH /users/{id}/active` e `PUT /users/{id}/password` com id de outra clínica retornam 404.
- [ ] Criar recepcionista com e-mail existente retorna 409; corpo com `role: DENTIST_ADMIN` é ignorado.
- [ ] Dentista desativando a si mesmo retorna 422.
- [ ] Usuário desativado não consegue login nem refresh.
- [ ] Troca de senha com a atual errada retorna 401; com a nova igual à atual, 422.
- [ ] Após reset de senha, o refresh antigo do alvo retorna 401.
- [ ] `PUT /clinic` com `version` desatualizada retorna 409.
- [ ] Nenhuma resposta contém `password_hash`.
