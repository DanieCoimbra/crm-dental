import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/patients/data/patient_model.dart';
import 'package:frontend_flutter/features/patients/data/clinical_evolution_model.dart';
import 'package:frontend_flutter/features/patients/data/patient_file_model.dart';
import 'package:frontend_flutter/features/patients/data/patients_repository.dart';

final patientsListProvider = FutureProvider.family<List<Patient>, String>((ref, search) {
  final repository = ref.watch(patientsRepositoryProvider);
  return repository.getPatients(search: search);
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

