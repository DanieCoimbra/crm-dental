# Spec — F02 Catálogo de procedimentos e procedimento na agenda

Complementa `docs/global/backend/Fase-3/spec.md` (seções 2.2, 3.1, 3.2, 4.3 e 4.11).

## 1. Visão técnica
- **Feature:** F02 Catálogo e procedimento na agenda.
- **Stack:** Spring Web MVC, Spring Data JPA (JPQL), Bean Validation, Spring Security, `Money` (F01).
- **Abordagem:** módulo novo `crm_data.catalog` (`web → service → repository`, `domain`); mudanças pontuais em `crm_data.scheduling`. Sem migrations: usa V022 e V023.

## 2. Dados
- **Tabelas:** `core.procedure_types` (`ux_ptype_clinic_name`, `ux_ptype_id_clinic`) e `core.appointments.procedure_type_id` (`fk_appt_procedure`, `ix_appt_procedure`). Nenhuma alteração de schema.
- **`ProcedureType`** (`TenantAwareEntity`): `id UUID`, `name`, `basePrice` (`BigDecimal`, escala 2), `defaultDurationMinutes Short`, `color`, `active`, `createdBy` (`updatable = false`), `createdAt`/`updatedAt` (somente leitura), `@Version Long`; `save` de entidade nova cai em `persist` (id atribuído, versão nula).
- **`Appointment`:** `+ procedureTypeId UUID` (coluna `procedure_type_id`, editável, sem `@ManyToOne`).
- **Repositório:** `findAllByActive(boolean, Sort)` e `findAll(Sort)`; `findAllById` para `briefs`/`quotes` (filtro de tenant aplicado por consulta JPQL `id IN :ids`).
- **Estado em memória:** nenhum.

## 3. Componentes
| Componente | Responsabilidade |
|---|---|
| `ProcedureTypeController` | `GET /procedure-types`, `GET /procedure-types/{id}`, `POST`, `PUT /{id}`, `PATCH /{id}/active` |
| `ProcedureTypeService` | CRUD; escritas só D (`@PreAuthorize`); traduz `23505`; eventos |
| `ProcedureTypeLookup` / `ProcedureTypeLookupService` | `requireActive(UUID)` (404 / 422 `PROCEDURE_INACTIVE`), `Map<UUID, ProcedureBrief> briefs(Collection<UUID>)`, `Map<UUID, ProcedureQuote> quotes(Collection<UUID>)` |
| `ProcedureBrief(id, name, color)` / `ProcedureQuote(id, name, basePrice, active)` | Valores imutáveis; `toString` sem preço |
| `ProcedureTypeRequests` / `ProcedureTypeResponse` | DTOs; `basePrice` em texto decimal (`Money`) |
| `CatalogEvents` / `CatalogAuditLogger` | `Created`, `Updated`, `ActiveChanged`; log após commit, só ids |
| `AppointmentService` (alterado) | `create(..., procedureTypeId)` e `reschedule(..., procedureTypeId)` chamam `requireActive` quando preenchido; `AppointmentView` ganha `Map<UUID, ProcedureBrief>` |
| `AppointmentRequests` / `AppointmentResponse` (alterados) | `+ procedureTypeId` na entrada; `+ procedure {id, name, color}` na saída |

## 4. Lógica
**Criar:** (1) validar (nome 2–80 após `strip`; `Money.parse`; duração 5–720 e múltipla de 5; cor `^#[0-9A-Fa-f]{6}$`) → 400 com `errors[]`; (2) `new ProcedureType(UUID.randomUUID(), …, active = true, createdBy = userId)`; (3) `save` + `flush`, traduzindo `23505` (`ux_ptype_clinic_name`) para 409; (4) evento e 201 com `Location`.

**Editar:** carregar (404); `version` do corpo ≠ da entidade → 409; atualizar nome, preço, duração e cor; `flush` com a mesma tradução.

**Ativar/inativar:** `version`; setar `active`; `flush` (a reativação pode colidir por `23505` → 409).

**Listar:** `active=true` (padrão), `false` ou `all`; ordenação por `lower(name)` e `id` em JPQL; sem paginação.

**Lookup:** `requireActive` carrega por `id` (filtro do tenant); ausente → 404 "Procedimento não encontrado."; `active = false` → `BusinessException(BUSINESS_RULE, …, "PROCEDURE_INACTIVE")`. `briefs` e `quotes`: uma consulta `id IN :ids`; id desconhecido simplesmente não aparece no mapa.

**Agenda:** em `create`/`reschedule`, se `procedureTypeId ≠ null`, `requireActive` **antes** de gravar; no `PUT`, `procedureTypeId` do corpo (ausente = `null`) **substitui** o campo da entidade. Na montagem de `AppointmentView`, coletar os `procedureTypeId` da lista e chamar `briefs` uma vez (junto de `PatientLookup.briefs`).

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Nome ativo repetido (qualquer caixa) | 409 "Já existe um procedimento ativo com este nome." |
| Reativar procedimento cujo nome já está ativo em outro | 409 (mesma tradução) |
| `version` defasada | 409 `STALE_VERSION` |
| Procedimento de outra clínica / inexistente | 404 |
| `procedureTypeId` inativo na agenda | 422 `PROCEDURE_INACTIVE` |
| Preço negativo, duração fora da faixa ou não múltipla de 5, cor mal formada, nome em branco | 400 com `errors[]` |
| Recepção em escrita | 403 antes do banco |
| `PUT /appointments` sem `procedureTypeId` num agendamento que tinha | Vínculo removido (D1) |
| `PUT /appointments` com procedimento inativo igual ao atual | 422 (a regra vale para qualquer valor preenchido) |

## 6. Segurança e desempenho
- Escritas só D no `SecurityConfig` e no `@PreAuthorize`; `clinicId` e `createdBy` só do token; `active` e `id` nunca do cliente.
- Log só com `procedureTypeId`/`userId`; nunca o preço nem o nome.
- Lista de até dezenas de linhas: p95 ≤ 100 ms; `briefs`/`quotes` em uma consulta; a agenda não ganha consulta por item.
