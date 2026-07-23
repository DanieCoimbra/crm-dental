# Feature Contract: F03-Smart Agenda

## 1. Contract Summary
- **Feature**: F03-Smart Agenda
- **Version**: 1.1.0
- **Primary Consumer(s)**: Frontend Flutter e módulo futuro `F05-Inventory Control` (via pub/sub interno em Go).

## 2. API Endpoints Principais
- **Criar/Editar Consulta**: `POST /api/appointments` e `PUT /api/appointments/:id`
- **Controle de Fluxo (Timer)**:
  - `PUT /api/appointments/:id/start` -> Salva o timestamp real de início e muda o status.
  - `PUT /api/appointments/:id/finish` -> Salva o timestamp real de fim e conclui o registro.
- **Smart Booking Heuristics**:
  - `POST /api/waitlists/matches` -> Recebe o ID do médico e o tempo base liberado, devolvendo a fila qualificada.

## 3. Outputs (Smart Booking Matches)
- **200 OK Response Schema**:
  ```json
  [
    {
      "id": 10,
      "patient_id": 5,
      "patient": {
         "name": "João", 
         "phone": "119999999"
      },
      "urgency_level": "alta",
      "preferred_time_range": "manha"
    }
  ]
  ```

## 4. Business Rules & Limits
- **Validation Blocks**: Tentativas de marcação fora do `business_start_hour` e `business_end_hour` definidas nas Configurações (`settings`) da Clínica retornarão HTTP 400. Finais de semana também podem ser barrados se `allow_weekends=false`.

## 5. Internal Events Emitted (Side Effects)
- **Event Name**: `Appointment.Completed`
- **Trigger**: Execução bem-sucedida da rota de Finish (`/api/appointments/:id/finish`).
- **Event Payload (Memory Struct in Go)**: 
  ```go
  type AppointmentCompletedEvent struct {
      AppointmentID uint
      ClinicID      uint
      DoctorID      uint
      PatientID     uint
      FinishedAt    time.Time
  }
  ```
- **Consumer**: No desenvolvimento futuro da Sprint **F05**, um worker em goroutine assinará este evento para varrer os procedimentos associados a essa consulta concluída, calculando e abatendo automaticamente os materiais descartáveis utilizados diretamente do Módulo de Estoque, promovendo automação silenciosa e zero latência adicional no endpoint do agendamento.
