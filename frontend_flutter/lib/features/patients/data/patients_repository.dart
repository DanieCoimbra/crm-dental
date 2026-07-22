import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:file_picker/file_picker.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:frontend_flutter/core/network/api_client.dart';
import 'package:frontend_flutter/features/patients/data/patient_model.dart';
import 'package:frontend_flutter/features/patients/data/clinical_evolution_model.dart';
import 'package:frontend_flutter/features/patients/data/patient_file_model.dart';
import 'package:frontend_flutter/core/local_storage/hive_service.dart';
import 'dart:convert';

final patientsRepositoryProvider = Provider<PatientsRepository>((ref) {
  return PatientsRepository(ref.read(dioProvider));
});

class PatientsRepository {
  final Dio _dio;

  PatientsRepository(this._dio);

  Future<List<Patient>> getPatients({String? search, int page = 1}) async {
    try {
      final response = await _dio.get('/patients', queryParameters: {
        if (search != null && search.isNotEmpty) 'search': search,
        'page': page,
      });
      
      final data = response.data as List;
      
      // Salva no cache local (somente primeira página para simplificar no MVP)
      if (page == 1 && (search == null || search.isEmpty)) {
        await HiveService.patientsBox.put('all_patients', jsonEncode(data));
      }

      return data.map((json) => Patient.fromJson(json)).toList();
    } on DioException catch (e) {
      if (e.type == DioExceptionType.connectionError || e.type == DioExceptionType.unknown) {
        // Tentar ler do cache offline
        final cachedData = HiveService.patientsBox.get('all_patients');
        if (cachedData != null) {
          final data = jsonDecode(cachedData) as List;
          return data.map((json) => Patient.fromJson(json)).toList();
        }
      }
      rethrow;
    }
  }

  Future<Patient> getPatientById(int id) async {
    final response = await _dio.get('/patients/$id');
    return Patient.fromJson(response.data);
  }

  Future<void> updateMedicalHistory(int id, String history, String notes) async {
    await _dio.put('/patients/$id/emr', data: {
      'medical_history': history,
      'notes': notes,
    });
  }

  Future<void> exportPatientsCsv() async {
    const storage = FlutterSecureStorage();
    final token = await storage.read(key: 'jwt_token');
    final baseUrl = _dio.options.baseUrl;
    final url = Uri.parse('$baseUrl/patients-export?token=$token');
    if (!await launchUrl(url)) {
      throw Exception('Could not launch browser for download');
    }
  }

  Future<void> downloadImportTemplate() async {
    final baseUrl = _dio.options.baseUrl;
    final url = Uri.parse('$baseUrl/patients-import-template');
    if (!await launchUrl(url)) {
      throw Exception('Could not launch browser for download');
    }
  }

  Future<void> importPatientsCsv(PlatformFile file) async {
    if (file.bytes == null) {
      throw Exception('Os dados do arquivo estão vazios. Certifique-se de usar withData: true no FilePicker.');
    }
    
    FormData formData = FormData.fromMap({
      "file": MultipartFile.fromBytes(file.bytes!, filename: file.name),
    });

    await _dio.post('/patients-import', data: formData);
  }


  Future<Patient> createPatient(Map<String, dynamic> data) async {
    final response = await _dio.post('/patients', data: data);
    return Patient.fromJson(response.data);
  }

  Future<Patient> updatePatient(int id, Map<String, dynamic> data) async {
    final response = await _dio.put('/patients/$id', data: data);
    return Patient.fromJson(response.data);
  }

  Future<void> deletePatient(int id) async {
    await _dio.delete('/patients/$id');
  }

  Future<List<ClinicalEvolution>> getEvolutions(int patientId) async {
    final response = await _dio.get('/patients/$patientId/evolutions');
    final data = response.data as List;
    return data.map((json) => ClinicalEvolution.fromJson(json)).toList();
  }

  Future<ClinicalEvolution> createEvolution(int patientId, String content) async {
    final response = await _dio.post('/patients/$patientId/evolutions', data: {
      'content': content,
    });
    return ClinicalEvolution.fromJson(response.data);
  }


  Future<List<PatientFile>> getPatientFiles(int patientId) async {
    final response = await _dio.get('/patients/$patientId/files');
    final data = response.data as List;
    return data.map((json) => PatientFile.fromJson(json)).toList();
  }

  Future<PatientFile> uploadPatientFile(int patientId, PlatformFile file, String category) async {
    if (file.bytes == null) {
      throw Exception('Os dados do arquivo estão vazios. Certifique-se de usar withData: true no FilePicker.');
    }
    final formData = FormData.fromMap({
      'category': category,
      'file': MultipartFile.fromBytes(file.bytes!, filename: file.name),
    });
    
    final response = await _dio.post(
      '/patients/$patientId/files',
      data: formData,
    );
    return PatientFile.fromJson(response.data);
  }

  Future<void> deletePatientFile(int fileId) async {
    await _dio.delete('/files/$fileId');
  }

  Future<void> openPatientFile(String filePath) async {
    // A API salva como "./uploads/arquivo.png". Limpar isso.
    String cleanPath = filePath.replaceFirst('./', '');
    if (cleanPath.startsWith('/')) {
      cleanPath = cleanPath.substring(1);
    }
    
    // baseUrl: http://localhost:8080/api/v1
    final baseUrl = _dio.options.baseUrl.replaceAll('/api/v1', '');
    final url = Uri.parse('$baseUrl/$cleanPath');
    
    if (!await launchUrl(url)) {
      throw Exception('Não foi possível abrir o arquivo');
    }
  }

}
