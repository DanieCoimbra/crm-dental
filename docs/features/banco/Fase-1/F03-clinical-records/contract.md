# Contract — F03 Prontuário Clínico Cifrado (Fase 1)

## 1. Resumo
- **Feature:** F03-clinical-records (Fase 1)
- **Versão:** 1.0.0
- **Consumidores:** backend (módulo `record`; `EncryptedStringConverter`); F05; F06; `crm_backup`.

## 2. Entradas

Somente com `app.role = DENTIST_ADMIN`, `app.clinic_id` e `app.user_id` definidos.

| Tabela | Coluna | Tipo | Regra |
|---|---|---|---|
| ambas | `id`, `clinic_id`, `patient_id`, `created_by` | `uuid` | `patient_id` da mesma clínica; `created_by = app.user_id`; todos imutáveis (e `created_at`) |
| ambas | `content` | `varchar(54000)` (histórico) / `varchar(27000)` (nota) | Base64 de `versão[1] \| IV[12] \| cifra + tag[16]`; ≥ 40 caracteres; sem espaços |
| `medical_histories` | `patient_id` | `uuid` | Único |
| `clinical_notes` | `noted_at` | `timestamptz` | Padrão `now()` |
| ambas | `version` | `bigint` | `@Version` |

O backend limita o texto claro a 10.000 caracteres (histórico) e 5.000 (nota).

## 3. Saídas
Todas as colunas, incluindo `created_at` e `updated_at`.

### Erros
| SQLSTATE | Causa | Resposta sugerida |
|---|---|---|
| `23505` (`ux_histories_patient`) | Histórico já existe | 409 (editar o existente) |
| `23514` | Conteúdo fora do formato / coluna imutável | 500 (bug de cifra) / 422 |
| `22001` | Conteúdo maior que a coluna | 422 |
| `23503` | Paciente ou usuário de outra clínica | 404/500 |
| `42501` | Papel sem permissão ou autoria falsa | 403 |

## 4. Regras e limites
- RLS `ENABLE` + `FORCE`; `SELECT`/`INSERT`/`UPDATE` só para `DENTIST_ADMIN` da própria clínica; `DELETE` negado.
- O banco não decifra, não valida a autenticidade e não indexa o conteúdo.
- Sem pesquisa por conteúdo; só por paciente e data.
- `crm_backup`: `SELECT` com política nomeada.
- Políticas do `crm_definer` nestas tabelas: F06.

## 5. Eventos emitidos
Nenhum no banco (auditoria pela F05).

## 6. Critérios de aceite (integração)
- [ ] Testes 29 a 31 (parte): isolamento, sem tenant e FK composta.
- [ ] Teste 32: `RECEPTIONIST` lê 0 linhas e não grava em histórico e nota.
- [ ] Teste 35 (parte): `app.role` inválido nega.
- [ ] Teste 36 (parte): `created_by` falso falha.
- [ ] Teste 40: texto em claro e conteúdo curto falham; Base64 válido passa; segundo histórico falha.
- [ ] Teste 42 (parte): imutáveis e `updated_at`.
- [ ] Teste 52 (parte): `crm_app` sem `DELETE`.
- [ ] Teste 55 (parte): linha do tempo sem `Seq Scan`.
