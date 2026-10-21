# Contract — F02 Pacientes (Fase 1)

## 1. Resumo
- **Feature:** F02-patients (Fase 1)
- **Versão:** 1.0.0
- **Consumidores:** backend (módulo `patient`, entidade JPA `Patient`); F03–F06 (FK composta); `crm_backup`.

## 2. Entradas

Transação com `app.clinic_id`, `app.role` ∈ {`DENTIST_ADMIN`, `RECEPTIONIST`} e `app.user_id`.

| Coluna | Tipo | Regra de escrita |
|---|---|---|
| `id` | `uuid` | Gerado pela aplicação; imutável |
| `clinic_id` | `uuid` | Igual ao tenant; imutável |
| `name` | `varchar(120)` | ≥ 2 caracteres úteis |
| `cpf` | `varchar(11)` | Opcional, 11 dígitos, único na clínica |
| `birth_date` | `date` | Obrigatória, ≥ 1900-01-01 (data futura: backend) |
| `sex` | `varchar(15)` | `FEMALE`, `MALE`, `OTHER`, `NOT_INFORMED` |
| `profession` | `varchar(100)` | Opcional |
| `phone` | `varchar(20)` | Obrigatório, 10 a 13 dígitos |
| `email` | `varchar(254)` | Opcional, minúsculas |
| `address` | `varchar(255)` | Opcional |
| `insurance_name` / `insurance_number` | `varchar(100)` / `varchar(40)` | Número exige nome |
| `acquaintance_name` / `acquaintance_phone` | `varchar(120)` / `varchar(20)` | Ambos ou nenhum; telefone 10–13 dígitos |
| `active` / `deactivated_at` | `boolean` / `timestamptz` | Coerentes entre si |
| `created_by` | `uuid` | = `app.user_id`; imutável |
| `version` | `bigint` | `@Version` |

`anonymized_at` não é gravável pelo `crm_app` (mapear `insertable = false, updatable = false`).

## 3. Saídas
Todas as colunas acima mais `anonymized_at`, `created_at`, `updated_at`.

### Erros
| SQLSTATE | Causa | Resposta sugerida do backend |
|---|---|---|
| `23505` (`ux_patients_clinic_cpf`) | CPF já cadastrado na clínica | 409, sem revelar o outro paciente |
| `23514` | `CHECK` (formato, coerência, imutável) | 422 |
| `23503` | `created_by` inexistente na clínica | 500 (bug) |
| `42501` | Autoria falsa, coluna sem grant | 403/500 |

## 4. Regras e limites
- `ENABLE` + `FORCE` RLS; sem `DELETE` para `crm_app`.
- Leitura/escrita: dentista e recepcionista da própria clínica.
- `crm_app`: `SELECT`; `INSERT` e `UPDATE` por coluna; `crm_backup`: `SELECT`.
- Imutáveis: `id`, `clinic_id`, `created_by`, `created_at`. Como ficam fora do `GRANT UPDATE` por coluna, o backend **deve** mapeá-las `updatable = false` (e `anonymized_at` com `insertable = false, updatable = false`); um `UPDATE` do Hibernate que as inclua falha com `permission denied`.
- Índice `ix_patients_created_by (created_by, clinic_id)` cobre a FK composta de autoria.
- Listagem por keyset `(name, id)`; busca por trecho a partir de 3 caracteres (senão prefixo); sem busca insensível a acento.

## 5. Eventos emitidos
Nenhum no banco (a auditoria vem da F05).

## 6. Critérios de aceite (integração)
- [ ] Testes 29 a 31 (parte): isolamento, sem tenant e FK composta em `patients`.
- [ ] Teste 34: recepção lê, insere e altera.
- [ ] Teste 35 (parte): `app.role` inválido nega tudo.
- [ ] Teste 36 (parte): `created_by` falso falha.
- [ ] Testes 37 a 39 e 42 (parte `patients`): CPF, formatos, coerências, imutáveis, `updated_at`.
- [ ] Teste 52 (parte): `crm_app` sem `DELETE`/`DROP`/`TRUNCATE`; sem grant em `anonymized_at`.
- [ ] Teste 55 (parte): planos de listagem, busca por nome e por CPF sem `Seq Scan`.
