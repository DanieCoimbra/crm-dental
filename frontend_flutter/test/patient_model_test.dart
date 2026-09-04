import 'package:flutter_test/flutter_test.dart';
import 'package:frontend_flutter/features/patients/data/patient_model.dart';

void main() {
  group('Patient Model Unit Tests', () {
    test('Patient.fromJson parses valid JSON correctly', () {
      final json = {
        'id': 10,
        'clinic_id': 2,
        'name': 'Carlos Eduardo Silva',
        'email': 'carlos@exemplo.com',
        'phone': '11999998888',
        'cpf': '123.456.789-00',
      };

      final patient = Patient.fromJson(json);

      expect(patient.id, equals(10));
      expect(patient.clinicId, equals(2));
      expect(patient.name, equals('Carlos Eduardo Silva'));
      expect(patient.email, equals('carlos@exemplo.com'));
      expect(patient.initials, equals('CS'));
    });

    test('Patient initials returns single letter for single name', () {
      final patient = Patient(
        id: 1,
        clinicId: 1,
        name: 'Ana',
      );

      expect(patient.initials, equals('AN'));
    });
  });
}
