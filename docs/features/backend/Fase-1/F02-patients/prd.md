# PRD — F02 Pacientes

**Derivação:** PRD global da Fase 1, US-P1-01 a US-P1-04, FR-1 a FR-3, FR-8, FR-12, FR-14; decisões E4 e E6. Spec global, seções 2.2, 3.2, 4.2 a 4.4.
**Dependências externas:** F01 (sessão do banco com papel e usuário, tradução de erros), tabela `core.patients` (V008). Requer o access token da Fase 0 (F04). Consumida por F03, F04 e F05 por `PatientLookup`.

## 1. Introdução

- **Problema:** o dentista e a recepção ainda não conseguem cadastrar nem achar pacientes, base de todo o ciclo clínico.
- **Solução:** cadastro, busca paginada, edição com controle otimista e inativação lógica, com validação de CPF, telefone e datas no servidor.
- **Metas:**
  1. Busca p95 ≤ 100 ms com 10 mil pacientes na clínica e 4 concorrentes.
  2. 100% dos erros de validação listados de uma vez em `errors[]`.
  3. Paciente de outra clínica nunca aparece, nem com o filtro Hibernate desligado.
  4. Cobertura ≥ 80% em `PatientService` e `PatientRules`.

## 2. User stories

### US-001: Cadastrar paciente
**Descrição:** Como recepcionista ou dentista, quero cadastrar um paciente com dados demográficos, contato, convênio e contato de um conhecido.

**Critérios de aceite:**
- [ ] `POST /api/v1/patients` (D, R) devolve 201 com o paciente; `clinic_id`, `created_by` e `id` não vêm do corpo.
- [ ] Obrigatórios: nome (2–120), nascimento (≥ 1900-01-01, não futuro) e telefone (10–13 dígitos). Opcionais: CPF, sexo (padrão `NOT_INFORMED`), profissão, e-mail, endereço, convênio, conhecido.
- [ ] Convênio: carteirinha exige nome. Conhecido: nome e telefone ambos ou nenhum.
- [ ] CPF inválido (dígito verificador ou sequência repetida) → 400 no campo `cpf`; CPF repetido na clínica → 409 sem revelar o outro paciente; mesmo CPF em outra clínica é aceito.
- [ ] Telefones saem só com dígitos; e-mail em minúsculas.

### US-002: Buscar e listar pacientes
**Descrição:** Como recepcionista ou dentista, quero buscar por parte do nome ou por CPF, com paginação.

**Critérios de aceite:**
- [ ] `GET /api/v1/patients?q&status&page&size` (D, R); `size` 1–50 (padrão 20); ordem `name, id`.
- [ ] `q` com menos de 2 caracteres → 400; `q` com 11 dígitos (com ou sem máscara) → busca por CPF; senão, por trecho do nome sem diferenciar caixa, com `%`, `_` e `\` tratados como texto.
- [ ] `status` = `active` (padrão), `inactive` ou `all`.
- [ ] A lista traz só id, nome, telefone, nascimento, convênio e `active`.
- [ ] p95 ≤ 100 ms (10 mil pacientes, 4 concorrentes).

### US-003: Ver e editar paciente
**Descrição:** Como recepcionista ou dentista, quero ver e corrigir a ficha.

**Critérios de aceite:**
- [ ] `GET` e `PUT /api/v1/patients/{id}` (D, R); id de outra clínica → 404.
- [ ] `PUT` exige `version`; defasada → 409.
- [ ] Paciente anonimizado: `GET` devolve a ficha anonimizada; `PUT` → 409.
- [ ] Nenhum campo de prontuário na resposta.

### US-004: Inativar e reativar
**Descrição:** Como dentista, quero inativar um paciente sem perder o histórico.

**Critérios de aceite:**
- [ ] `PATCH /api/v1/patients/{id}/active` `{active}` só para D (R → 403).
- [ ] Inativar grava `active = false` e `deactivated_at`; reativar zera `deactivated_at`; repetir o mesmo estado é idempotente (200).
- [ ] Paciente anonimizado não é reativado (409).
- [ ] Não existe `DELETE` de paciente.

## 3. Requisitos funcionais

- **FR-1:** Cadastrar, buscar, editar e inativar conforme o contrato.
- **FR-2:** Validar CPF, telefone, e-mail, nascimento, tamanhos e coerência convênio/conhecido no servidor, com todos os erros juntos.
- **FR-3:** Controle otimista (`version`) na edição.
- **FR-4:** Expor a `PatientLookup` (`requireExists`, `requireWritable`) para F03, F04 e F05.
- **FR-5:** Mapear `Patient` com `anonymizedAt` somente leitura e colunas imutáveis com `updatable = false`.
- **FR-6:** Rejeitar `clinicId`, `createdBy`, `id` e `role` vindos do cliente.

## 4. Fora do escopo

- Prontuário, exames, auditoria e anonimização (F03–F05).
- Busca por telefone, e-mail ou acento-insensível; busca em campo cifrado.
- Exclusão física; importação em lote; foto do paciente.
- Responsável legal e consentimento LGPD.

## 5. Considerações técnicas e riscos

- Os índices trigram e de prefixo da V008 não são usados sob RLS (`LIKE` não é *leakproof*); a meta de 100 ms depende da varredura filtrada (≈ 5 ms medido no banco).
- O CPF viaja na query string da busca; aceito no portfólio (alternativa: `POST /patients/search`).
- O banco permite a R alterar `active`; o backend restringe a D.
- **Pergunta aberta:** nenhuma.
