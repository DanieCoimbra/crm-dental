# PRD — F02 Catálogo de procedimentos e procedimento na agenda

**Derivação:** PRD global da Fase 3, US-P3-01 e US-P3-02, FR-1 e FR-10; padrões "Procedimento inativo" e "Agenda × procedimento". Spec global, seções 2.2 (`ProcedureType`, `Appointment`), 3.2, 4.3 e 4.11. Divergência D1 (o `PUT` do agendamento **substitui** `procedureTypeId`).
**Dependências externas:** F01 (`Money`, tradução de `23505`, regras de rota, `Phase3ItSupport`), migrations V022 (`procedure_types`) e V023 (FK em `appointments`), `scheduling` das Fases 2 (`AppointmentService`, `AppointmentRequests/Response`). Requer o access token da Fase 0.

## 1. Introdução

- **Problema:** o dentista não consegue cadastrar o que cobra nem dizer qual procedimento é o de um agendamento; o orçamento (F03) e a agenda precisam de um catálogo com preço-base, duração e cor.
- **Solução:** o módulo `catalog` com o CRUD sem `DELETE` de tipos de procedimento e a interface pública `ProcedureTypeLookup`; a agenda passa a mapear, aceitar e devolver o procedimento.
- **Metas:**
  1. 100% das rotas do catálogo na matriz de papéis: D grava; D e R leem; recepção recebe 403 em toda escrita.
  2. Nome repetido entre os ativos (qualquer caixa) retorna 409; o mesmo nome em outra clínica e um nome inativo reaproveitado passam.
  3. Agendamento com procedimento inativo é recusado (422); a listagem da agenda resolve os procedimentos em **uma** consulta (sem N+1).
  4. Os testes da Fase 2 continuam verdes; cobertura ≥ 80% em `ProcedureTypeService` e nas classes de serviço alteradas de `scheduling`.

## 2. User stories

### US-001: Cadastrar e editar tipos de procedimento
**Descrição:** Como dentista, quero cadastrar os procedimentos que realizo, com preço-base, duração e cor.

**Critérios de aceite:**
- [ ] `POST /api/v1/procedure-types` (só D) recebe `name` (2–80), `basePrice` (0 a 999999.99, texto decimal), `defaultDurationMinutes` (5–720, múltiplo de 5) e `color` (`#RRGGBB`); devolve 201 com `id`, `active=true`, `version` e `Location`. `clinicId`, `createdBy`, `id` e `active` nunca vêm do corpo.
- [ ] `PUT /procedure-types/{id}` (só D, com `version`) altera os quatro campos; versão defasada → 409. Alterar o preço não altera orçamentos existentes.
- [ ] `PATCH /procedure-types/{id}/active` (só D, `{active, version}`) ativa e inativa; não existe `DELETE`. Reativar um nome que colide com outro ativo → 409.
- [ ] Nome repetido entre os **ativos** da clínica, sem diferenciar caixa → 409 "Já existe um procedimento ativo com este nome."; mesmo nome em outra clínica e nome de inativo reaproveitado passam.
- [ ] A validação devolve todos os campos inválidos de uma vez (400 com `errors[]`); o `23514` do banco é a segunda barreira.

### US-002: Consultar o catálogo
**Descrição:** Como dentista ou recepcionista, quero listar os procedimentos para usar no orçamento e na agenda.

**Critérios de aceite:**
- [ ] `GET /procedure-types?active=true|false|all` (D, R; padrão `true`) ordenado por nome sem diferenciar caixa e `id`, sem paginação; valor inválido de `active` → 400.
- [ ] `GET /procedure-types/{id}` (D, R); de outra clínica → 404. Cada item traz `id`, `name`, `basePrice`, `defaultDurationMinutes`, `color`, `active`, `version`; nunca `clinicId` nem `createdBy`.
- [ ] Resposta com `Cache-Control: no-store`.

### US-003: Procedimento no agendamento
**Descrição:** Como dentista ou recepcionista, quero indicar o procedimento de um agendamento.

**Critérios de aceite:**
- [ ] `POST /appointments` e `PUT /appointments/{id}` (D, R) aceitam `procedureTypeId` opcional; inexistente na clínica → 404; inativo → 422 com `reason: PROCEDURE_INACTIVE`.
- [ ] **No `PUT`, o campo substitui o valor atual (D1):** ausente ou `null` remove o vínculo; o cliente reenvia o atual para mantê-lo.
- [ ] A resposta e a listagem da agenda trazem `procedure {id, name, color}` (nulo se não houver), em lote; `GET /appointments/{id}` e o histórico do paciente também.
- [ ] `endsAt` segue obrigatório (o front propõe a duração com `defaultDurationMinutes`); a máquina de status, o conflito e a regra de horário não mudam.
- [ ] Agendamentos existentes (campo nulo) seguem válidos; os testes da Fase 2 passam.

## 3. Requisitos funcionais

- **FR-1:** Módulo `catalog` com `ProcedureType`, `ProcedureTypeRepository`, `ProcedureTypeService`, `ProcedureTypeController` e DTOs.
- **FR-2:** Interface pública `ProcedureTypeLookup`: `requireActive(id)`, `briefs(ids)` e `quotes(ids)`, cada uma em uma consulta.
- **FR-3:** `Appointment` mapeia `procedure_type_id`; `AppointmentService.create/reschedule` e os DTOs aceitam e devolvem o procedimento; `AppointmentView` carrega o mapa de resumos.
- **FR-4:** `@PreAuthorize("hasRole('DENTIST_ADMIN')")` nas escritas; leituras `hasAnyRole`.
- **FR-5:** Eventos de log `ProcedureType.Created/Updated/ActiveChanged` após o commit, só com identificadores (nunca o preço).
- **FR-6:** `Phase3SchemaValidateIT` ganha `ProcedureType` e a `Appointment` alterada.

## 4. Fora do escopo

- Duração do agendamento calculada pelo backend a partir do procedimento (o front propõe).
- Seed de procedimentos padrão, preço por convênio, tabelas de preço múltiplas.
- Orçamento e itens (F03); `DELETE` de procedimento.
- Migration ou alteração de banco.

## 5. Considerações técnicas e riscos

- O `PUT` do agendamento que **não** reenvia `procedureTypeId` apaga o vínculo (D1): o front precisa reenviar. Documentar na API.
- O índice único parcial do banco cobre só os **ativos**; reativar pode colidir (409), caso de borda tratado por `23505`.
- `quotes` é lido sem trava; o preço pode mudar entre a leitura e a gravação do item (F03 trata o `23514` residual).
- Inativar um procedimento não afeta agendamentos e itens já gravados.
