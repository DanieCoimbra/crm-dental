# PRD — F05 Auditoria e anonimização

**Derivação:** PRD global da Fase 1, US-P1-09 e US-P1-10, FR-8 a FR-10, FR-16; decisões E3 e E6. Spec global, seções 4.10, 4.11 e 5.1.
**Dependências externas:** F01 (erros do banco), F02 (`Patient`, `PatientLookup`), F04 (`PatientFileService.storageKeysOf` e `purgeObjects`; F03 só como origem dos registros auditados), `core.anonymize_patient(uuid)` (V013), `core.audit_log` (V012). Requer o access token da Fase 0 com papel `DENTIST_ADMIN`.

## 1. Introdução

- **Problema:** o consultório precisa cumprir a LGPD (eliminar dados pessoais após a guarda legal de 20 anos) e saber quem tocou nos dados clínicos, mas o banco só oferece as funções e a tabela; falta expô-las com papel, tradução de erros e limpeza do Storage.
- **Solução:** endpoint de anonimização em duas fases (transação do banco e, depois do commit, limpeza dos objetos) e endpoint somente leitura da trilha de auditoria, ambos exclusivos do dentista.
- **Metas:**
  1. Depois da anonimização, 0 coluna pessoal mantém o valor original e 0 edição posterior é aceita.
  2. Falha do Storage nunca desfaz a anonimização (resposta `PENDING`, evento de segurança sem chaves nem nomes).
  3. A trilha nunca expõe valores de colunas e nunca cruza clínicas.
  4. Recepcionista recebe 403 em 100% das rotas da feature.
  5. Cobertura ≥ 80% em `PatientAnonymizationService` e `AuditQueryService`.

## 2. User stories

### US-001: Anonimizar paciente
**Descrição:** Como dentista, quero anonimizar os dados pessoais de um paciente quando a retenção legal vencer, ou imediatamente se ele não tiver prontuário.

**Critérios de aceite:**
- [ ] `POST /api/v1/patients/{id}/anonymize` (só D) chama `core.anonymize_patient` e responde 200 `{anonymizedAt, storageCleanup}`.
- [ ] Erros: `CR001` (paciente ativo) → 409 "inative antes"; `CR002` (retenção vigente) → 409 com a data de liberação; `CR003` (já anonimizado) → 409; `P0002` → 404; `42501` → 403.
- [ ] Na mesma transação, depois da função, lista **todas** as `storage_key` do paciente; **depois do commit**, apaga os objetos no Storage.
- [ ] Falha do Storage → 200 com `storageCleanup: PENDING`, anonimização preservada e evento de segurança com `patientId` e quantidade (sem chaves nem nomes); sucesso → `COMPLETED`.
- [ ] Depois: nenhuma coluna pessoal mantém o valor original, histórico e notas apagados, exames marcados como removidos, e edição, reativação e novos registros → 409.
- [ ] Segunda chamada concorrente → 409 (`CR003`).

### US-002: Consultar a trilha de auditoria
**Descrição:** Como dentista, quero saber quem criou ou alterou um registro clínico.

**Critérios de aceite:**
- [ ] `GET /api/v1/audit-log?tableName&recordId&page&size` (só D), somente leitura, `size` ≤ 50, mais recente primeiro.
- [ ] `recordId` exige `tableName`; `tableName` ∈ `patients`, `medical_histories`, `clinical_notes`, `patient_files`; senão 400.
- [ ] Cada item traz `userId`, `userName`, `tableName`, `recordId`, `action` (`INSERT`, `UPDATE`, `DELETE`, `ANONYMIZE`) e `occurredAt`; nunca valores de colunas.
- [ ] Não existe endpoint de escrita em auditoria; a entidade é `@Immutable`.
- [ ] Registros de outra clínica nunca aparecem; R recebe 403.

## 3. Requisitos funcionais

- **FR-1:** Orquestrar a anonimização com `TransactionTemplate` (serviço não transacional) para separar o commit da limpeza do Storage.
- **FR-2:** Chamar `SELECT core.anonymize_patient(:id)` por consulta nativa parametrizada e converter o resultado para `Instant`.
- **FR-3:** Traduzir `SQLState` em mensagens específicas dentro do service (`CR001`, `CR002`, `CR003`, `P0002`, `42501`).
- **FR-4:** Listar as chaves após a função e na mesma transação (a função trava a linha do paciente e fecha a corrida com upload concorrente).
- **FR-5:** Publicar `Patient.Anonymized` e `Patient.StorageCleanupPending`; o `ClinicalAuditLogger` registra só identificadores, depois do commit.
- **FR-6:** `AuditLog` somente leitura; resolver nomes de usuário por `UserNames.namesOf(ids)` (interface pública nova em `clinic.service`), sem acessar repositório de outro módulo.
- **FR-7:** Rotas `POST /patients/*/anonymize` e `/audit-log/**` exigem D na rota e `@PreAuthorize`.

## 4. Fora do escopo

- Expurgo automático agendado por retenção; reconciliação de objetos órfãos do Storage.
- Exportação de dados do titular (acesso e portabilidade).
- Auditoria de leitura; comparação de valores antigo/novo.
- Visão única da trilha por paciente: o `audit_log` não tem `patient_id`, então a consulta é **por registro**.
- Reversão da anonimização.

## 5. Considerações técnicas e riscos

- Dumps do `crm_backup` guardam os dados originais por até 30 dias após a anonimização (política de privacidade).
- Prazo de 20 anos a validar com assessoria jurídica antes de uso real; o parâmetro é do banco.
- Se a tela precisar do histórico completo de um paciente, será pedido de migration à função `banco` (coluna `patient_id`).
- O `statement_timeout` de 15 s comporta pacientes com dezenas ou centenas de notas.
- **Pergunta aberta:** exportação dos dados do titular (endpoint agora ou sob demanda).
