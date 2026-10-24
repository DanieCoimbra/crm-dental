# Spec — F01 Plataforma da agenda e fuso da clínica

Complementa `docs/global/backend/Fase-2/spec.md` (seções 1.1, 2.2, 3.1, 4.13, 5.1 e 9).

## 1. Visão técnica
- **Feature:** F01 Plataforma da agenda e fuso.
- **Stack:** Spring Data JPA/Hibernate 7 (`validate`), Spring Web MVC, Spring Security, ArchUnit.
- **Abordagem:** acrescentar o esqueleto do módulo `scheduling` (domain e repository) e as interfaces públicas; sem endpoint de agenda. Sem migrations: usa V014, V015, V016, V017 e V019.

## 2. Dados
- **Entidades novas** (`crm_data.scheduling.domain`): `Appointment`, `AppointmentStatus`, `BusinessHour`, `ClinicHoliday` (estendem `TenantAwareEntity`), `NationalHoliday` (`@Immutable`, sem tenant). Mapeamento da seção 2.2 da spec global; `procedure_type_id` **não** mapeada.
- **Alteradas:** `Clinic.timezone` (`String`, `nullable=false`, valor inicial `America/Sao_Paulo`); `ClinicalNote.appointmentId` (`UUID`, editável).
- **Repositórios:** `AppointmentRepository`, `BusinessHourRepository`, `ClinicHolidayRepository`, `NationalHolidayRepository` (métodos de F02 a F05 entram nas próprias features; aqui só a base).
- **Estado em memória:** nenhum.

## 3. Componentes
| Componente | Responsabilidade |
|---|---|
| `ClinicZones` / implementação (`clinic.service`) | `ZoneId zone()` da clínica do token; lê `clinics.timezone`; valor inválido → 500 `internal` |
| `ClinicService` (alterado) | `update` aceita `timezone` opcional, valida com `ZoneId.getAvailableZoneIds()` |
| `ClinicRequests.UpdateClinicRequest` / `ClinicResponse` | `+ timezone` (`@Size(max=50)`) |
| `PatientLookup` (alterado) | `requireSchedulable(UUID)`; `briefs(Collection<UUID>)` → `Map<UUID, PatientBrief(id,name,phone)>` |
| `DatabaseErrors` (alterado) | `isExclusionViolation`, `isRule("CR004"/"CR005")`; constraint name já existe |
| `GlobalExceptionHandler` / `ConflictMessages` | Mapeamento da tabela 5.1; `reason` em `BusinessException` copiado para o `ProblemDetail` |
| `AuditQueryService` | `TABLES += appointments` |
| `ClinicalCacheHeaders` | `no-store` nas rotas `/appointments/**`, `/business-hours`, `/holidays`, `/clinic-holidays/**` e `/patients/*/appointments` |
| `Phase*ArchitectureTest` | Regras de dependência da seção 1.1 da spec global |

## 4. Lógica
1. `PUT /clinic`: ler `timezone` do corpo; ausente → manter; presente → `ZoneId.getAvailableZoneIds().contains(valor)` senão 400 no campo `timezone`; gravar com a `version` (controle otimista existente).
2. `ClinicZones.zone()`: `ClinicRepository.findById(clinicId do token)` → `ZoneId.of(timezone)`; sem cache.
3. `requireSchedulable`: carrega o paciente no tenant (404); anonimizado → 409; inativo → 409.
4. Tradução de erros: `DatabaseErrors` lê `SQLState` e constraint da cadeia de causas; o handler monta o `ProblemDetail` sem a mensagem do driver.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| `timezone` fora da lista do Java | 400 `validation` no campo |
| Java aceita e o Postgres rejeita (`23514`) | 500 `internal` com log do nome da constraint (divergência de catálogo; ver spec global, seção 9, item 2) |
| Recepção chama `PUT /clinic` | 403 |
| `Clinic` gravada sem fuso (defeito) | Impossível pela entidade; o banco tem `NOT NULL` |
| `23P01` só no commit | Handler global traduz pela causa |

## 6. Segurança e desempenho
- `PUT /clinic` só D (rota e `@PreAuthorize`); o banco impede a recepção de trocar o fuso (`42501`).
- `ClinicZones` custa uma consulta por chave primária por requisição.
- Nenhum dado pessoal em log ou mensagem de erro.
