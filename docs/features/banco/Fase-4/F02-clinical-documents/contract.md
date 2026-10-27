# Contract — F02 Documentos clínicos (Fase 4)

## 1. Resumo
- **Feature:** F02-clinical-documents (Fase 4)
- **Versão:** 1.0.0
- **Consumidores:** backend (emitir, listar, abrir, anular); F03 (auditoria); F04 (anonimização); `crm_backup`.

## 2. Entradas

O backend define, na **mesma transação**, `app.clinic_id`, `app.role` e `app.user_id`, em `READ COMMITTED`.

**Emitir** — `INSERT` em `core.clinical_documents` como `crm_app` (só `DENTIST_ADMIN`):

```json
{
  "id": "uuid (gerado pela aplicação)",
  "clinic_id": "uuid (= app.clinic_id)",
  "patient_id": "uuid (paciente ativo e não anonimizado)",
  "appointment_id": "uuid ou null (consulta do mesmo paciente)",
  "document_type": "CERTIFICATE | PRESCRIPTION | REFERRAL",
  "issued_on": "date (data civil no fuso da clínica)",
  "rest_days": "smallint 1..90 (só CERTIFICATE, obrigatório nele)",
  "target_specialty": "string 2..60 (só REFERRAL, obrigatório nele)",
  "content": "Base64 do AES-256-GCM, 40..22000 caracteres",
  "sha256": "hex minúsculo de 64 caracteres (v1|tipo|paciente|issued_on|rest_days|especialidade|content)",
  "created_by": "uuid (= app.user_id; precisa ter CRO)"
}
```
**Não enviar** `number`, `voided_*`, `anonymized_at`. Reler a linha depois do `INSERT` para obter `number`.

**Anular** — `UPDATE` como `crm_app` (só `DENTIST_ADMIN`), apenas estas colunas:
```json
{ "voided_at": "timestamptz", "voided_by": "uuid (= app.user_id)", "void_reason": "string ≤ 200 ou null", "version": "bigint" }
```

**Listar** — `SELECT` por `(clinic_id, patient_id)` ordenado por `created_at DESC, id DESC` (keyset).

## 3. Saídas
| Coluna | Tipo | Observação |
|---|---|---|
| `number` | `integer` | por clínica e tipo; pelo banco |
| `document_type`, `issued_on`, `rest_days`, `target_specialty` | claros | nulos após anonimização |
| `content` | `varchar(22000)` | cifrado; nulo após anonimização; o backend decifra |
| `sha256` | `varchar(64)` | o backend recalcula e compara ao reabrir |
| `voided_at`, `voided_by`, `void_reason` | | anulado quando `voided_at` não é nulo |
| `anonymized_at` | `timestamptz` | pelo banco (F04) |
| `created_by`, `created_at`, `updated_at`, `version` | | |

### Erros
| SQLSTATE | Causa | Resposta sugerida |
|---|---|---|
| `42501` | recepção inserindo; `created_by` ≠ `app.user_id`; `UPDATE` de coluna sem grant; `DELETE`/`TRUNCATE` | 403 (bug, no caso do grant) |
| `CR012` | emissor sem CRO e UF | 409 |
| `CR003` / `CR005` | paciente anonimizado / inativo | 409 |
| `23514` | `CHECK` (tipo, campos por tipo, Base64, tamanho, `sha256`); anular de novo; mudar campo imutável | 422 (dado) / 409 (anular de novo) |
| `23503` | paciente ou consulta de outra clínica; consulta de outro paciente; `created_by` ou `voided_by` inexistente na clínica (para o `crm_app`, `created_by` diferente de `app.user_id` chega antes como `42501`) | 422/404 |
| `28000` | sem `app.user_id` no trigger de auditoria (F03). Para o `crm_app` a política `cdoc_ins` recusa antes com `42501` (`created_by` exige `app.user_id`); o `28000` só aparece para papéis que ignoram a RLS | 500 |

## 4. Regras e limites
- Documento é imutável; corrigir = emitir outro. Anular não apaga.
- Recepção não vê, não emite e não anula.
- Mapeamento JPA: `number` não inserível; `UPDATE` só nas quatro colunas do grant (`@DynamicUpdate`).
- O banco não verifica o `sha256` nem decifra o `content`.
- Anulado continua legível; a marca no PDF é do backend.

## 5. Eventos emitidos
Nenhum (a trilha é a F03).

## 6. Critérios de aceite (integração)
- [ ] Teste 127: constraints por tipo, `CHECK`s de conteúdo e `sha256`, FKs compostas.
- [ ] Teste 128: `CR012`, `CR003`, `CR005`, recepção `42501`, ordem dos erros.
- [ ] Teste 129: só anular; anular duas vezes; `DELETE`/`TRUNCATE` negados (inclusive ao dono); `UPDATE` fora do grant.
- [ ] Testes 130 e 131: numeração e concorrência de duas conexões (`documents_concurrency.sh`).
- [ ] Teste 138 (parte): isolamento entre clínicas.
- [ ] Teste 140 (parte): `V032` aplica do zero; `U032` reverte e reaplica (destrutiva por convenção: o dono não enxerga linhas sob RLS FORCE, então não confere se há documentos; com dados, restaurar do pg_dump).
