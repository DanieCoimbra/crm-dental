# Spec — F06 Auditoria dos agendamentos (Fase 2)

Complementa `docs/global/banco/Fase-2/spec.md` (seções 2.3, 2.6, 7 e 10) e a spec da F05 da Fase 1.

## 1. Visão técnica
- **Feature:** extensão da trilha de auditoria a `core.appointments`.
- **Stack:** PL/pgSQL (função existente), migration `V020`.
- **Abordagem:** recriar a constraint de `table_name` e acrescentar um trigger por linha; nada muda na função nem nas políticas.

## 2. Objetos (`V020__core_appointments_audit.sql`, como `crm_owner`, transação única)

```sql
ALTER TABLE core.audit_log DROP CONSTRAINT ck_audit_table;
ALTER TABLE core.audit_log ADD CONSTRAINT ck_audit_table CHECK (
  table_name IN ('patients','medical_histories','clinical_notes','patient_files','appointments'));

CREATE TRIGGER trg_appt_audit
  AFTER INSERT OR UPDATE OR DELETE ON core.appointments
  FOR EACH ROW EXECUTE FUNCTION core.write_audit();
```
- `DROP`/`ADD CONSTRAINT` fazem uma varredura de validação; não disparam `trg_audit_log_append_only` (esse trigger é de linha em `UPDATE`/`DELETE`, e o `ALTER TABLE` não os executa).
- `write_audit()` usa `TG_TABLE_NAME`: o valor `'appointments'` passa pelo novo `CHECK` sem alteração da função.
- Se o `CREATE TRIGGER` falhar por falta de `EXECUTE` do `crm_owner` na função (dono `crm_definer`), usar na V020:
  `GRANT EXECUTE ON FUNCTION core.write_audit() TO crm_owner;` antes do trigger e `REVOKE EXECUTE ... FROM crm_owner;` ao fim.

## 3. Componentes
`V020`, `U020`, `tests/audit_log.sql` (ampliado), entidade `AuditEntry` (`@Immutable`, já existente) do backend.

## 4. Lógica
1. Qualquer `INSERT/UPDATE/DELETE` em `appointments` dispara `write_audit()` depois da linha.
2. A função lê `app.user_id`; se nulo, aborta toda a transação (`28000`).
3. Insere `(clinic_id, user_id, 'appointments', id, TG_OP)` como `crm_definer`, sob a política `WITH CHECK (clinic_id = tenant)`; a FK composta confere o usuário.
4. Dentro de `anonymize_patient` (F07), os `UPDATE`s de `notes` também geram linhas.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Sem `app.user_id` | `28000`; agendamento não é gravado |
| `user_id` de outra clínica | `23503` |
| Linha de outra clínica na sessão | `WITH CHECK` do definer falha (`42501`) |
| `UPDATE` sem mudança de valores | Gera 1 linha (aceito, como na Fase 1) |
| `table_name` fora da lista | `23514` |
| Agenda de configuração (`business_hours` etc.) | Sem trigger, sem linha |
| Recepção consulta o log | 0 linhas (política da Fase 1) |
| Cancelamentos e reativações | Cada um é um `UPDATE` auditado |

## 6. Segurança e desempenho
- Log só com identificadores: id do agendamento e do usuário, ação e instante; sem horário, status ou `notes`.
- Custo: 1 `INSERT` por linha alterada (dezenas por dia).
- A falha de auditoria bloqueia a operação (intencional).
