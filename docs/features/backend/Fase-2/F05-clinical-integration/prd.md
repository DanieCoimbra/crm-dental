# PRD — F05 Integração com o núcleo clínico

**Derivação:** PRD global da Fase 2, US-P2-10, US-P2-11 e US-P2-12, FR-9 e FR-10; decisões G2 e G3. Spec global, seções 4.8, 4.9 e 4.10.
**Dependências externas:** F03 (`Appointment`, repositório), F04 (cancelamento por status), F01 (`PatientLookup`, tradução de `CR004`), módulos `patient` e `record` da Fase 1, migrations V019 (vínculo da nota) e V021 (anonimização). Requer o access token da Fase 0.

## 1. Introdução

- **Problema:** a agenda precisa conversar com o paciente e o prontuário: inativar um paciente não pode deixar horários ocupados, a anonimização precisa respeitar agendamentos futuros e a nota clínica deve poder apontar para o atendimento em que ocorreu.
- **Solução:** histórico de agendamentos do paciente, evento síncrono de inativação que cancela os futuros na mesma transação, tradução de `CR004` na anonimização e `appointmentId` opcional na nota (no máximo uma nota por atendimento, serializada por trava de linha).
- **Metas:**
  1. Inativar paciente cancela 100% dos futuros em aberto na mesma transação; falha no cancelamento desfaz a inativação.
  2. `IN_PROGRESS` do paciente bloqueia a inativação (409).
  3. Duas notas simultâneas para o mesmo atendimento resultam em exatamente uma gravada.
  4. Nenhum ciclo entre `patient`, `record` e `scheduling`.

## 2. User stories

### US-001: Histórico de agendamentos do paciente
**Descrição:** Como recepcionista ou dentista, quero ver os agendamentos de um paciente.

**Critérios de aceite:**
- [ ] `GET /api/v1/patients/{id}/appointments?page&size` (D, R) devolve `PageResponse` (`size` máx. 50) ordenado por `startsAt DESC, id DESC`, com todos os status.
- [ ] Paciente inexistente ou de outra clínica → 404; inativo ou anonimizado continua consultável.
- [ ] A resposta não traz prontuário, só os campos do agendamento.

### US-002: Inativar e anonimizar com a agenda
**Descrição:** Como dentista, quero inativar ou anonimizar um paciente sem deixar horários ocupados por quem saiu da clínica.

**Critérios de aceite:**
- [ ] `PATCH /patients/{id}/active` com `active=false` cancela, na mesma transação, os agendamentos futuros (`ends_at > agora`) `SCHEDULED`/`CONFIRMED` do paciente; falha no cancelamento reverte a inativação.
- [ ] Com um `IN_PROGRESS` do paciente, retorna 409 "Finalize o atendimento antes de inativar o paciente."
- [ ] Cada cancelamento gera sua linha de auditoria (autor = dentista do token); agendamentos passados não são tocados; reativar o paciente não restaura nada.
- [ ] Inativar de novo um paciente já inativo é idempotente e não cancela nada.
- [ ] `POST /patients/{id}/anonymize`: `CR004` → 409 "Cancele os agendamentos futuros antes de anonimizar."; depois da anonimização os `notes` dos agendamentos ficam nulos e `startsAt`, `endsAt`, `status` permanecem.

### US-003: Nota clínica vinculada ao atendimento
**Descrição:** Como dentista, quero associar a nota clínica ao agendamento em que o atendimento ocorreu.

**Critérios de aceite:**
- [ ] `POST /patients/{id}/notes` e `PUT …/notes/{noteId}` (só D) aceitam `appointmentId` opcional; a resposta da nota traz `appointmentId` (nulo se não vinculada).
- [ ] O agendamento deve existir na clínica (senão 404) e ser do mesmo paciente (senão 409).
- [ ] No máximo uma nota por atendimento: a segunda → 409; a nota já vinculada pode ser reeditada.
- [ ] No `PUT`, `appointmentId` **substitui** o vínculo (como o restante do corpo): preenchido vincula ou troca o atendimento; `null` ou ausente **desvincula**. Assim o dentista corrige uma nota ligada ao atendimento errado sem ficar preso.
- [ ] Desvincular não exige trava nem checagem de atendimento; a nota desvinculada libera o atendimento para outra nota. Trocar o vínculo aplica as mesmas regras de vincular ao novo atendimento (mesmo paciente, uma nota por atendimento).
- [ ] Notas existentes seguem válidas com `appointmentId` nulo; o conteúdo continua cifrado e fora de log; recepção continua com 403 em notas.

## 3. Requisitos funcionais

- **FR-1:** `PatientAppointmentController` em `scheduling` (rota sob `/patients`), consultando por `PatientLookup.requireExists`.
- **FR-2:** `PatientEvents.Deactivating` publicado por `PatientService` antes de gravar, só na transição ativo → inativo.
- **FR-3:** `PatientDeactivationListener` (`@EventListener` síncrono) no módulo `scheduling`.
- **FR-4:** `PatientAnonymizationService` traduz `CR004`.
- **FR-5:** `AppointmentLookup` (interface pública) com `requireOfPatient` e `lockOfPatient` (`PESSIMISTIC_WRITE`); `ClinicalNoteRepository.existsByAppointmentIdAndIdNot`.
- **FR-6:** Evento `AutoCanceled(patientId, quantidade)` para log após o commit.

## 4. Fora do escopo

- Criar nota automaticamente no `finish`.
- Restaurar agendamentos ao reativar o paciente.
- Prontuário no histórico do paciente da agenda.

## 5. Considerações técnicas e riscos

- Os testes da Fase 1 que inativam pacientes precisam ser revistos; a verificação da fase reexecuta a suíte da Fase 1.
- Cancelar em lote gera uma linha de auditoria por agendamento.
- A trava `PESSIMISTIC_WRITE` sob RLS só é validada para `DENTIST_ADMIN` (único que escreve notas).
