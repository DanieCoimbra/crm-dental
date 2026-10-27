# Contract — F03 Auditoria e download de documentos (Fase 4)

## 1. Resumo
- **Feature:** F03-documents-audit-and-download (Fase 4)
- **Versão:** 1.0.0
- **Consumidores:** backend (emissão, anulação, PDF); F04 (linhas `ANONYMIZE`); dentista (consulta da trilha); `crm_backup`.

## 2. Entradas

O backend define `app.clinic_id`, `app.role` e `app.user_id` na transação (**`app.user_id` é obrigatório**: sem ele a auditoria reverte a operação).

**Registrar download ou impressão** — antes de devolver o PDF, na mesma transação:
```sql
SELECT core.record_document_download(:document_id);   -- como crm_app, só DENTIST_ADMIN
```

A emissão (F02) e a anulação (F02) não chamam nada: o trigger grava sozinho.

## 3. Saídas
Linhas de `core.audit_log` (`clinic_id`, `user_id`, `table_name = 'clinical_documents'`, `record_id`, `action`, `occurred_at`):

| `action` | Quando |
|---|---|
| `INSERT` | emissão |
| `VOID` | anulação |
| `ANONYMIZE` | anonimização do paciente (uma por documento) |
| `DOWNLOAD` | cada chamada de `record_document_download` |

Leitura: `SELECT` em `audit_log` como `DENTIST_ADMIN` (política existente), por `ix_audit_record (clinic_id, table_name, record_id, occurred_at DESC)`.

### Erros
| SQLSTATE | Causa | Resposta sugerida |
|---|---|---|
| `42501` | recepção chamando o download | 403 |
| `28000` | sem `app.user_id` ou sem clínica | 500 |
| `P0002` | documento inexistente ou de outra clínica | 404 |
| `CR003` | documento anonimizado | 409 |

## 4. Regras e limites
- **Ordem do backend (PDF):** (1) `BEGIN` + variáveis de sessão, (2) ler o documento, (3) `record_document_download`, (4) gerar o PDF (marca ANULADO se `voided_at`), (5) `COMMIT` e devolver. Falha na gravação = não entrega.
- Impressão pelo navegador do PDF do backend conta como download; imprimir a tela de detalhe não é auditável.
- Leitura em tela (lista, detalhe) não grava auditoria.
- A auditoria não guarda conteúdo, motivo da anulação nem valores.
- O `audit_log` é só de inserção, para todos os papéis.

## 5. Eventos emitidos
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] Teste 132: `INSERT` e `VOID` auditados; sem `app.user_id` reverte (`28000`); só identificadores.
- [ ] Teste 133: `record_document_download` (sucesso, repetição, recepção, clínica errada, inexistente, anonimizado).
- [ ] Teste 134: constraints novas do `audit_log`, log continua append-only.
- [ ] Teste 140 (parte): `V033` aplica do zero; `U033` reverte, reaplica e falha com auditoria gravada.
