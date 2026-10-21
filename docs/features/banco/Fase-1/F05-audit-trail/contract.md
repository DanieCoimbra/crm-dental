# Contract — F05 Trilha de Auditoria (Fase 1)

## 1. Resumo
- **Feature:** F05-audit-trail (Fase 1)
- **Versão:** 1.0.0
- **Consumidores:** backend (consulta somente leitura); F06 (grava `ANONYMIZE`); `crm_backup`.

## 2. Entradas

O backend **não escreve** em `audit_log`. A escrita é efeito de gravações clínicas, que exigem `app.clinic_id`, `app.role`, **`app.user_id`** (uuid de usuário da mesma clínica).

Leitura: `SELECT` por `crm_app` com `app.role = DENTIST_ADMIN`.

## 3. Saídas

| Coluna | Tipo | Significado |
|---|---|---|
| `id` | `bigint` | Ordem de inserção |
| `clinic_id` | `uuid` | Clínica |
| `user_id` | `uuid` | Quem fez |
| `table_name` | `varchar(30)` | `patients`, `medical_histories`, `clinical_notes`, `patient_files` |
| `record_id` | `uuid` | Registro afetado |
| `action` | `varchar(10)` | `INSERT`, `UPDATE`, `DELETE`, `ANONYMIZE` |
| `occurred_at` | `timestamptz` | Instante da transação |

### Erros
| SQLSTATE | Causa | Resposta sugerida |
|---|---|---|
| `28000` | `app.user_id` ausente numa gravação clínica | 500 (bug do aspecto de sessão) |
| `23503` | `app.user_id` de outra clínica | 500 |
| `42501` | Tentativa de escrever/alterar o log | 500 (não deve ocorrer) |

## 4. Regras e limites
- Sem coluna de valores; nenhuma informação pessoal ou clínica.
- Append-only para todos os papéis (trigger de linha em `UPDATE`/`DELETE` e trigger de comando em `TRUNCATE`, inclusive `TRUNCATE ... CASCADE`) e sem grant de escrita para `crm_app`. Só quem desliga o trigger (DDL por dono/superusuário) esvazia o log.
- `SELECT` somente para `DENTIST_ADMIN` da clínica; `crm_backup` lê tudo por política nomeada e tem `SELECT` (só) na sequência da identidade `core.audit_log_id_seq`, necessário ao `pg_dump`. `crm_app` e `crm_definer` não têm privilégio na sequência.
- Consulta por `ix_audit_clinic_time` (recente) e `ix_audit_record` (por registro); paginação por `(occurred_at, id)`.
- Auditoria de leitura não existe.

## 5. Eventos emitidos
Esta feature é a trilha; não emite eventos adicionais.

## 6. Critérios de aceite (integração)
- [ ] Teste 43: cada `INSERT/UPDATE/DELETE` clínico gera exatamente 1 linha; não existe coluna de valor.
- [ ] Teste 44: sem `app.user_id`, a gravação clínica falha (`28000`) e nada persiste.
- [ ] Teste 45: `crm_app` não escreve em `audit_log`; o dono não consegue `UPDATE/DELETE`.
- [ ] Teste 32 (parte): recepção lê 0 linhas de `audit_log`.
- [ ] Teste 29/30 (parte): isolamento por clínica e sem tenant em `audit_log`.
- [ ] Teste 53 (parte): `write_audit()` com `search_path` fixo, dono `crm_definer`, sem `EXECUTE` para `PUBLIC`.
- [ ] Teste 54 (parte): política `SELECT TO crm_backup` presente.
