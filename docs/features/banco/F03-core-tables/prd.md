# PRD — F03 Tabelas do Core (clinics, users, refresh_tokens)

**Derivação:** PRD global do banco, US-003, US-005 e FR-2, FR-3, FR-5, FR-6, FR-7, FR-9, FR-10; spec global, seções 2 e 3.
**Dependências externas:** F01 (papéis e schema), F02 (função de tenant, triggers e receita de RLS). Consumida pelas features F02 a F05 do backend (`F04-authentication`, `F05-clinic-and-users`).

## 1. Introdução

- **Problema:** o backend precisa de tabelas com integridade forte e isolamento por clínica para registrar clínicas, usuários e sessões.
- **Solução:** três tabelas em `core` com constraints que garantem as invariantes (e-mail único, um dentista por clínica, token sempre da clínica do usuário), RLS por operação e grants mínimos.
- **Metas:**
  1. Com `app.clinic_id = A`, 0 linhas de B são lidas, alteradas ou apagadas, e `INSERT` com `clinic_id = B` falha.
  2. Nenhuma coluna de plano, trial ou cobrança (FR-9).
  3. `ddl-auto=validate` do backend passa contra o schema.
  4. Login por e-mail e refresh por hash usam índice único.

## 2. User stories

### US-001: Clínicas isoladas
**Descrição:** Como backend, quero criar e editar a clínica do tenant e nenhuma outra.

**Critérios de aceite:**
- [ ] `clinics` com RLS: `SELECT`, `INSERT`, `UPDATE` só para `id = tenant`; sem `DELETE`.
- [ ] `INSERT` com `id` diferente do tenant falha.

### US-002: Usuários com invariantes
**Descrição:** Como backend, quero que o banco impeça estados inválidos de usuário.

**Critérios de aceite:**
- [ ] E-mail único global, sempre em minúsculas (`CHECK`).
- [ ] No máximo um `DENTIST_ADMIN` por clínica; dentista nunca inativo.
- [ ] `role`, `clinic_id`, `id` e `created_at` imutáveis.
- [ ] Sem `DELETE` para `crm_app` (desativa-se).

### US-003: Sessões ligadas à clínica correta
**Descrição:** Como backend, quero que um refresh token nunca pertença a usuário de outra clínica.

**Critérios de aceite:**
- [ ] FK composta `(user_id, clinic_id)` → `users(id, clinic_id)`.
- [ ] `token_hash` único e no formato hexadecimal de 64 caracteres.
- [ ] Colunas `clinic_id`, `user_id`, `family_id`, `token_hash` imutáveis.

### US-004: Compatibilidade com o JPA
**Descrição:** Como backend, quero tipos que o Hibernate valide sem ajustes.

**Critérios de aceite:**
- [ ] Textos como `varchar(n)` (nunca `char`/`text`); `uuid`, `timestamptz`, `bigint version`.

## 3. Requisitos funcionais

- **FR-1:** Criar as tabelas conforme o dicionário do contract, via `V003`, `V004` e `V005`.
- **FR-2:** Aplicar a receita de RLS da F02 em todas as três (incluindo política `SELECT` para `crm_backup`).
- **FR-3:** Aplicar triggers `set_updated_at` (clinics, users) e `forbid_immutable_columns` (as três).
- **FR-4:** Criar os índices do contract.
- **FR-5:** Conceder a `crm_app` apenas: `SELECT, INSERT, UPDATE` nas três tabelas; a `crm_backup`, `SELECT`.
- **FR-6:** Não criar `DEFAULT` que gere dados de negócio nem seeds.

## 4. Fora do escopo

- Funções de login e limpeza (F04).
- Tabelas de pacientes, agenda e financeiro (fases 1–4).
- `audit_log` (Fase 1).

## 5. Considerações técnicas e riscos

- E-mail único global impede o mesmo e-mail em duas clínicas (decisão aprovada).
- Colunas imutáveis e `CHECK` geram erros SQLSTATE `23514`/`23505` que o backend traduz para 4xx.
- Alterar tipo de coluna depois exige migration em passos.
- **Pergunta aberta:** nenhuma.
