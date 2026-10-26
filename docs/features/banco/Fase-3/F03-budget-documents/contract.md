# Contract — F03 PDF do orçamento no Storage (Fase 3)

## 1. Resumo
- **Feature:** F03-budget-documents (Fase 3)
- **Versão:** 1.0.0
- **Consumidores:** backend (envio, reenvio e download do PDF); F06 (auditoria); F07 (anonimização); `crm_backup`.

## 2. Entradas

O backend define, na **mesma transação**, `app.clinic_id`, `app.role` e `app.user_id`. `INSERT` em `core.budget_documents` como `crm_app` (só `DENTIST_ADMIN`), **depois** de gravar o objeto no Storage:

```json
{
  "id": "uuid (gerado pela aplicação)",
  "clinic_id": "uuid (= app.clinic_id)",
  "budget_id": "uuid (orçamento fora de DRAFT)",
  "patient_id": "uuid (= paciente do orçamento)",
  "storage_key": "<clinic_id>/<patient_id>/budgets/<uuid>",
  "content_type": "application/pdf",
  "size_bytes": "bigint, 1 a 5242880",
  "sha256": "varchar(64), hex minúsculo do arquivo",
  "created_by": "uuid (= app.user_id)"
}
```
**Não enviar** `revision` (o banco atribui). `UPDATE` só de `deleted_at` (dentista).

## 3. Saídas

| Coluna | Tipo | Observação |
|---|---|---|
| `revision` | `smallint` | 1, 2, 3… por orçamento; pelo banco; reler depois do `INSERT` |
| `storage_key` | `varchar(120)` | Único; chave do objeto no bucket dos exames |
| `size_bytes`, `sha256` | `bigint`, `varchar(64)` | Para conferir o objeto baixado |
| `generated_at`, `created_at` | `timestamptz` | Imutáveis |
| `deleted_at` | `timestamptz` | Única coluna mutável; nulo = ativo |

### Erros
| SQLSTATE | Causa | Resposta sugerida |
|---|---|---|
| `CR008` | Orçamento em `DRAFT` | 409 |
| `23514` | `CHECK` (chave, `sha256`, tipo, tamanho); `deleted_at` voltando a nulo ou mudando; coluna imutável (só para o dono ou superusuário) | 422 |
| `23505` | `storage_key` repetida | 500 |
| `23503` | Orçamento ou paciente de outra clínica | 404 |
| `42501` | Recepção gravando; `created_by` ≠ `app.user_id`; `DELETE`; `UPDATE` de qualquer coluna que não seja `deleted_at` (grant por coluna) | 403 (o backend barra antes; o JPA mapeia as demais colunas como não atualizáveis) |
| `28000` | Sem `app.user_id` (auditoria, F06) | 500 |

## 4. Regras e limites
- **Ordem do backend:** (1) `UPDATE budgets SET status = 'SENT'`, (2) gerar o PDF e calcular o `sha256`, (3) gravar o objeto no Storage, (4) `INSERT` em `budget_documents`, (5) `COMMIT`. Falha em qualquer passo: `ROLLBACK` e apagar o objeto órfão. Nunca inserir a linha antes do objeto.
- **Revisão ativa** = maior `revision` com `deleted_at IS NULL`. Reenvio = nova linha; o orçamento não muda.
- **Download:** sempre pelo backend (URL assinada curta ou proxy); conferir o `sha256` quando relevante. O bucket é o dos exames (Fase 1), com o prefixo `budgets/`.
- **Anonimização (F07):** o banco marca `deleted_at` em todos os PDFs do paciente; o backend lista as `storage_key` **antes** e apaga os objetos **depois** do sucesso.
- Recepção: lê metadados; não grava.
- O banco não verifica a existência do objeto.

## 5. Eventos emitidos
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] Teste 110: `DRAFT` → `CR008`; revisão 1, 2, 3 (e concorrência); chave, `sha256`, tipo e tamanho; imutabilidade; `deleted_at` de mão única; recepção lê e não grava.
- [ ] Testes 86 e 87 (parte): isolamento e FK cruzada.
- [ ] Teste 114 (parte): `UPDATE` só em `deleted_at`; sem `DELETE`.
- [ ] Teste 117 (parte): V025 aplica do zero; `U025` reverte e reaplica.
