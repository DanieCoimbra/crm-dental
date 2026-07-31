# Contrato de Repositório Client-Side: Pacientes e EMR

- **Domínio:** `F02-front-patients-emr`
- **Pacote:** `package:frontend_flutter/features/patients`

---

## 1. Interface do Repositório (`PatientRepository`)

```dart
abstract class IPatientRepository {
  Future<List<Map<String, dynamic>>> getPatients();
  Future<Map<String, dynamic>> createPatient(Map<String, dynamic> data);
  Future<List<Map<String, dynamic>>> getEvolutions(String patientId);
  Future<List<Map<String, dynamic>>> getFiles(String patientId);
}
```

---

## 2. Contrato de Componentes e Modais

### `PatientFormDialog`
- **Inputs:** `Map<String, dynamic>? patient` (Opcional para edição).
- **Callbacks:** `VoidCallback? onSuccess`.

### `PatientSummaryTab`
- **Inputs:** `required String patientId`, `required Map<String, dynamic> patientData`.

### `PatientFilesTab`
- **Inputs:** `required String patientId`.
