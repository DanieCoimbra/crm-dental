# Contract — F04 Anonimização com documentos (Fase 4)

## 1. Resumo
- **Feature:** F04-documents-anonymization (Fase 4)
- **Versão:** 1.0.0
- **Consumidores:** backend (rota de anonimização do paciente, já existente); F03 (auditoria); `crm_backup`.

## 2. Entradas
Sem mudança de assinatura: `SELECT core.anonymize_patient(:patient_id)` como `crm_app`, só `DENTIST_ADMIN`, com `app.clinic_id`, `app.role` e `app.user_id` na transação.

## 3. Saídas
- Retorno: `timestamptz` (instante da anonimização), como antes.
- Efeito nos documentos do paciente:

| Coluna | Depois |
|---|---|
| `content`, `rest_days`, `target_specialty`, `void_reason` | `NULL` |
| `anonymized_at` | instante da anonimização |
| `number`, `document_type`, `issued_on`, `sha256`, `created_by`, `voided_at`, `voided_by` | inalterados |
| `version` | `+1` |

- `audit_log`: uma linha `clinical_documents/ANONYMIZE` por documento, mais a de `patients`.

### Erros
Os da função existente (`42501`, `28000`, `P0002`, `CR001`, `CR002`, `CR003`, `CR004`, `CR006`). **Documentos não introduzem erro novo.**

## 4. Regras e limites
- Documento não bloqueia a anonimização nem entra na retenção do prontuário.
- Não há objeto no Storage a apagar para documentos (o PDF é gerado sob demanda).
- Depois de anonimizado, `record_document_download` responde `CR003` e o backend não gera PDF.
- Documentos anonimizados continuam listáveis (número e tipo), sem conteúdo.
- O backend não pode depender de `content` para documentos de pacientes anonimizados.

## 5. Eventos emitidos
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] Teste 135: colunas zeradas, linha mantida, `anonymized_at`, `version+1`, auditoria por documento, sem bloqueio, precedência de erros inalterada.
- [ ] Teste 136: GUC forjado por sessão comum não permite `UPDATE` de documento; definer não vê outra clínica.
- [ ] Teste 140 (parte): `V034` aplica do zero; `U034` restaura a função da V029 (comparação com o corpo da V029).
