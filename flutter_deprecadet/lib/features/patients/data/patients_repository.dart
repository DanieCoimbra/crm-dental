import 'dart:convert';
import 'package:dio/dio.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:frontend_flutter/core/network/api_client.dart';
import 'package:frontend_flutter/core/local_storage/hive_service.dart';
import 'package:frontend_flutter/features/patients/data/patient_model.dart';
import 'package:frontend_flutter/features/patients/data/clinical_evolution_model.dart';
import 'package:frontend_flutter/features/patients/data/patient_file_model.dart';
import 'package:frontend_flutter/features/patients/data/odontogram_model.dart';

final patientsRepositoryProvider = Provider<PatientsRepository>((ref) {
  return PatientsRepository(ref.read(dioProvider));
});

class PatientsRepository {
  final Dio _dio;

  PatientsRepository(this._dio);

  Future<List<Patient>> getPatients({
    String? search,
    int page = 1,
    String? healthInsurance,
  }) async {
    try {
      final response = await _dio.get('/patients', queryParameters: {
        if (search != null && search.isNotEmpty) 'search': search,
        'page': page,
        if (healthInsurance != null && healthInsurance.isNotEmpty && healthInsurance != 'TODOS')
          'health_insurance': healthInsurance,
      });

      final dynamic resData = response.data;
      List rawList = [];
      if (resData is List) {
        rawList = resData;
      } else if (resData is Map && resData['data'] is List) {
        rawList = resData['data'] as List;
      }

      if (page == 1 && (search == null || search.isEmpty)) {
        await HiveService.patientsBox.put('all_patients', jsonEncode(rawList));
      }

      return rawList.map((json) => Patient.fromJson(json as Map<String, dynamic>)).toList();
    } on DioException catch (e) {
      if (e.type == DioExceptionType.connectionError || e.type == DioExceptionType.unknown) {
        final cachedData = HiveService.patientsBox.get('all_patients');
        if (cachedData != null) {
          final data = jsonDecode(cachedData) as List;
          return data.map((json) => Patient.fromJson(json as Map<String, dynamic>)).toList();
        }
      }
      rethrow;
    }
  }

  Future<Patient> getPatientById(int id) async {
    final response = await _dio.get('/patients/$id');
    return Patient.fromJson(response.data as Map<String, dynamic>);
  }

  Future<Patient> createPatient(Map<String, dynamic> data) async {
    final response = await _dio.post('/patients', data: data);
    return Patient.fromJson(response.data as Map<String, dynamic>);
  }

  Future<Patient> updatePatient(int id, Map<String, dynamic> data) async {
    final response = await _dio.put('/patients/$id', data: data);
    return Patient.fromJson(response.data as Map<String, dynamic>);
  }

  Future<void> deletePatient(int id) async {
    await _dio.delete('/patients/$id');
  }

  Future<void> updateMedicalHistory(int id, String history, String notes) async {
    await _dio.put('/patients/$id/emr', data: {
      'medical_history': history,
      'notes': notes,
    });
  }

  // --- EVOLUÇÃO CLÍNICA ---

  Future<List<ClinicalEvolution>> getEvolutions(int patientId) async {
    final response = await _dio.get('/patients/$patientId/evolutions');
    final dynamic resData = response.data;
    List rawList = [];
    if (resData is List) {
      rawList = resData;
    } else if (resData is Map && resData['data'] is List) {
      rawList = resData['data'] as List;
    }
    return rawList.map((json) => ClinicalEvolution.fromJson(json as Map<String, dynamic>)).toList();
  }

  Future<ClinicalEvolution> createEvolution(
    int patientId, {
    required String procedureSummary,
    String? chiefComplaint,
    String? diagnosis,
    String? contentHtml,
    DateTime? attendanceDate,
  }) async {
    final response = await _dio.post('/patients/$patientId/evolutions', data: {
      'procedure_summary': procedureSummary,
      if (chiefComplaint != null && chiefComplaint.isNotEmpty)
        'chief_complaint': chiefComplaint,
      if (diagnosis != null && diagnosis.isNotEmpty) 'diagnosis': diagnosis,
      'content_html': contentHtml ?? procedureSummary,
      if (attendanceDate != null)
        'attendance_date': attendanceDate.toIso8601String(),
    });
    return ClinicalEvolution.fromJson(response.data as Map<String, dynamic>);
  }

