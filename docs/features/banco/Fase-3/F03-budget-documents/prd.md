# PRD — F03 PDF do orçamento no Storage (Fase 3)

**Derivação:** PRD global da Fase 3, US-311, decisão C5, FR-16a e riscos 9 e 10; spec global, seções 2.3 (`budget_documents`), 4.6 e S12 e S13. Segue o padrão de `patient_files` (Fase 1, F04).
**Dependências externas:** F02 (`budgets`, `UNIQUE (id, patient_id, clinic_id)`, status fora de `DRAFT`), Fase 1 F04 (padrão de `storage_key`, bucket privado e `CRM_STORAGE_*`). Consumida por F06 (auditoria) e F07 (anonimização) e pelo backend (geração, envio e download do PDF).

## 1. Introdução

- **Problema:** o paciente recebe um PDF e, depois, a clínica precisa provar exatamente o que foi oferecido; sem registro do arquivo e de sua integridade, só resta regenerar.
- **Solução:** `core.budget_documents` guarda os **metadados** de cada PDF enviado (chave no Storage, tamanho, `sha256`, revisão); o binário fica no Supabase Storage. Cada reenvio é uma nova revisão; a linha é imutável salvo `deleted_at`.
- **Metas:**
  1. Nenhum PDF para orçamento em `DRAFT`.
  2. `revision` sequencial por orçamento, sem repetição sob concorrência.
  3. Todas as colunas imutáveis, exceto `deleted_at`, que só vai de nulo a um instante.
  4. Recepção lê e não grava.

## 2. User stories

### US-001: Registrar o PDF enviado
**Descrição:** Como dentista, quero registrar o PDF gerado ao enviar o orçamento, com sua impressão digital, para provar depois o que foi enviado.

**Critérios de aceite:**
- [ ] `core.budget_documents` conforme a spec global (2.3): `budget_id`, `patient_id`, `revision`, `storage_key`, `content_type` (`application/pdf`), `size_bytes` (1 a 5 MB), `sha256`, `generated_at`, `deleted_at`, `created_by`, `created_at`.
- [ ] FK composta `(budget_id, patient_id, clinic_id)` → `budgets (id, patient_id, clinic_id)` `RESTRICT`: o paciente é o do orçamento.
- [ ] `INSERT` com orçamento `DRAFT` → `CR008`.
- [ ] `storage_key` único e no formato `<clinic_id>/<patient_id>/budgets/<uuid>` (`CHECK`); `sha256` com 64 caracteres hexadecimais minúsculos.

### US-002: Revisões
**Descrição:** Como dentista, quero reenviar o orçamento sem perder o PDF anterior.

**Critérios de aceite:**
- [ ] `revision` atribuída pelo banco (`max + 1` sob trava do orçamento, contando as removidas); valor do cliente ignorado; `UNIQUE (budget_id, revision)`.
- [ ] Revisão ativa = maior `revision` com `deleted_at IS NULL` (índice parcial `(budget_id, revision DESC)`).
- [ ] Duas inserções simultâneas no mesmo orçamento recebem revisões distintas.

### US-003: Imutabilidade e remoção lógica
**Descrição:** Como titular, quero que o registro do PDF não seja reescrito, só marcado como removido.

**Critérios de aceite:**
- [ ] Trigger de imutáveis em todas as colunas, menos `deleted_at`; alterar `sha256` ou `storage_key` → `42501` para `crm_app` (grant por coluna: só `deleted_at` é atualizável) e `23514` para o dono ou superusuário (trigger de imutáveis).
- [ ] `deleted_at` só passa de nulo a um instante; não volta a nulo nem muda depois (`23514`).
- [ ] `crm_app` só atualiza a coluna `deleted_at` (grant por coluna); sem `DELETE`.

### US-004: Papéis e isolamento
**Descrição:** Como titular, quero que a recepção consulte o PDF (para reenviar ou imprimir) mas não o registre.

**Critérios de aceite:**
- [ ] RLS `ENABLE` + `FORCE`; `SELECT` para os dois papéis; `INSERT` (`created_by = app.user_id`) e `UPDATE` só `DENTIST_ADMIN`; política `SELECT TO crm_backup USING (true)`.
- [ ] Com `app.role` ausente ou inválido, tudo negado; sem `app.clinic_id`, 0 linhas.
- [ ] `storage_key` de outra clínica ou de outro paciente falha (`23514`).

## 3. Requisitos funcionais

- **FR-1:** Migration `V025__core_budget_documents.sql` (tabela, índices, triggers, RLS, grants).
- **FR-2:** `crm_app` com `SELECT`, `INSERT` e `UPDATE (deleted_at)`; `crm_backup` com `SELECT`.
- **FR-3:** O banco **não** acessa o Storage nem confere a existência do objeto; a ordem de gravação do backend evita linha sem objeto.
- **FR-4:** Rollback `U025`: só seguro antes de haver dados.

## 4. Fora do escopo

- Gerar o PDF, gravar e apagar o objeto no Storage (backend).
- Assinatura digital, envio por e-mail ou WhatsApp, versionamento de layout.
- Política de retenção própria do bucket (mesmo bucket dos exames, prefixo `budgets/`).
- Backup do bucket (fora do `pg_dump`; risco aceito).
- Marcar `deleted_at` na anonimização (F07).

## 5. Considerações técnicas e riscos

- O objeto contém o **nome do paciente** e não tem RLS: acesso só pelo backend (URL assinada curta), chave opaca, bucket privado (pendência de segurança já registrada).
- Perder o bucket deixa linhas órfãs; o `sha256` detecta objeto trocado ou corrompido e o PDF pode ser regenerado a partir do orçamento congelado (nova revisão).
- A ordem do backend (6.3 da spec global): gravar o objeto antes da linha; se o `INSERT` falhar, apagar o objeto.
- **Pergunta aberta:** nenhuma.
