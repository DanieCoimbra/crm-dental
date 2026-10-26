# Contract — F06 Auditoria do comercial e financeiro (Fase 3)

## 1. Resumo
- **Feature:** F06-commercial-audit (Fase 3)
- **Versão:** 1.0.0
- **Consumidores:** backend (consulta somente leitura, `DENTIST_ADMIN`); F07 (as limpezas geram linhas); `crm_backup`.

## 2. Entradas

O backend **não escreve** em `audit_log`. A escrita é efeito de gravações em `budgets`, `installments`, `payments` e `budget_documents`, que exigem `app.clinic_id`, `app.role` e **`app.user_id`** (uuid de usuário da mesma clínica).

Leitura: `SELECT` por `crm_app` com `app.role = DENTIST_ADMIN` (inalterado).

## 3. Saídas

As colunas de `audit_log` não mudam (contrato da F05 da Fase 1). Novos valores de `table_name`: `budgets`, `installments`, `payments`, `budget_documents`.

Para cada `INSERT`, `UPDATE` ou `DELETE` nessas tabelas: uma linha com `record_id` = id do registro e `action` = `INSERT` | `UPDATE` | `DELETE`.

### Erros
| SQLSTATE | Causa | Resposta sugerida |
|---|---|---|
| `28000` | `app.user_id` ausente numa gravação das tabelas auditadas | 500 (bug do aspecto de sessão) |
| `23503` | `app.user_id` de outra clínica | 500 |

## 4. Regras e limites
- O log **não** guarda valor monetário, status, `storage_key` nem o novo status: diz quem e quando.
- **Não auditados:** `procedure_types`, `budget_items`.
- Efeitos automáticos aparecem como linhas próprias: `UPDATE` em `budgets` a cada mudança de item (recálculo do total) e `UPDATE` em `installments` quando um pagamento quita ou um estorno reabre a parcela.
- Continua append-only e legível só pelo dentista; as regras de F05 (Fase 1) valem sem mudança.
- Histórico de um registro: `WHERE clinic_id = ? AND table_name = ? AND record_id = ?` (`ix_audit_record`).

## 5. Eventos emitidos
Esta feature é a trilha; não emite eventos adicionais.

## 6. Critérios de aceite (integração)
- [ ] Teste 111: cada `INSERT/UPDATE` nas quatro tabelas gera exatamente 1 linha com o `table_name` certo; sem `app.user_id`, falha com `28000` e nada persiste; `procedure_types` e `budget_items` não geram linhas; os efeitos em cascata geram suas linhas; nome fora da lista continua rejeitado.
- [ ] Teste 45 (Fase 1, reexecutado): `crm_app` não escreve em `audit_log`; o dono não consegue `UPDATE/DELETE`.
- [ ] Teste 117 (parte): V028 aplica do zero; `U028` reverte e reaplica.
- [ ] Teste 115 (parte): `write_audit()` com `search_path` fixo, dono `crm_definer`, sem `EXECUTE` para `PUBLIC`, e a concessão temporária ao `crm_owner` revogada ao fim.
