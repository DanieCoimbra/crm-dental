# PRD — F03 Prontuário Clínico Cifrado (Fase 1)

**Derivação:** PRD global da Fase 1, US-104, US-105, decisões P1 e P2 e FR-4, FR-6; spec global, seções 2.3 (`medical_histories`, `clinical_notes`, campos cifrados), 2.4 e 3.2.
**Dependências externas:** F01 (papel da sessão), F02 (`patients`, `UNIQUE (id, clinic_id)`). Consumida por F05 (auditoria), F06 (anonimização) e pelo backend (módulo `record`, cifra `AesGcmCipher`).

## 1. Introdução

- **Problema:** o histórico médico e as notas por atendimento são o dado mais sensível do produto; a recepção não pode acessá-los nem se o backend errar, e o banco não pode conter texto clínico legível.
- **Solução:** `medical_histories` (1 por paciente) e `clinical_notes` com conteúdo cifrado e opaco, RLS por clínica **e** por `DENTIST_ADMIN`, sem `DELETE`.
- **Metas:**
  1. Sob `RECEPTIONIST`: 0 linhas legíveis e 0 gravações possíveis nas duas tabelas.
  2. Texto em claro gravado por engano é rejeitado pelo `CHECK`.
  3. Linha do tempo de notas por keyset sem *sort*.
  4. Um único histórico por paciente.

## 2. User stories

### US-001: Histórico médico por paciente
**Descrição:** Como dentista, quero um histórico médico por paciente, ilegível fora da aplicação.

**Critérios de aceite:**
- [ ] `core.medical_histories` com `UNIQUE (patient_id)` e FK composta para `patients`.
- [ ] `content varchar(54000)` com `CHECK` de Base64 e mínimo de 40 caracteres.
- [ ] Segundo histórico do mesmo paciente falha (`23505`).

### US-002: Notas por atendimento
**Descrição:** Como dentista, quero registrar e editar notas datadas por paciente.

**Critérios de aceite:**
- [ ] `core.clinical_notes` com `noted_at timestamptz`, `content varchar(27000)` cifrado e opaco.
- [ ] Índice `ix_notes_timeline (clinic_id, patient_id, noted_at DESC, id DESC)`.
- [ ] Edição permitida (`UPDATE`); sem `DELETE`.

### US-003: Recepção sem acesso
**Descrição:** Como titular, quero que a recepção não leia nem grave prontuário.

**Critérios de aceite:**
- [ ] Políticas exigem `clinic_id = tenant AND app.role = 'DENTIST_ADMIN'` em `SELECT`, `INSERT` e `UPDATE`; `DELETE` sem política.
- [ ] `INSERT` exige `created_by = app.user_id`.
- [ ] Sem `app.role` ou com valor inválido: tudo negado.

### US-004: Prontuário opaco para o banco
**Descrição:** Como responsável pela segurança, quero que o banco nunca veja nem valide o texto clínico.

**Critérios de aceite:**
- [ ] Nenhuma extensão de criptografia, nenhuma chave no banco.
- [ ] `CHECK` rejeita conteúdo com espaço, pontuação ou menos de 40 caracteres.

## 3. Requisitos funcionais

- **FR-1:** Migrations `V009__core_medical_histories.sql` e `V010__core_clinical_notes.sql`, cada uma com índices, triggers (`updated_at`; imutáveis: `id`, `clinic_id`, `patient_id`, `created_by`, `created_at`), RLS e grants.
- **FR-2:** `GRANT SELECT, INSERT, UPDATE` a `crm_app`; `SELECT` a `crm_backup`; política `SELECT TO crm_backup USING (true)`.
- **FR-3:** FK composta `(patient_id, clinic_id)` → `patients (id, clinic_id)` `ON DELETE RESTRICT`; FK composta de `created_by`.
- **FR-4:** Políticas e grants do `crm_definer` (necessários à anonimização) **não** ficam aqui: pertencem à F06.

## 4. Fora do escopo

- Cifrar, decifrar, rotacionar chave (backend).
- Busca no conteúdo clínico.
- Vínculo da nota com agendamento (`appointment_id`, Fase 2).
- Limites de caracteres do texto claro (backend: 10.000 e 5.000).

## 5. Considerações técnicas e riscos

- O banco não autentica o cifrado: um conteúdo Base64 forjado passa pelo `CHECK` e só falha na decifragem (`TAMPERED`) no backend.
- Perda da chave AES torna o conteúdo irrecuperável (risco herdado da Fase 0).
- Colunas dimensionadas para 4 bytes UTF-8 por caractere: `varchar(54000)` e `varchar(27000)`.
- **Pergunta aberta:** nenhuma.
