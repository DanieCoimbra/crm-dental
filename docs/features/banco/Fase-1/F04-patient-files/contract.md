# Contract — F04 Metadados de Exames e Storage (Fase 1)

## 1. Resumo
- **Feature:** F04-patient-files (Fase 1)
- **Versão:** 1.0.0
- **Consumidores:** backend (módulo `record`, `FileStorage`); F05; F06; `crm_backup`.

## 2. Entradas

| Operação | Papel | Observações |
|---|---|---|
| `INSERT` | `DENTIST_ADMIN`, `RECEPTIONIST` | Sem `RETURNING`, sem `ON CONFLICT`, sem recarregar a linha |
| `SELECT`, `UPDATE` | `DENTIST_ADMIN` | `UPDATE` usado para `deleted_at` e `kind` |

| Coluna | Tipo | Regra |
|---|---|---|
| `id`, `clinic_id`, `patient_id`, `created_by` | `uuid` | `created_by = app.user_id`; imutáveis |
| `kind` | `varchar(20)` | `RADIOGRAPH`, `INTRAORAL_PHOTO`, `OTHER` |
| `storage_key` | `varchar(120)` | `<clinic_id>/<patient_id>/<uuid>`, único, imutável |
| `original_filename` | `varchar(255)` | Pode conter dado pessoal |
| `content_type` | `varchar(50)` | `image/jpeg`, `image/png`, `image/webp`, `application/pdf` |
| `size_bytes` | `bigint` | 1 a 10.485.760 |
| `deleted_at` | `timestamptz` | Remoção lógica |

## 3. Saídas
Todas as colunas (a recepção não lê). O download é URL assinada gerada pelo backend, não pelo banco.

### Erros
| SQLSTATE | Causa |
|---|---|
| `23514` | Tipo, tamanho, `kind` ou chave inválidos; coluna imutável |
| `23505` | `storage_key` repetida |
| `23503` | Paciente/usuário de outra clínica |
| `42501` | Papel sem permissão ou autoria falsa |

## 4. Regras e limites
- `INSERT`: dentista e recepção da clínica; `SELECT`/`UPDATE`: só dentista; `DELETE`: ninguém.
- Bucket privado, 10 MB, mesmos tipos da `CHECK`; sem política para `anon`/`authenticated`.
- Mapear no backend `storage_key`, `content_type`, `size_bytes`, `patient_id`, `created_by` como `updatable = false`.
- O banco não verifica a existência do objeto no Storage.

## 5. Eventos emitidos
Nenhum no banco (auditoria pela F05).

## 6. Critérios de aceite (integração)
- [ ] Teste 33: recepção insere; lê 0; atualiza 0; `INSERT ... RETURNING` falha.
- [ ] Teste 29 a 31 (parte): isolamento por clínica, sem tenant e FK composta.
- [ ] Teste 36 (parte): `created_by` falso falha.
- [ ] Teste 38 (parte): `kind`, tipo e tamanho inválidos falham.
- [ ] Teste 41: `storage_key` fora do formato ou de outra clínica falha.
- [ ] Teste 42 (parte): imutáveis e `updated_at`.
- [ ] Teste 52 (parte): `crm_app` sem `DELETE`.
- [ ] Teste 55 (parte): lista de exames sem `Seq Scan`.
- [ ] Bucket conferido manualmente (privado, limite e tipos) e registrado em `database/README.md`.
