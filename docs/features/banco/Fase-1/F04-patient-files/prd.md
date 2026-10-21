# PRD — F04 Metadados de Exames e Storage (Fase 1)

**Derivação:** PRD global da Fase 1, US-106, decisões P1 (recepção só insere) e P5 (Supabase Storage); spec global, seções 2.3 (`patient_files`), 3.2, 5.4 e 6.3 (regra 2).
**Dependências externas:** F01, F02 (`patients`). Consumida por F05, F06 e pelo backend (upload e URL assinada).

## 1. Introdução

- **Problema:** radiografias e fotos precisam ser vinculadas ao paciente, anexadas também pela recepção, sem guardar binário no banco.
- **Solução:** tabela `patient_files` só de metadados (chave do objeto, tipo, tamanho), com remoção lógica; objetos no bucket privado do Supabase Storage.
- **Metas:**
  1. `RECEPTIONIST` insere, mas lê 0 linhas e altera 0.
  2. `DENTIST_ADMIN` lê, insere e altera metadados da própria clínica.
  3. Chave do objeto amarrada à clínica e ao paciente por `CHECK`.
  4. Arquivo fora de tipo ou de 10 MB é rejeitado pelo banco.

## 2. User stories

### US-001: Registrar exame
**Descrição:** Como dentista ou recepcionista, quero registrar o arquivo enviado ao Storage para vinculá-lo ao paciente.

**Critérios de aceite:**
- [ ] `core.patient_files` com `kind`, `storage_key`, `original_filename`, `content_type`, `size_bytes`, `deleted_at`, `created_by`.
- [ ] `CHECK` de `kind`, de `content_type` (JPEG, PNG, WebP, PDF) e de `size_bytes` (1 a 10.485.760).
- [ ] `storage_key` única e no formato `<clinic_id>/<patient_id>/<uuid>`.
- [ ] `INSERT` exige `created_by = app.user_id`.

### US-002: Recepção só anexa
**Descrição:** Como titular, quero que a recepção anexe exames sem poder ver ou alterar o que foi guardado.

**Critérios de aceite:**
- [ ] `INSERT` permitido a `DENTIST_ADMIN` e `RECEPTIONIST`; `SELECT` e `UPDATE` só a `DENTIST_ADMIN`.
- [ ] Sob `RECEPTIONIST`: `INSERT` passa, `SELECT` devolve 0 linhas, `UPDATE` afeta 0, `INSERT ... RETURNING` falha.

### US-003: Remoção lógica
**Descrição:** Como dentista, quero remover um exame da lista sem perder o registro.

**Critérios de aceite:**
- [ ] `UPDATE ... SET deleted_at` pelo dentista; índice parcial `ix_files_patient WHERE deleted_at IS NULL`.
- [ ] Sem `DELETE` para `crm_app`; colunas do arquivo imutáveis (`storage_key`, `content_type`, `size_bytes`, `patient_id`).

### US-004: Bucket privado
**Descrição:** Como responsável pela segurança, quero o Storage configurado só para o backend.

**Critérios de aceite:**
- [ ] `database/README.md` documenta o bucket privado, `file_size_limit` de 10 MB, `allowed_mime_types` iguais à `CHECK`, ausência de políticas para `anon`/`authenticated` e URL assinada de curta duração.

## 3. Requisitos funcionais

- **FR-1:** Migration `V011__core_patient_files.sql`: tabela, índices, triggers (`updated_at`; imutáveis), RLS e grants.
- **FR-2:** Políticas conforme a spec global (3.2): `INSERT` para `A`, `SELECT`/`UPDATE` para `D`, todos com tenant e (no `INSERT`) autoria.
- **FR-3:** `GRANT SELECT, INSERT, UPDATE` a `crm_app`; `SELECT` a `crm_backup` com política nomeada.
- **FR-4:** FK composta para `patients` e para `users`, `ON DELETE RESTRICT`.

## 4. Fora do escopo

- Upload, antivírus, miniaturas, URL assinada e limpeza de objetos (backend).
- DICOM e outros tipos.
- Políticas do `crm_definer` (F06).
- Backup dos objetos do Storage.

## 5. Considerações técnicas e riscos

- A chave de serviço do Storage equivale a `service_role`: segredo só do backend; bucket privado.
- Metadados órfãos se o objeto sumir; objetos órfãos se o registro falhar. Conciliação é do backend.
- `original_filename` pode conter nome do paciente: tratado como dado pessoal e substituído na anonimização (F06).
- **Pergunta aberta:** nenhuma.
