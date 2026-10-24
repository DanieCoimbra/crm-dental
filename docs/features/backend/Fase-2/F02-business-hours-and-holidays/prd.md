# PRD — F02 Horário comercial e feriados

**Derivação:** PRD global da Fase 2, US-P2-02 e US-P2-03, FR-7 e FR-12 (parte `business_hours`/`clinic_holidays`). Spec global, seções 4.11 e 4.12.
**Dependências externas:** F01 (entidades `BusinessHour`, `ClinicHoliday`, `NationalHoliday`, `Appointment` e `AppointmentRepository` base, `PatientLookup.briefs`, tradução de `23505`, `ClinicZones`), migrations V016, V017 e V018 (seed 2026–2030). Requer o access token da Fase 0.

## 1. Introdução

- **Problema:** o dentista precisa dizer quando atende e quais dias a clínica fecha; sem isso a agenda não tem como bloquear a recepção fora do expediente (G1).
- **Solução:** leitura e configuração em lote dos 7 dias da semana e CRUD dos feriados da clínica, além da leitura dos feriados nacionais já cadastrados.
- **Metas:**
  1. Recepção recebe 403 em 100% das escritas da feature.
  2. Dia sem linha é sempre "fechado e não configurado".
  3. Nenhuma linha de outra clínica aparece ou é alterada.

## 2. User stories

### US-001: Horário comercial
**Descrição:** Como dentista, quero configurar os dias e horários em que atendo, com intervalo de almoço.

**Critérios de aceite:**
- [ ] `GET /api/v1/business-hours` (D, R) devolve os 7 dias (1 = segunda … 7 = domingo); dia sem linha vem como `configured: false, isOpen: false`.
- [ ] `PUT /api/v1/business-hours` (só D) recebe de 1 a 7 dias distintos e grava tudo na mesma transação (cria o dia na primeira vez, atualiza depois com `version`); `version` defasada ou ausente em dia já existente retorna 409.
- [ ] Validação com a lista de campos inválidos: aberto exige `opensAt < closesAt`; almoço com as duas pontas, dentro do expediente e início antes do fim; fechado não aceita horários; `weekday` fora de 1–7 ou repetido retorna 400. Horários em `HH:mm` locais, máximo 23:59.
- [ ] Não existe `DELETE`: fechar o dia é `isOpen=false`.
- [ ] Recepção recebe 403 no `PUT`; clínica de outro tenant nunca é lida ou alterada.

### US-001b: Aviso de agendamentos afetados
**Descrição:** Como dentista, quero ser avisado, antes ou ao mudar o expediente, de quais agendamentos já marcados ficam fora do novo horário.

**Critérios de aceite:**
- [ ] A resposta do `PUT /business-hours` traz `affectedAppointments`: os agendamentos futuros (`endsAt` > agora) em `SCHEDULED`/`CONFIRMED` que, pela nova configuração, caem em dia fechado, fora do expediente ou no almoço, cada um com `id`, `patient {id, name, phone}`, `startsAt`, `endsAt`, `status` e `reason` (`CLOSED_DAY`, `OUTSIDE_HOURS`, `LUNCH`), mais `affectedCount` e `truncated` (lista limitada a 100 itens, ordenada por `startsAt`).
- [ ] `PUT /business-hours?dryRun=true` faz toda a validação (inclusive `version`) e devolve o mesmo aviso **sem gravar** (`saved: false`); sem o parâmetro grava e devolve `saved: true`.
- [ ] O aviso é informativo: nada é cancelado, remarcado nem bloqueado; agendamentos `IN_PROGRESS`, `COMPLETED`, `CANCELED`, `NO_SHOW` e os já terminados não entram.
- [ ] A avaliação usa a configuração **resultante completa**: os dias do corpo mais o estado atual dos demais; dia sem linha conta como fechado (`CLOSED_DAY`).
- [ ] Sem agendamentos afetados: `affectedAppointments: []`, `affectedCount: 0`.

### US-002: Feriados
**Descrição:** Como dentista, quero ver os feriados nacionais e acrescentar os da minha clínica.

**Critérios de aceite:**
- [ ] `GET /api/v1/holidays?from&to` (D, R), janela máxima de 366 dias, devolve a união de feriados nacionais e da clínica, com `date`, `name`, `source` (`NATIONAL`/`CLINIC`) e, para os da clínica, `id` e `version`.
- [ ] `POST /api/v1/clinic-holidays` (só D) cria `{date, name}` (nome 2–80, data ≥ 2000-01-01); data repetida na clínica → 409; mesma data em outra clínica e data nacional repetida são aceitas.
- [ ] `PUT /api/v1/clinic-holidays/{id}` (só D) altera nome e data com `version`; `DELETE` (só D) remove fisicamente e responde 204.
- [ ] Não há escrita em feriado nacional; recepção recebe 403 nas escritas; feriado de outra clínica retorna 404.
- [ ] `POST` e `PUT /clinic-holidays` devolvem também `affectedAppointments` (mesmo formato, `reason: HOLIDAY`) para a data do feriado criado ou movido; o `DELETE` não gera aviso.

## 3. Requisitos funcionais

- **FR-1:** `BusinessHoursService` com leitura dos 7 dias e `PUT` em lote transacional.
- **FR-2:** Validação coerente com os `CHECK`s `ck_bh_open` e `ck_bh_lunch`, devolvendo todos os erros.
- **FR-3:** `HolidayService` com a união nacional ∪ clínica e o CRUD dos feriados da clínica.
- **FR-4:** Expor aos demais serviços do módulo a leitura dos 7 dias e dos feriados de um período (usadas por F03).
- **FR-5:** Controle otimista (`version`) e `@PreAuthorize` D nas escritas.
- **FR-6:** Criar nesta feature a regra pura `AppointmentSlotRules` (e o registro `DaySchedule`), usada aqui para calcular o impacto e depois pela F03 para validar a criação e a remarcação.
- **FR-7:** `AffectedAppointmentsFinder` consulta os agendamentos futuros em aberto (`SCHEDULED`/`CONFIRMED`, `endsAt` > agora) e os avalia contra a configuração resultante, sem alterar nenhum; resumo dos pacientes em lote.
- **FR-8:** `dryRun=true` valida e calcula, sem gravar.

## 4. Fora do escopo

- Fechamento às 24:00 e horário que atravessa a meia-noite.
- Feriados municipais prontos e feriados nacionais depois de 2030 (nova migration do banco).
- Cancelar, remarcar ou bloquear automaticamente os agendamentos afetados (o aviso é só informativo; a decisão é do dentista).
- Aviso ao remover um feriado da clínica ou ao mudar o fuso.

## 5. Considerações técnicas e riscos

- Seed de feriados nacionais termina em 2030; depois disso a resposta traz só os da clínica.
- Linha gravada por fora com `24:00:00`: a leitura trata como 23:59 e registra aviso (sem valores).
