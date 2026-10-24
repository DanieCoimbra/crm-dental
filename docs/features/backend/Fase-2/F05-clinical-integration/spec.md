# Spec — F05 Integração com o núcleo clínico

Complementa `docs/global/backend/Fase-2/spec.md` (seções 1.1, 4.8, 4.9 e 4.10).

## 1. Visão técnica
- **Feature:** F05 Integração com o núcleo clínico.
- **Stack:** Spring Web MVC, Spring Data JPA (`PESSIMISTIC_WRITE`), eventos Spring (`@EventListener` síncrono), Spring Security.
- **Abordagem:** `scheduling` escuta o evento publicado por `patient`; `record` consulta `scheduling` por `AppointmentLookup`. Sem migrations: usa V019 e V021.

## 2. Dados
- **Tabelas:** `core.appointments` (cancelamento e histórico), `core.clinical_notes.appointment_id` (V019), `core.anonymize_patient` com `CR004` (V021).
- **Repositório:** `AppointmentRepository.findByPatient(pageable)` (ordem `startsAt DESC, id DESC`), `existsInProgressByPatient`, `findOpenFutureByPatient(now)`; `ClinicalNoteRepository.existsByAppointmentIdAndIdNot`.
- **Estado em memória:** nenhum.

## 3. Componentes
| Componente | Responsabilidade |
|---|---|
| `PatientAppointmentController` (`scheduling.web`) | `GET /patients/{id}/appointments` |
| `PatientEvents.Deactivating` (`patient.service`) | Evento `(clinicId, patientId)` |
| `PatientService` (alterado) | Publica o evento antes de gravar `active=false`, só se estava ativo |
| `PatientDeactivationListener` (`scheduling.service`) | `@EventListener`: bloqueia com `IN_PROGRESS`; cancela os futuros em aberto |
| `PatientAnonymizationService` (alterado) | `CR004` → 409 |
| `AppointmentLookup` (`scheduling.service`) | `requireOfPatient(appointmentId, patientId)` e `lockOfPatient(...)` |
| `ClinicalNoteService` / `RecordRequests` / `NoteResponse` (alterados) | `appointmentId` opcional; regra G3 |

## 4. Lógica
**Inativação:** `PatientService.setActive(id, false)`; se estava ativo, publica `Deactivating`; o listener, na mesma transação: (a) existe `IN_PROGRESS` do paciente → `BusinessException(CONFLICT)`; (b) carrega `SCHEDULED`/`CONFIRMED` com `endsAt > now`, marca `CANCELED` entidade a entidade e `flush`; (c) publica `AutoCanceled(patientId, quantidade)`. Exceção do listener reverte a inativação.

**Nota:** com `appointmentId` presente, `requireWritable(patientId)`; `lockOfPatient` (404 se não existe, 409 se de outro paciente, trava a linha); `existsByAppointmentIdAndIdNot` → 409 se houver outra nota; gravar. Na edição (`PUT`), `appointmentId` **substitui** o valor: preenchido e igual ao atual segue o fluxo acima (a própria nota é excluída da checagem); preenchido e diferente vincula ao novo atendimento com as mesmas regras; `null` ou ausente **desvincula** (grava `appointment_id = NULL`, sem `lockOfPatient` nem checagem de unicidade). `RecordRequests.UpdateNoteRequest` não distingue ausente de `null`: ambos significam desvincular.

**Anonimização:** o service traduz o `SQLState` `CR004` em 409; depois da função, `notes` nulos e horário/status mantidos (conferido em IT).

**Histórico:** `requireExists(patientId)`; página ordenada; `briefs` não necessário (o paciente é o mesmo), a resposta usa o resumo do paciente da rota.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Paciente com `IN_PROGRESS` | 409; inativação não persiste |
| Falha no cancelamento em lote | Exceção propaga; paciente continua ativo |
| Inativar paciente já inativo | Idempotente; sem evento |
| Agendamentos passados ou já `CANCELED`/`NO_SHOW` | Não tocados |
| Agendamento da nota de outro paciente | 409; de outra clínica/inexistente 404 |
| Duas notas simultâneas para o mesmo atendimento | Trava de linha serializa; a segunda 409 |
| Reedição da nota já vinculada | Passa (excluída da checagem) |
| `CR004` na anonimização | 409 |
| Paciente de outra clínica no histórico | 404 |

## 6. Segurança e desempenho
- Inativação e notas só D; histórico D e R; auditoria registra o autor de cada cancelamento.
- O listener roda na transação do publicador (`@EventListener`, não `@TransactionalEventListener`).
- Cancelamento em lote: N `UPDATE`s e N linhas de auditoria (pequeno, dentista solo); `ix_appt_patient` atende o histórico e o `CR004`.
- Log só com ids e contagens.
