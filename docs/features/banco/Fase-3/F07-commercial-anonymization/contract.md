# Contract — F07 Anonimização com histórico comercial e financeiro (Fase 3)

## 1. Resumo
- **Feature:** F07-commercial-anonymization (Fase 3)
- **Versão:** 1.0.0
- **Consumidores:** backend (anonimização de paciente e limpeza do Storage); `crm_backup`.

## 2. Entradas

Chamada única, como `crm_app` com `app.role = DENTIST_ADMIN`, `app.clinic_id` e `app.user_id` definidos na transação:

```sql
SELECT core.anonymize_patient(:patient_id);   -- uuid; devolve timestamptz (instante da anonimização)
```
Pré-condições (mantidas das fases anteriores): paciente inativo, da clínica e não anonimizado; retenção vencida; sem agendamento futuro em aberto. **Nova:** sem parcela `OPEN`.

## 3. Saídas

- Retorno: `timestamptz` da anonimização.
- Efeitos, na mesma transação: `budgets.notes` do paciente viram `NULL` (`version + 1`); `budget_documents.deleted_at` preenchido; orçamentos, itens, parcelas e pagamentos intactos; linhas de auditoria (`UPDATE` de cada orçamento limpo e `ANONYMIZE`).

### Erros
| SQLSTATE | Causa | Resposta sugerida |
|---|---|---|
| `CR006` | Parcela `OPEN` do paciente | 409 ("quite ou cancele as parcelas em aberto") |
| `CR004` | Agendamento futuro em aberto | 409 |
| `CR002` | Retenção do prontuário não vencida (`DETAIL` traz a data) | 409 |
| `CR001` / `CR003` | Paciente ativo / já anonimizado | 409 |
| `P0002` | Paciente inexistente na clínica | 404 |
| `42501` / `28000` | Papel diferente de `DENTIST_ADMIN` / sessão sem clínica ou usuário | 403 / 500 |

Precedência: `CR002` → `CR004` → `CR006`.

## 4. Regras e limites
- **Backend:** antes da chamada, listar as `storage_key` de `budget_documents` (e dos exames) do paciente; depois do sucesso, apagar os objetos do Storage. Falha na limpeza deixa linhas `deleted_at IS NOT NULL` para conciliação.
- **Mantido:** orçamentos (número, total, status), itens, parcelas e pagamentos: obrigação contábil; nenhum deles guarda nome (só `patient_id`).
- **Zerado:** `budgets.notes` (texto livre); **removidos logicamente:** os PDFs (têm o nome do paciente).
- Orçamento `DRAFT` ou `SENT` sem parcelas não bloqueia; o backend avisa o usuário.
- Quitar ou cancelar parcelas abertas é pré-requisito: estornar e cancelar (F04/F05) ou receber.
- A retenção de 20 anos considera só registro clínico; orçamento e financeiro não entram no cálculo.

## 5. Eventos emitidos
Nenhum no banco. O backend emite, se quiser, o evento de anonimização concluída.

## 6. Critérios de aceite (integração)
- [ ] Teste 112: `CR006` com parcela `OPEN`; passa com só `PAID`/`CANCELED` e com `SENT` sem parcelas; `budgets.notes` `NULL`; financeiro intacto; PDFs `deleted_at`; auditoria correta.
- [ ] Teste 113: precedência `CR002` > `CR004` > `CR006`; testes 46–51 (Fase 1) e 77–78 (Fase 2) verdes.
- [ ] Teste 115 (parte): função `SECURITY DEFINER` com `search_path` fixo; nenhum `USING (true)` do definer.
- [ ] Teste 118: a `U029` restaura o comportamento da V021.
- [ ] Teste 117 (parte): V029 aplica do zero; `U029` reverte e reaplica.
