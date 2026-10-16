import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/core/network/api_client.dart';
import 'package:frontend_flutter/features/patients/data/medical_document_model.dart';

final medicalDocumentsRepositoryProvider = Provider<MedicalDocumentsRepository>((ref) {
  return MedicalDocumentsRepository(ref.read(dioProvider));
});

class MedicalDocumentsRepository {
  final Dio _dio;

  MedicalDocumentsRepository(this._dio);

  Future<List<MedicalDocument>> getPatientDocuments(int patientId) async {
    final response = await _dio.get('/patients/$patientId/documents');
    final dynamic resData = response.data;
    List rawList = [];
    if (resData is List) {
      rawList = resData;
    } else if (resData is Map && resData['data'] is List) {
      rawList = resData['data'] as List;
    }
    return rawList
        .map((json) => MedicalDocument.fromJson(json as Map<String, dynamic>))
        .toList();
  }

  Future<MedicalDocument> getDocumentById(int id) async {
    final response = await _dio.get('/documents/$id');
    final dynamic resData = response.data;
    if (resData is Map<String, dynamic>) {
      if (resData.containsKey('data') && resData['data'] is Map<String, dynamic>) {
        return MedicalDocument.fromJson(resData['data'] as Map<String, dynamic>);
      }
      return MedicalDocument.fromJson(resData);
    }
    throw Exception('Formato de resposta de documento inválido');
  }

  Future<MedicalDocument> createDocument(
    int patientId, {
    required String type,
    required String title,
    required String content,
  }) async {
    final response = await _dio.post(
      '/patients/$patientId/documents',
      data: {
        'type': type,
        'title': title,
        'content': content,
      },
    );

    final dynamic resData = response.data;
    if (resData is Map<String, dynamic>) {
      if (resData.containsKey('data') && resData['data'] is Map<String, dynamic>) {
        return MedicalDocument.fromJson(resData['data'] as Map<String, dynamic>);
      }
      return MedicalDocument.fromJson(resData);
    }
    throw Exception('Falha ao criar documento');
  }

  Future<void> deleteDocument(int id) async {
    await _dio.delete('/documents/$id');
  }
}
