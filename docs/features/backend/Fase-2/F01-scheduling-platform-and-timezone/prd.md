# PRD — F01 Plataforma da agenda e fuso da clínica

**Derivação:** PRD global da Fase 2, US-P2-01 e US-P2-13 (parte `tableName=appointments`), FR-3, FR-11, FR-13, FR-14 e FR-16; seção 2 (módulos alterados). Spec global, seções 1.1, 2.2, 3.1, 4.13, 5.1 e 9.
**Dependências externas:** Fases 0 e 1 (tenant, `GlobalExceptionHandler`, `DatabaseErrors`, `PatientLookup`, `AuditQueryService`, `Clock`), migrations V014 (fuso), V015 (agendamentos) e V020 (auditoria). Requer o access token da Fase 0.

## 1. Introdução

- **Problema:** a agenda precisa de uma base comum antes de qualquer endpoint: o fuso da clínica (para entender "hoje" e "esta semana"), o mapeamento das entidades novas, a tradução dos erros do banco da agenda e as interfaces entre módulos sem ciclo.
- **Solução:** `Clinic.timezone` com `GET/PUT /clinic`, o esqueleto do módulo `scheduling` (entidades e repositórios mapeados com `ddl-auto=validate`), a tradução de `23P01`, `CR004` e `CR005`, `PatientLookup` estendido, `audit-log` aceitando `appointments` e o teste de arquitetura cobrindo `scheduling`.
- **Metas:**
  1. `ddl-auto=validate` passa contra V001–V021 com todas as entidades novas e alteradas (fecha o teste 84 do banco).
  2. Todo cálculo de dia, semana e expediente das demais features lê o fuso da clínica do token via `ClinicZones`, nunca uma constante.
  3. `23P01`, `CR003`, `CR004` e `CR005` viram 409 sem expor `DETAIL` do driver.
  4. Nenhum ciclo entre módulos (teste de arquitetura).

## 2. User stories

### US-001: Fuso da clínica
**Descrição:** Como dentista, quero que a clínica tenha um fuso para o sistema entender "hoje" e "esta semana" na hora local.

**Critérios de aceite:**
- [ ] `GET /api/v1/clinic` (D, R) devolve `timezone` (padrão `America/Sao_Paulo` nas clínicas existentes).
- [ ] `PUT /api/v1/clinic` (só D) aceita `timezone` opcional e mantém o valor atual quando ausente.
- [ ] Nome de fuso desconhecido retorna 400 com o campo (validado por `ZoneId` antes do banco; o `23514` do banco é a segunda barreira).
- [ ] `/auth/register` segue funcionando: a entidade `Clinic` envia `America/Sao_Paulo` no `INSERT`.
- [ ] `ClinicZones.zone()` devolve o fuso da clínica do token; fuso inválido gravado por fora retorna 500 `internal`.

### US-002: Mapeamento e tradução de erros da agenda
**Descrição:** Como desenvolvedor, quero as entidades e os erros da agenda prontos para as features seguintes.

**Critérios de aceite:**
- [ ] `Appointment`, `BusinessHour`, `ClinicHoliday`, `NationalHoliday` e os repositórios existem e passam em `ddl-auto=validate`.
- [ ] `23P01` (`ex_appt_no_overlap`) → 409 "Horário já ocupado."; `CR003` → 409; `CR004` → 409; `CR005` → 409; `23505` de `ux_bh_clinic_weekday` e `ux_clinic_holidays_date` → 409 com mensagem específica.
- [ ] `BusinessException` aceita `reason` opcional, devolvido como propriedade do `ProblemDetail`.

### US-003: Interfaces entre módulos e auditoria
**Descrição:** Como desenvolvedor, quero os pontos de contato entre módulos sem ciclo.

**Critérios de aceite:**
- [ ] `PatientLookup.requireSchedulable(id)` (404 / 409 anonimizado / 409 inativo) e `briefs(ids)` (nome e telefone em lote).
- [ ] `GET /api/v1/audit-log?tableName=appointments` deixa de ser 400.
- [ ] `Phase*ArchitectureTest` cobre `scheduling`: `patient`, `clinic` e `shared` não dependem dele.
- [ ] `Cache-Control: no-store` nas rotas da agenda.

## 3. Requisitos funcionais

- **FR-1:** Mapear `Clinic.timezone` e expor `ClinicZones` em `clinic.service`.
- **FR-2:** Mapear as quatro entidades novas e `ClinicalNote.appointmentId`, sem `@ToString`/`@Data`/`@EqualsAndHashCode`.
- **FR-3:** Estender `DatabaseErrors` e `GlobalExceptionHandler` conforme a tabela 5.1 da spec global.
- **FR-4:** Estender `PatientLookup` e `AuditQueryService.TABLES`.
- **FR-5:** Estender o componente de `no-store` e o teste de arquitetura.

## 4. Fora do escopo

- Endpoints de agendamento, horário comercial e feriado (F02 a F04).
- Evento de inativação e vínculo da nota (F05).
- Alinhar `PatientRules.CLINIC_ZONE` ao fuso da clínica.

## 5. Considerações técnicas e riscos

- Nomes de fuso do Java podem não existir em `pg_timezone_names` (e vice-versa); o 400 do backend e o `23514` do banco cobrem a divergência.
- O fuso só é alterado pelo dentista; mudar o fuso não revalida agendamentos existentes.
