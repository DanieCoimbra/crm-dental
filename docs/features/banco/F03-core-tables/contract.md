# Contract — F03 Tabelas do Core

## 1. Resumo
- **Feature:** F03-core-tables
- **Versão:** 1.0.0
- **Consumidores:** backend (`F04-authentication`, `F05-clinic-and-users`, mapeamento JPA), F04 e F06 (banco).

## 2. Entradas
Operações de `crm_app` dentro de transação com `app.clinic_id` definido (contrato da F02). `INSERT` sempre informa `id` gerado pela aplicação.

## 3. Saídas — dicionário (schema `core`)

### `clinics`
| Coluna | Tipo | Nulo | Regra |
|---|---|---|---|
| `id` | `uuid` | não | PK |
| `name` | `varchar(150)` | não | `char_length >= 2` |
| `document` | `varchar(20)` | sim | |
| `phone` | `varchar(20)` | sim | |
| `email` | `varchar(254)` | sim | |
| `address` | `varchar(255)` | sim | |
| `created_at` | `timestamptz` | não | `now()`; imutável |
| `updated_at` | `timestamptz` | não | trigger |
| `version` | `bigint` | não | `0` |

### `users`
| Coluna | Tipo | Nulo | Regra |
|---|---|---|---|
| `id` | `uuid` | não | PK; imutável |
| `clinic_id` | `uuid` | não | FK `clinics(id)`; imutável |
| `name` | `varchar(120)` | não | `char_length >= 2` |
| `email` | `varchar(254)` | não | minúsculas; **único global** |
| `password_hash` | `varchar(100)` | não | hash da aplicação |
| `role` | `varchar(20)` | não | `DENTIST_ADMIN` \| `RECEPTIONIST`; imutável |
| `active` | `boolean` | não | `true`; dentista sempre `true` |
| `created_by` | `uuid` | sim | FK `users(id)` `ON DELETE SET NULL` |
| `created_at` | `timestamptz` | não | imutável |
| `updated_at` | `timestamptz` | não | trigger |
| `version` | `bigint` | não | `0` |

### `refresh_tokens`
| Coluna | Tipo | Nulo | Regra |
|---|---|---|---|
| `id` | `uuid` | não | PK; imutável |
| `clinic_id` | `uuid` | não | FK `clinics(id)` `ON DELETE CASCADE`; imutável |
| `user_id` | `uuid` | não | FK composta com `clinic_id`; imutável |
| `family_id` | `uuid` | não | imutável |
| `token_hash` | `varchar(64)` | não | hex minúsculo de 64 caracteres; **único**; imutável |
| `expires_at` | `timestamptz` | não | |
| `used_at` | `timestamptz` | sim | |
| `revoked_at` | `timestamptz` | sim | |
| `created_at` | `timestamptz` | não | imutável |

### Privilégios entregues
| Papel | `clinics` | `users` | `refresh_tokens` |
|---|---|---|---|
| `crm_app` | SELECT, INSERT, UPDATE | SELECT, INSERT, UPDATE | SELECT, INSERT, UPDATE |
| `crm_backup` | SELECT | SELECT | SELECT |
| `crm_definer` | — | (F04) | (F04) |

### Mapeamento JPA esperado
`uuid`→`UUID`; `timestamptz`→`Instant`; `varchar`→`String`; `bigint version`→`@Version Long`; `role`→`@Enumerated(STRING)`; `id`, `clinic_id`, `role`, `created_at` com `updatable = false`; `hibernate.default_schema=core`.

## 4. Regras e limites
- Sem `DELETE` para `crm_app`; sem DDL.
- Um `DENTIST_ADMIN` por clínica; e-mail único global em minúsculas.
- Nenhuma coluna de plano, trial, cobrança ou expiração de conta.
- Toda alteração de coluna/tipo exige nova migration (imutabilidade de migrations).

## 5. Eventos emitidos
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] Testes 1, 2, 3 e 6: leitura, alteração, remoção e inserção cruzadas entre A e B falham ou afetam 0 linhas.
- [ ] Testes 7 a 11: e-mail duplicado/maiúsculo, segundo dentista, dentista inativo, `role` inválido, colunas imutáveis, hash inválido ou duplicado, token de outra clínica.
- [ ] Teste 12: `updated_at` muda a cada `UPDATE`.
- [ ] Teste 18: `crm_app` não faz `DROP`, `TRUNCATE`, `ALTER`, `CREATE` nem `DELETE` em `users`/`clinics`.
- [ ] Teste 22: `crm_owner` vê 0 linhas (prova do `FORCE`).
- [ ] `ddl-auto=validate` do backend passa contra este schema. Do lado do banco, `tests/schema_dictionary.sql` comprova tipos, tamanhos, nulidade, defaults, índices e FKs do dicionário acima; a execução do `validate` com as entidades JPA é verificada na CI do backend (F04 e F05 do backend) usando os scripts de `database/`.
