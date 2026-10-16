import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/patients/data/patient_model.dart';
import 'package:frontend_flutter/features/patients/data/clinical_evolution_model.dart';
import 'package:frontend_flutter/features/patients/data/patient_file_model.dart';
import 'package:frontend_flutter/features/patients/data/odontogram_model.dart';
import 'package:frontend_flutter/features/patients/data/patients_repository.dart';

class PatientQueryParams {
  final String search;
  final int page;
  final String? healthInsurance;

  const PatientQueryParams({
    this.search = '',
    this.page = 1,
    this.healthInsurance,
  });

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is PatientQueryParams &&
          runtimeType == other.runtimeType &&
          search == other.search &&
          page == other.page &&
          healthInsurance == other.healthInsurance;

  @override
  int get hashCode => search.hashCode ^ page.hashCode ^ healthInsurance.hashCode;
}

final patientsListProvider = FutureProvider.family<List<Patient>, String>((ref, search) {
  final repository = ref.watch(patientsRepositoryProvider);
  return repository.getPatients(search: search);
});

final patientsPaginatedProvider = FutureProvider.family<List<Patient>, PatientQueryParams>((ref, params) {
  final repository = ref.watch(patientsRepositoryProvider);
  return repository.getPatients(
    search: params.search,
    page: params.page,
    healthInsurance: params.healthInsurance,
  );
});

final patientDetailProvider = FutureProvider.family<Patient, int>((ref, id) {
  final repository = ref.watch(patientsRepositoryProvider);
  return repository.getPatientById(id);
});

final evolutionsProvider = FutureProvider.family<List<ClinicalEvolution>, int>((ref, patientId) {
  final repository = ref.watch(patientsRepositoryProvider);
  return repository.getEvolutions(patientId);
});

final patientFilesProvider = FutureProvider.family<List<PatientFile>, int>((ref, patientId) {
  final repository = ref.watch(patientsRepositoryProvider);
  return repository.getPatientFiles(patientId);
});

final teethStatusProvider = FutureProvider.family<List<ToothStatus>, int>((ref, patientId) {
  final repository = ref.watch(patientsRepositoryProvider);
  return repository.getTeethStatus(patientId);
});

final teethHistoryProvider = FutureProvider.family<List<ToothHistory>, int>((ref, patientId) {
  final repository = ref.watch(patientsRepositoryProvider);
  return repository.getTeethHistory(patientId);
});