  Future<void> deleteEvolution(int id) async {
    await _dio.delete('/evolutions/$id');
  }

  // --- ODONTOGRAMA ---

  Future<List<ToothStatus>> getTeethStatus(int patientId) async {
    try {
      final response = await _dio.get('/patients/$patientId/teeth');
      final dynamic data = response.data;
      List list = [];
      if (data is List) {
        list = data;
      } else if (data is Map && data['data'] is List) {
        list = data['data'];
      }
      return list.map((j) => ToothStatus.fromJson(j as Map<String, dynamic>)).toList();
    } catch (_) {
      return [];
    }
  }

  Future<ToothStatus> updateToothStatus(
    int patientId, {
    required int toothNumber,
    required String face,
    required String condition,
    String? notes,
  }) async {
    try {
      final Map<String, dynamic> body = {
        'tooth_number': toothNumber,
        'face': face,
        'condition': condition,
      };
      if (notes != null && notes.isNotEmpty) {
        body['notes'] = notes;
      }
      final response = await _dio.post('/patients/$patientId/teeth', data: body);
      return ToothStatus.fromJson(response.data as Map<String, dynamic>);
    } catch (_) {
      return ToothStatus(
        patientId: patientId,
        toothNumber: toothNumber,
        face: ToothFace.fromString(face),
        condition: ToothCondition.fromString(condition),
        notes: notes,
        updatedAt: DateTime.now(),
      );
    }
  }

  Future<List<ToothHistory>> getTeethHistory(int patientId, {int? toothNumber}) async {
    try {
      final url = toothNumber != null
          ? '/patients/$patientId/teeth/$toothNumber'
          : '/patients/$patientId/teeth/history';
      final response = await _dio.get(url);
      final dynamic data = response.data;
      List list = [];
      if (data is List) {
        list = data;
      } else if (data is Map && data['data'] is List) {
        list = data['data'];
      }
      return list.map((j) => ToothHistory.fromJson(j as Map<String, dynamic>)).toList();
    } catch (_) {
      return [];
    }
  }

  // --- EXAMES & ARQUIVOS ---

  Future<List<PatientFile>> getPatientFiles(int patientId) async {
    final response = await _dio.get('/patients/$patientId/files');
    final dynamic resData = response.data;
    List rawList = [];
    if (resData is List) {
      rawList = resData;
    } else if (resData is Map && resData['data'] is List) {
      rawList = resData['data'] as List;
    }
    return rawList.map((json) => PatientFile.fromJson(json as Map<String, dynamic>)).toList();
  }

  Future<PatientFile> uploadPatientFile(int patientId, PlatformFile file, String category) async {
    if (file.bytes == null) {
      throw Exception('Os dados do arquivo estão vazios. Certifique-se de carregar os bytes.');
    }

    int retries = 3;
    int delay = 1;

    while (retries > 0) {
      try {
        final formData = FormData.fromMap({
          'category': category,
          'file': MultipartFile.fromBytes(file.bytes!, filename: file.name),
        });

        final response = await _dio.post(
          '/patients/$patientId/files',
          data: formData,
        );
        return PatientFile.fromJson(response.data as Map<String, dynamic>);
      } catch (e) {
        retries--;
        if (retries == 0) rethrow;
        await Future.delayed(Duration(seconds: delay));
        delay *= 2;
      }
    }
    throw Exception('Falha ao fazer upload após 3 tentativas.');
  }

  Future<void> deletePatientFile(int fileId) async {
    await _dio.delete('/files/$fileId');
  }

  Future<void> openPatientFile(String urlString) async {
    final url = Uri.parse(urlString);
    if (!await launchUrl(url)) {
      throw Exception('Não foi possível abrir o arquivo');
    }
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
}
