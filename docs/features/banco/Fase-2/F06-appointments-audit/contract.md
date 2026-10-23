# Contract — F06 Auditoria dos agendamentos (Fase 2)

## 1. Resumo
- **Feature:** F06-appointments-audit (Fase 2)
- **Versão:** 1.0.0
- **Consumidores:** backend (consulta somente leitura, `DENTIST_ADMIN`); F07 (as limpezas de `notes` geram linhas); `crm_backup`.

## 2. Entradas

O backend **não escreve** em `audit_log`. A escrita é efeito de gravações em `appointments`, que exigem `app.clinic_id`, `app.role` e **`app.user_id`** (uuid de usuário da mesma clínica).

Leitura: `SELECT` por `crm_app` com `app.role = DENTIST_ADMIN` (inalterado).

## 3. Saídas

As colunas de `audit_log` não mudam (ver contrato da F05 da Fase 1). Novidade:

| Coluna | Novo valor possível |
|---|---|
| `table_name` | `appointments` |

Para cada `INSERT`, `UPDATE` ou `DELETE` em `appointments`: uma linha com `record_id` = id do agendamento e `action` = `INSERT` | `UPDATE` | `DELETE`.

### Erros
| SQLSTATE | Causa | Resposta sugerida |
|---|---|---|
| `28000` | `app.user_id` ausente numa gravação de agendamento | 500 (bug do aspecto de sessão) |
| `23503` | `app.user_id` de outra clínica | 500 |

## 4. Regras e limites
- O log **não** guarda horário, status, `notes` nem o novo status: diz quem e quando, não o quê.
- Auditados: `INSERT/UPDATE/DELETE` em `appointments`. **Não auditados:** `business_hours`, `clinic_holidays`, `holidays`.
- Cancelamento, confirmação e reativação são `UPDATE` e aparecem como `UPDATE`; a consulta por registro usa `ix_audit_record (clinic_id, table_name, record_id, occurred_at DESC)`.
- Continua append-only e legível só pelo dentista; as regras de F05 (Fase 1) valem sem mudança.
- Consulta ao histórico de um agendamento: `WHERE clinic_id = ? AND table_name = 'appointments' AND record_id = ?`.

## 5. Eventos emitidos
Esta feature é a trilha; não emite eventos adicionais.

## 6. Critérios de aceite (integração)
- [ ] Teste 76: cada `INSERT/UPDATE` em `appointments` gera exatamente 1 linha com `table_name = 'appointments'`; sem `app.user_id` falha com `28000` e nada persiste; `audit_log` ainda rejeita nome de tabela fora da lista; `business_hours`, `clinic_holidays` e `holidays` não geram linhas.
- [ ] Teste 45 (Fase 1, reexecutado): `crm_app` não escreve em `audit_log`; o dono não consegue `UPDATE/DELETE`.
- [ ] Teste 82 (parte): V020 aplica do zero; `U020` reverte e reaplica.
- [ ] Teste 80 (parte): `write_audit()` com `search_path` fixo, dono `crm_definer`, sem `EXECUTE` para `PUBLIC` (inalterado).
