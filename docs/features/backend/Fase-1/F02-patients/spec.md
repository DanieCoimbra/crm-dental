# Spec — F02 Pacientes

Complementa `docs/global/backend/Fase-1/spec.md` (seções 2.2, 3, 4.2 a 4.4 e 5).

## 1. Visão técnica
- **Feature:** F02 Pacientes.
- **Stack:** Spring Web MVC, Spring Data JPA (`JpaRepository` + `JpaSpecificationExecutor`), Bean Validation, Spring Security (`@PreAuthorize`), Hibernate 7.
- **Abordagem:** módulo `crm_data.patient` (`web`, `service`, `domain`, `repository`). Sem migrations: usa `core.patients` (V008).

## 2. Dados
- **Tabela:** `core.patients`; nenhuma alteração. Colunas e constraints conforme V008.
- **Entidade `Patient`** (estende `TenantAwareEntity`): `id` atribuído (UUID); setters apenas nos campos editáveis; `createdBy` `updatable = false`; `createdAt`/`updatedAt` `insertable = false, updatable = false` (sem `@Generated`); **`anonymizedAt` `insertable = false, updatable = false`** (sem grant de coluna para `crm_app`); `sex` `@Enumerated(STRING)` (`Sex`); `@Version Long version`.
- **Métodos de domínio:** `deactivate(Instant)`, `activate()`, `isAnonymized()`.
- **Estado em memória:** nenhum.

## 3. Componentes
| Componente | Responsabilidade |
|---|---|
| `PatientController` | `POST/GET /patients`, `GET/PUT /patients/{id}`, `PATCH /patients/{id}/active`; valida `page/size` como o `UserController` da Fase 0 |
| `PatientRequests` | Records `CreatePatientRequest`, `UpdatePatientRequest` (+`version`), `SetActiveRequest`; normalização no construtor canônico (padrão de `RequestText`) |
| `PatientResponse` / `PatientSummaryResponse` | DTOs de saída (sem `clinicId` nem `createdBy`) |
| `PatientRules` | CPF (11 dígitos, dois dígitos verificadores, rejeita sequências iguais), telefone (só dígitos, 10–13), nascimento não futuro em `America/Sao_Paulo` |
| `PatientService` | Regras e transações; `@PreAuthorize` por método |
| `PatientLookup` (interface pública) | `void requireExists(UUID)` (404) e `void requireWritable(UUID)` (404 / 409 se anonimizado), via projeção JPQL de `anonymizedAt` |
| `PatientRepository` | `findAll(Specification, Pageable)`; projeção para `PatientLookup` |

## 4. Lógica
- **Criar:** normalizar → validar → `id = UUID.randomUUID()`, `createdBy = TenantContext.userId()` → `saveAndFlush` → violação de `ux_patients_clinic_cpf` vira 409 "CPF já cadastrado." (sem logar a causa) → `refresh` → 201.
- **Buscar:** `q` < 2 após `trim` → 400. Só dígitos/`.`/`-`/espaço com 11 dígitos → igualdade em `cpf`. Senão `lower(name) LIKE %q%` com `ESCAPE '\'` e `q` em minúsculas (`Locale.ROOT`). `status` filtra `active`. Sort fixo `name ASC, id ASC`.
- **Editar:** `findById` (a RLS esconde outra clínica → 404); anonimizado → 409; `version` do corpo ≠ atual → 409; aplica campos; `saveAndFlush`; `refresh`.
- **Ativar/desativar:** só D; anonimizado → 409; estado igual → no-op; senão grava `active`/`deactivatedAt` coerentes.
- **`requireWritable`:** projeção `select p.anonymizedAt from Patient p where p.id = :id` (filtro de tenant aplica); vazio → 404; não nulo → 409.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| `{id}` de outra clínica | 404 |
| CPF repetido na clínica | 409 (mensagem sem o CPF) |
| CPF inválido / telefone fora de 10–13 / nascimento futuro | 400 com `errors[]` |
| `version` defasada | 409 |
| Anonimizado: editar ou reativar | 409 (pré-checagem; `CR003` do trigger como reserva) |
| `q` curto ou `size` fora de 1–50 | 400 |
| Recepção em `PATCH /active` | 403 |
| Corpo com `clinicId`/`createdBy`/`id` | Ignorados |

## 6. Segurança e desempenho
- Rotas: `PATCH /api/v1/patients/*/active` exige D; as demais, autenticado (D, R). `@PreAuthorize` nos métodos como segunda barreira.
- JPQL/`Specification` parametrizados; nenhum SQL nativo.
- CPF e nome fora de log, `toString` e mensagens de erro.
- Meta: busca p95 ≤ 100 ms (10 mil pacientes, 4 concorrentes); listagem sem *sort* extra graças ao índice `ix_patients_clinic_name`.
