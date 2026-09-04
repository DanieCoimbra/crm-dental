import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/core/network/api_client.dart';
import 'package:frontend_flutter/features/schedule/data/appointment_model.dart';
import 'package:frontend_flutter/features/schedule/data/appointment_type_model.dart';
import 'package:frontend_flutter/features/schedule/data/waitlist_model.dart';
import 'package:frontend_flutter/features/schedule/data/shift_assignment_model.dart';
import 'package:frontend_flutter/core/local_storage/hive_service.dart';
import 'dart:convert';

final scheduleRepositoryProvider = Provider<ScheduleRepository>((ref) {
  return ScheduleRepository(ref.read(dioProvider));
});

class ScheduleRepository {
  final Dio _dio;

  ScheduleRepository(this._dio);

  Future<List<Appointment>> getAppointments(String? start, String? end) async {
    final cacheKey = 'appointments_${start ?? 'all'}_${end ?? 'all'}';
    try {
      final response = await _dio.get('/appointments', queryParameters: {
        'start': ?start,
        'end': ?end,
      });
      final data = response.data as List;

      // Salva no cache local
      await HiveService.appointmentsBox.put(cacheKey, jsonEncode(data));

      return data.map((json) => Appointment.fromJson(json)).toList();
    } on DioException catch (e) {
      if (e.type == DioExceptionType.connectionError || e.type == DioExceptionType.unknown) {
        // Tentar ler do cache offline
        final cachedData = HiveService.appointmentsBox.get(cacheKey);
        if (cachedData != null) {
          final data = jsonDecode(cachedData) as List;
          return data.map((json) => Appointment.fromJson(json)).toList();
        }
      }
      rethrow;
    }
  }

  Future<Appointment> createAppointment(Map<String, dynamic> data) async {
    final response = await _dio.post('/appointments', data: data);
    return Appointment.fromJson(response.data);
  }

  Future<Appointment> updateAppointment(int id, Map<String, dynamic> data) async {
    final response = await _dio.put('/appointments/$id', data: data);
    return Appointment.fromJson(response.data);
  }

  Future<void> deleteAppointment(int id) async {
    await _dio.delete('/appointments/$id');
  }

  Future<Appointment> startAppointment(int id) async {
    final response = await _dio.post('/appointments/$id/start');
    return Appointment.fromJson(response.data);
  }

  Future<Appointment> finishAppointment(int id) async {
    final response = await _dio.post('/appointments/$id/finish');
    return Appointment.fromJson(response.data);
  }

  Future<Appointment> confirmAppointment(int id) async {
    final response = await _dio.post('/appointments/$id/confirm');
    return Appointment.fromJson(response.data);
  }

  Future<Appointment> missAppointment(int id) async {
    final response = await _dio.post('/appointments/$id/miss');
    return Appointment.fromJson(response.data);
  }

  Future<String> getWhatsAppLink(int id) async {
    final response = await _dio.post('/appointments/$id/whatsapp-link');
    if (response.data is Map) {
      final map = response.data as Map<String, dynamic>;
      return map['link']?.toString() ?? map['url']?.toString() ?? map['whatsapp_link']?.toString() ?? '';
    }
    return response.data.toString();
  }

  Future<List<AppointmentType>> getAppointmentTypes() async {
    final response = await _dio.get('/appointment-types');
    final data = response.data as List;
    return data.map((json) => AppointmentType.fromJson(json)).toList();
  }

  Future<List<Waitlist>> getWaitlists() async {
    final response = await _dio.get('/waitlists');
    final data = response.data as List;
    return data.map((json) => Waitlist.fromJson(json)).toList();
  }

  Future<Waitlist> createWaitlist(Map<String, dynamic> data) async {
    final response = await _dio.post('/waitlists', data: data);
    return Waitlist.fromJson(response.data);
  }

  Future<Waitlist> updateWaitlist(int id, Map<String, dynamic> data) async {
    final response = await _dio.put('/waitlists/$id', data: data);
    return Waitlist.fromJson(response.data);
  }

  Future<void> deleteWaitlist(int id) async {
    await _dio.delete('/waitlists/$id');
  }

  Future<List<Waitlist>> checkMatches(int doctorId, String startTime) async {
    final response = await _dio.post('/waitlists/check-matches', data: {
      'doctor_id': doctorId,
      'start_time': startTime,
    });
    final data = response.data as List;
    return data.map((json) => Waitlist.fromJson(json)).toList();
  }

  Future<List<ShiftAssignment>> getShiftAssignments() async {
    final response = await _dio.get('/shift-assignments');
    final data = response.data as List;
    return data.map((json) => ShiftAssignment.fromJson(json)).toList();
  }

  Future<ShiftAssignment> createShiftAssignment(Map<String, dynamic> data) async {
    final response = await _dio.post('/shift-assignments', data: data);
    return ShiftAssignment.fromJson(response.data);
  }

  Future<void> deleteShiftAssignment(int id) async {
    await _dio.delete('/shift-assignments/$id');
  }
}
