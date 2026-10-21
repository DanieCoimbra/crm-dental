# PRD — F02 Pacientes (Fase 1)

**Derivação:** PRD global da Fase 1, US-101, US-102, US-103 e FR-1, FR-2, FR-5, FR-7, FR-8, FR-15; spec global, seções 2.3 (`patients`), 2.4, 2.6, 3.2 e 4.4.
**Dependências externas:** Fase 0 (`clinics`, `users`, F02 tenant), F01 da Fase 1 (`current_app_role()`, `current_user_id()`, `pg_trgm`). Consumida por F03, F04, F05 e F06 e pelo backend (módulo `patient`).

## 1. Introdução

- **Problema:** a recepção e o dentista precisam cadastrar, buscar e inativar pacientes, com isolamento por clínica e sem exclusão física.
- **Solução:** tabela `core.patients` com RLS por clínica e papel, CPF único por clínica, índices de busca por nome e por CPF, inativação lógica e grants por coluna.
- **Metas:**
  1. Nenhuma linha de outra clínica é visível ou alterável.
  2. Busca por trecho do nome ≤ 20 ms e por CPF ≤ 5 ms com 10 mil pacientes na clínica.
  3. `crm_app` sem `DELETE`; recepção e dentista leem, inserem e alteram.
  4. Autoria verdadeira: `created_by = app.user_id` na inserção.

## 2. User stories

### US-001: Cadastro de paciente
**Descrição:** Como recepcionista ou dentista, quero cadastrar o paciente com dados demográficos, contato, convênio e conhecido para emergência.

**Critérios de aceite:**
- [ ] Colunas e `CHECK`s do dicionário da spec global (seção 2.3), incluindo `sex`, `profession`, `acquaintance_name/phone`, `insurance_name/number`.
- [ ] CPF opcional, só dígitos, único por clínica (`23505` em duplicidade); CPF repetido em outra clínica é permitido.
- [ ] `phone` e `birth_date` obrigatórios; `email` em minúsculas.
- [ ] `INSERT` exige `created_by = core.current_user_id()`.

### US-002: Busca e listagem
**Descrição:** Como recepcionista, quero buscar por parte do nome ou por CPF e listar com paginação.

**Critérios de aceite:**
- [ ] Índices `ix_patients_clinic_name` (keyset), `ix_patients_name_trgm`, `ix_patients_name_prefix` e `ux_patients_clinic_cpf`.
- [ ] `EXPLAIN` das quatro consultas sem `Seq Scan` com 10 mil pacientes.

### US-003: Inativação lógica
**Descrição:** Como dentista ou recepcionista, quero inativar um paciente sem perder o histórico.

**Critérios de aceite:**
- [ ] `CHECK` de coerência entre `active` e `deactivated_at`.
- [ ] Nenhum `DELETE` para `crm_app`; filhas com `ON DELETE RESTRICT`.

### US-004: Isolamento por clínica e papel
**Descrição:** Como titular, quero que os dados do paciente fiquem restritos à minha clínica e a usuários autenticados com papel válido.

**Critérios de aceite:**
- [ ] `ENABLE` + `FORCE` RLS; uma política por operação com `clinic_id = tenant AND app.role em (DENTIST_ADMIN, RECEPTIONIST)`.
- [ ] Sem `app.clinic_id` ou com `app.role` inválido: 0 linhas e gravações falham.
- [ ] Política `SELECT TO crm_backup USING (true)`.

## 3. Requisitos funcionais

- **FR-1:** Migration `V008__core_patients.sql` com tabela, índices, triggers (`updated_at` e imutáveis: `id`, `clinic_id`, `created_by`, `created_at`), RLS e grants.
- **FR-2:** `GRANT SELECT` e `INSERT`/`UPDATE` por coluna (todas exceto `anonymized_at`) a `crm_app`; `SELECT` a `crm_backup`.
- **FR-3:** `UNIQUE (id, clinic_id)` para FKs compostas das features seguintes.
- **FR-4:** A coluna `anonymized_at` e sua `CHECK` existem aqui; o bloqueio de edição pós-anonimização e a função ficam na F06.

## 4. Fora do escopo

- Histórico, notas e exames (F03, F04); auditoria (F05); anonimização (F06).
- Validação de dígito verificador do CPF e de data futura (backend).
- Busca insensível a acento.

## 5. Considerações técnicas e riscos

- CPF, telefone e convênio ficam em texto claro por decisão P2; proteção por RLS e papéis.
- O backend deve mapear `anonymized_at` como `insertable = false, updatable = false`, senão recebe `permission denied`.
- `pg_trgm` depende da F01; sem ela, só o índice de prefixo.
- **Pergunta aberta:** nenhuma.
