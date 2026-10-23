# Spec — F05 Vínculo da nota clínica com o atendimento (Fase 2)

Complementa `docs/global/banco/Fase-2/spec.md` (seções 2.3, 2.4 e 2.5).

## 1. Visão técnica
- **Feature:** `core.clinical_notes.appointment_id`.
- **Stack:** PostgreSQL 16, migration `V019`.
- **Abordagem:** alteração aditiva e nula; FK composta de três colunas para garantir paciente e clínica.

## 2. Objetos (`V019__core_notes_appointment_link.sql`, como `crm_owner`, transação única)

```sql
ALTER TABLE core.clinical_notes ADD COLUMN appointment_id uuid;

ALTER TABLE core.clinical_notes
  ADD CONSTRAINT fk_notes_appointment
  FOREIGN KEY (appointment_id, patient_id, clinic_id)
  REFERENCES core.appointments (id, patient_id, clinic_id) ON DELETE RESTRICT;

CREATE INDEX ix_notes_appointment ON core.clinical_notes (clinic_id, appointment_id)
  WHERE appointment_id IS NOT NULL;
```
- `patient_id` e `clinic_id` já são `NOT NULL` e vêm da própria nota; a FK usa `MATCH SIMPLE`: se `appointment_id` for nulo, nenhuma checagem.
- A lista de colunas imutáveis do trigger `trg_notes_forbid_immutable` (`id`, `clinic_id`, `patient_id`, `created_by`, `created_at`) **não muda**.
- Políticas e grants de `clinical_notes` não mudam (a coluna nova herda o grant de tabela de `crm_app`).
- A coluna não é cifrada (é um uuid, sem dado clínico).

## 3. Componentes
`V019`, `U019`, ampliação de `tests/clinical_notes*.sql`, entidade `ClinicalNote` do backend (campo `appointmentId`).

## 4. Lógica
1. O dentista cria a nota com ou sem `appointment_id`.
2. A FK confere que o agendamento existe, é da mesma clínica e do mesmo paciente.
3. O vínculo pode ser definido ou trocado depois por `UPDATE` (dentista), com a mesma conferência.
4. Listar as notas de um atendimento: `WHERE clinic_id = ? AND appointment_id = ?` por `ix_notes_appointment`.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| `appointment_id` nulo | Passa |
| Agendamento de outro paciente ou de outra clínica | `23503` |
| `appointment_id` inexistente | `23503` |
| Várias notas no mesmo agendamento | Passam |
| Trocar o vínculo por `UPDATE` | Passa para o dentista; recepção não acessa a tabela |
| Notas existentes antes da migration | Intactas, `appointment_id` nulo |
| `DELETE` de agendamento vinculado | Impossível (`crm_app` sem `DELETE`); `RESTRICT` por garantia |
| Anonimização do paciente | As notas são apagadas (F07/Fase 1), então os vínculos somem junto |

## 6. Segurança e desempenho
- Sem exposição nova: a nota continua cifrada e restrita ao dentista; `appointment_id` não é dado clínico.
- A FK não permite cruzar clínicas nem pacientes.
- `ix_notes_appointment` é parcial: não pesa em notas sem vínculo.
