import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/patients/data/medical_document_model.dart';
import 'package:frontend_flutter/features/patients/data/medical_documents_repository.dart';

final patientDocumentsProvider = FutureProvider.family<List<MedicalDocument>, int>((ref, patientId) async {
  final repository = ref.watch(medicalDocumentsRepositoryProvider);
  return repository.getPatientDocuments(patientId);
});

final documentDetailProvider = FutureProvider.family<MedicalDocument, int>((ref, documentId) async {
  final repository = ref.watch(medicalDocumentsRepositoryProvider);
  return repository.getDocumentById(documentId);
});
