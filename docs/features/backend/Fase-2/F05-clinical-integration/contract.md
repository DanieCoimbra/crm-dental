# Contract — F05 Integração com o núcleo clínico

## 1. Resumo
- **Feature:** F05-clinical-integration
- **Versão:** 1.0.0
- **Consumidores:** Frontend React (ficha do paciente, prontuário). Requer `Authorization: Bearer` da Fase 0.

## 2. Entradas

Prefixo `/api/v1`. **D** = `DENTIST_ADMIN`, **R** = `RECEPTIONIST`.

| Método | Rota | Papéis | Corpo / query |
|---|---|---|---|
| GET | `/patients/{id}/appointments` | D, R | `?page=0&size=20 (1–50)` |
| PATCH | `/patients/{id}/active` | D | `{ "active": bool }` (comportamento alterado) |
| POST | `/patients/{id}/anonymize` | D | — (comportamento alterado) |
| POST | `/patients/{id}/notes` | D | `{ "content", "notedAt"?, "appointmentId": uuid? }` |
| PUT | `/patients/{id}/notes/{noteId}` | D | `{ "content", "notedAt", "version", "appointmentId": uuid? }` |

## 3. Saídas

### Sucesso
- **Histórico** (200, `no-store`): `PageResponse<AppointmentResponse>` (F03), sem prontuário, ordenado por `startsAt` e `id` decrescentes.
- **Inativar** (200): `PatientResponse` com `active:false`.
- **Nota** (201/200): `NoteResponse` da Fase 1 acrescida de `appointmentId` (nulo quando não vinculada).

### Erros (formato da Fase 0)
| Status | Quando |
|---|---|
| 400 `validation` | `size` fora de 1–50; conteúdo da nota inválido |
| 401 `unauthorized` | Token ausente ou inválido |
| 403 `forbidden` | R em inativar, anonimizar ou notas |
| 404 `not-found` | Paciente, nota ou atendimento inexistente / de outra clínica |
| 409 `conflict` | Inativar com `IN_PROGRESS` ("Finalize o atendimento antes de inativar o paciente."); anonimizar com futuro em aberto ("Cancele os agendamentos futuros antes de anonimizar."); atendimento de outro paciente; já existe nota para o atendimento; `version` defasada |

## 4. Regras e limites
- Inativar cancela os agendamentos com `endsAt > agora` em `SCHEDULED`/`CONFIRMED`, na mesma transação; passados e finalizados não mudam; reativar o paciente não restaura.
- A anonimização zera `notes` dos agendamentos do paciente; `startsAt`, `endsAt` e `status` permanecem.
- No máximo uma nota por atendimento. No `PUT`, `appointmentId` **substitui** o vínculo: preenchido vincula ou troca; `null` ou ausente desvincula. O cliente que quer manter o vínculo deve reenviar o `appointmentId` atual (a resposta da nota sempre o traz). Desvincular libera o atendimento para outra nota.
- Paciente inativo ou anonimizado continua tendo o histórico consultável.

### Interfaces internas
```java
// crm_data.patient.service.PatientEvents
record Deactivating(UUID clinicId, UUID patientId) {}

// crm_data.scheduling.service.AppointmentLookup
void requireOfPatient(UUID appointmentId, UUID patientId);  // 404 | 409
void lockOfPatient(UUID appointmentId, UUID patientId);     // idem, com PESSIMISTIC_WRITE
```

## 5. Eventos emitidos (internos)
| Evento | Gatilho | Payload |
|---|---|---|
| `Patient.Deactivating` | Antes de gravar a inativação (síncrono, mesma transação) | `{clinicId, patientId}` |
| `Appointment.AutoCanceled` | Após o commit do cancelamento em lote | `{patientId, quantidade}` (sem nomes) |

## 6. Critérios de aceite (integração)
- [ ] Inativar paciente com 3 futuros em aberto cancela os 3 (auditoria de cada um, autor = dentista), preserva os passados e o paciente fica inativo.
- [ ] Inativar com `IN_PROGRESS` → 409 e o paciente continua ativo.
- [ ] Falha forçada no cancelamento desfaz a inativação.
- [ ] Reativar o paciente não restaura agendamentos.
- [ ] Anonimizar com futuro em aberto → 409; sem eles, `notes` ficam nulos e horário/status permanecem.
- [ ] Nota com `appointmentId` do mesmo paciente passa; de outro paciente 409; inexistente 404; segunda nota para o mesmo atendimento 409 (inclusive concorrente: uma passa, uma 409).
- [ ] Reeditar a nota vinculada reenviando o mesmo `appointmentId` passa.
- [ ] `PUT` com `appointmentId: null` ou sem o campo desvincula (resposta com `appointmentId: null`); depois outra nota pode ser vinculada ao mesmo atendimento.
- [ ] `PUT` trocando para outro atendimento do mesmo paciente passa; para atendimento já com nota 409; de outro paciente 409; inexistente 404.
- [ ] Notas antigas (sem vínculo) seguem válidas.
- [ ] Histórico: R e D leem; paginado; paciente de outra clínica 404; sem conteúdo clínico.
- [ ] A suíte da Fase 1 continua verde após ajustar os testes que inativam pacientes.
