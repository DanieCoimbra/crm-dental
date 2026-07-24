import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/core/network/api_client.dart';
import 'package:frontend_flutter/features/settings/data/clinic_model.dart';
import 'package:frontend_flutter/features/settings/data/room_model.dart';
import 'package:frontend_flutter/features/settings/data/user_model.dart';
import 'package:frontend_flutter/features/settings/data/role_model.dart';
import 'package:frontend_flutter/features/settings/data/setting_model.dart';

final settingsRepositoryProvider = Provider<SettingsRepository>((ref) {
  return SettingsRepository(ref.read(dioProvider));
});

class SettingsRepository {
  final Dio _dio;

  SettingsRepository(this._dio);

  Future<Clinic> getMyClinic() async {
    final response = await _dio.get('/clinics/me');
    return Clinic.fromJson(response.data);
  }

  Future<Clinic> updateClinic(int clinicId, Map<String, dynamic> data) async {
    final response = await _dio.put('/clinics/$clinicId', data: data);
    return Clinic.fromJson(response.data);
  }

  Future<List<User>> getUsers() async {
    final response = await _dio.get('/users');
    final data = response.data as List;
    return data.map((j) => User.fromJson(j)).toList();
  }

  Future<List<Role>> getRoles() async {
    final response = await _dio.get('/roles');
    final data = response.data as List;
    return data.map((j) => Role.fromJson(j)).toList();
  }

  Future<Role> createRole(Map<String, dynamic> data) async {
    final response = await _dio.post('/roles', data: data);
    return Role.fromJson(response.data);
  }

  Future<Role> updateRole(int id, Map<String, dynamic> data) async {
    final response = await _dio.put('/roles/$id', data: data);
    return Role.fromJson(response.data);
  }

  Future<void> deleteRole(int id) async {
    await _dio.delete('/roles/$id');
  }

  Future<List<String>> getPermissions() async {
    final response = await _dio.get('/permissions');
    return (response.data as List).map((e) => e.toString()).toList();
  }

  Future<User> createTeamMember(Map<String, dynamic> data) async {
    final response = await _dio.post('/team', data: data);
    return User.fromJson(response.data);
  }

  Future<User> updateTeamMember(int id, Map<String, dynamic> data) async {
    final response = await _dio.put('/team/$id', data: data);
    return User.fromJson(response.data);
  }

  // --- Rooms ---
  Future<List<Room>> getRooms() async {
    final response = await _dio.get('/rooms');
    final data = response.data as List;
    return data.map((json) => Room.fromJson(json)).toList();
  }

  Future<Room> createRoom(Map<String, dynamic> data) async {
    final response = await _dio.post('/rooms', data: data);
    return Room.fromJson(response.data);
  }

  Future<Room> updateRoom(int id, Map<String, dynamic> data) async {
    final response = await _dio.put('/rooms/$id', data: data);
    return Room.fromJson(response.data);
  }

  Future<void> deleteRoom(int id) async {
    await _dio.delete('/rooms/$id');
  }

  // --- Settings ---
  Future<List<AppSetting>> getSettings() async {
    final response = await _dio.get('/settings');
    final data = response.data as List;
    return data.map((json) => AppSetting.fromJson(json)).toList();
  }

  Future<void> saveSettings(Map<String, String> settings) async {
    await _dio.post('/settings', data: {'settings': settings});
  }

  Future<void> exportData() async {
    // In a real app, you might want to use url_launcher to open the download link,
    // or use Dio to download the file bytes and save it.
    // For now we just call the API to verify it works (mock)
    await _dio.get('/export');
  }

  Future<void> importData(List<int> fileBytes, String filename) async {
    final formData = FormData.fromMap({
      'file': MultipartFile.fromBytes(fileBytes, filename: filename),
    });
    await _dio.post('/import', data: formData);
  }

  Future<List<dynamic>> getHolidays() async {
    final response = await _dio.get('/settings/holidays');
    return response.data as List;
  }

  Future<void> deleteTeamMember(int id, String password) async {
    await _dio.delete(
      '/team/$id',
      data: {'password': password},
    );
  }

  Future<List<Map<String, dynamic>>> getAuditLogs() async {
    final response = await _dio.get('/audit-logs');
    return List<Map<String, dynamic>>.from(response.data);
  }

  // --- Appointment Types ---
  Future<Map<String, dynamic>> createAppointmentType(Map<String, dynamic> data) async {
    final response = await _dio.post('/appointment-types', data: data);
    return response.data;
  }

  Future<Map<String, dynamic>> updateAppointmentType(int id, Map<String, dynamic> data) async {
    final response = await _dio.put('/appointment-types/$id', data: data);
    return response.data;
  }

  Future<void> deleteAppointmentType(int id) async {
    await _dio.delete('/appointment-types/$id');
  }

  // --- SaaS / Coupons ---
  Future<Map<String, dynamic>> validateCoupon(String code) async {
    final response = await _dio.post('/saas/validate-coupon', data: {'code': code});
    return response.data;
  }

  Future<void> applyCoupon(String code) async {
    await _dio.post('/saas/apply-coupon', data: {'coupon_code': code, 'code': code});
  }

  Future<void> changePlan(String plan, {String billingCycle = 'monthly'}) async {
    await _dio.post('/saas/change-plan', data: {
      'new_plan': plan,
      'plan': plan,
      'billing_cycle': billingCycle,
    });
  }
}
