# Contrato de Repositório Client-Side: Agenda e Modais

- **Domínio:** `F04-front-schedule-dialogs`
- **Pacote:** `package:frontend_flutter/features/schedule`

---

## 1. Interface do Repositório (`ScheduleRepository`)

```dart
abstract class IScheduleRepository {
  Future<List<Map<String, dynamic>>> getAppointments();
  Future<void> createAppointment(Map<String, dynamic> data);
  Future<void> createWaitlist(Map<String, dynamic> data);
  Future<void> createShiftAssignment(Map<String, dynamic> data);
}
```

---

## 2. Contrato de Componentes e Modais

### `AppointmentFormDialog`
- **Inputs:** `DateTime? initialDate`, `Map<String, dynamic>? appointment`.

### `WaitlistFormDialog`
- **Inputs:** N/A.

### `ShiftAssignmentFormDialog`
- **Inputs:** N/A.
