# PRD — F06 Retenção e Anonimização de Paciente (Fase 1)

**Derivação:** PRD global da Fase 1, US-110, decisão P3 e FR-16; spec global, seções 1.2 (S2–S4), 2.6 (guards), 3.2 (políticas do `crm_definer`) e 4.2.
**Dependências externas:** F01 (retenção, funções de sessão), F02, F03, F04 (tabelas), F05 (`write_audit`, `audit_log`). Consumida pelo backend (chamada da função e limpeza do Storage).

## 1. Introdução

- **Problema:** a LGPD exige guardar o prontuário pelo prazo legal e, depois dele (ou quando não há obrigação de guarda), eliminar os dados pessoais, sem apagar o rastro de auditoria.
- **Solução:** estado terminal `anonymized_at`, guards que impedem qualquer edição posterior e a função `core.anonymize_patient`, que só o dentista chama, só para paciente inativo e fora da retenção.
- **Metas:**
  1. Antes do fim da retenção, a função falha (`CR002`) sem alterar nada.
  2. Depois, nenhuma coluna pessoal conserva o valor original.
  3. A ação inteira é auditada e atômica.
  4. Paciente anonimizado não é editado, reativado nem recebe novos registros.

## 2. User stories

### US-001: Anonimizar paciente
**Descrição:** Como dentista, quero anonimizar um paciente elegível para cumprir o pedido do titular ou o fim da guarda.

**Critérios de aceite:**
- [ ] `core.anonymize_patient(p_patient_id uuid) RETURNS timestamptz`, `SECURITY DEFINER`, dono `crm_definer`, `search_path` fixo, sem SQL dinâmico, `EXECUTE` só para `crm_app`.
- [ ] Exige `app.role = DENTIST_ADMIN` (`42501`), tenant e usuário definidos (`28000`), paciente da clínica (`P0002`), inativo (`CR001`), não anonimizado (`CR003`) e retenção vencida (`CR002`).
- [ ] Executa os passos 5 a 10 da spec global (4.2) em uma transação, com `SELECT ... FOR UPDATE` no paciente.

### US-002: Retenção calculada
**Descrição:** Como responsável pela conformidade, quero que o prazo seja calculado na hora, sem coluna que possa ficar velha.

**Critérios de aceite:**
- [ ] Último registro clínico = maior entre `medical_histories.updated_at`, `clinical_notes.updated_at` e `noted_at`, `patient_files.updated_at`; fim da retenção = último + `clinical_retention_period()` (20 anos).
- [ ] Paciente sem nenhum registro clínico não tem retenção (decisão S4).
- [ ] A mensagem de `CR002` informa a data liberada em `DETAIL`, sem dado pessoal.

### US-003: Estado terminal protegido
**Descrição:** Como titular, quero que o paciente anonimizado não seja alterado de novo.

**Critérios de aceite:**
- [ ] `trg_patients_guard_anonymized`: `UPDATE` com `OLD.anonymized_at` não nulo → `CR003`; `anonymized_at` só muda de `NULL` para valor dentro da função (flag local `core.anonymizing`), senão `42501`.
- [ ] `BEFORE INSERT` em `medical_histories`, `clinical_notes` e `patient_files` → `CR003` se o paciente estiver anonimizado.

### US-004: Privilégios mínimos do `crm_definer`
**Descrição:** Como responsável pela segurança, quero que a função só alcance a clínica da sessão.

**Critérios de aceite:**
- [ ] Políticas do `crm_definer` escopadas a `clinic_id = core.current_clinic_id()`: `SELECT`/`UPDATE` em `patients` e `patient_files`; `SELECT`/`DELETE` em `medical_histories` e `clinical_notes`.
- [ ] Grants correspondentes, sem mais nada; nenhum `USING (true)`.

## 3. Requisitos funcionais

- **FR-1:** Migration `V013__core_patient_anonymization.sql` com políticas e grants do definer, guards e a função.
- **FR-2:** Os passos da anonimização seguem a spec global, 4.2: apagar histórico e notas, marcar exames (`deleted_at`, `original_filename = 'anonimizado'`, mantendo `storage_key`), substituir os dados pessoais do paciente, reduzir `birth_date` ao ano, `version = version + 1`.
- **FR-3:** Inserir `audit_log` com `action = 'ANONYMIZE'`; os `DELETE`/`UPDATE` internos geram suas linhas pelos triggers da F05.
- **FR-4:** Códigos: `42501`, `28000`, `P0002`, `CR001`, `CR002`, `CR003`.

## 4. Fora do escopo

- Apagar objetos no Storage (backend).
- Exportação de dados do titular (backend).
- Expurgo agendado e automático; registro de consentimento.
- Anonimização de `users`, `clinics` ou `audit_log`.

## 5. Considerações técnicas e riscos

- Dumps do `crm_backup` guardam o dado original por até 30 dias após a anonimização.
- O prazo de 20 anos deve ser validado juridicamente antes de uso real (dados do projeto são fictícios).
- Falha no Storage depois da anonimização deixa objetos órfãos; `storage_key` é mantida para a conciliação.
- Anonimizar é irreversível; a confirmação é responsabilidade da interface.
- **Pergunta aberta:** nenhuma.
