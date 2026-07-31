import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/core/network/api_client.dart';
import 'package:frontend_flutter/features/settings/data/user_model.dart';

abstract class IAuthRepository {
  Future<Map<String, dynamic>> registerClinic({
    required String clinicName,
    required String clinicEmail,
    required String adminName,
    required String adminEmail,
    required String password,
  });

  Future<Map<String, dynamic>> login({
    required String email,
    required String password,
  });

  Future<Map<String, dynamic>> getProfile();

  Future<User> updateRoom(int? roomId);
  
  Future<User> updateProfile(Map<String, dynamic> data);
  
  Future<User> updateAvatar(List<int> bytes, String fileName);
}

final authRepositoryProvider = Provider<IAuthRepository>((ref) {
  return AuthRepository(ref.read(dioProvider));
});

class AuthRepository implements IAuthRepository {
  final Dio _dio;

  AuthRepository(this._dio);

  @override
  Future<Map<String, dynamic>> registerClinic({
    required String clinicName,
    required String clinicEmail,
    required String adminName,
    required String adminEmail,
    required String password,
  }) async {
    final response = await _dio.post('/auth/register', data: {
      'clinic_name': clinicName,
      'clinic_email': clinicEmail,
      'admin_name': adminName,
      'admin_email': adminEmail,
      'password': password,
    });
    return response.data;
  }

  @override
  Future<Map<String, dynamic>> login({
    required String email,
    required String password,
  }) async {
    final response = await _dio.post('/auth/login', data: {
      'email': email,
      'password': password,
    });
    return response.data;
  }

  @override
  Future<Map<String, dynamic>> getProfile() async {
    final response = await _dio.get('/profile');
    return response.data;
  }

  @override
  Future<User> updateRoom(int? roomId) async {
    final response = await _dio.post('/profile/room', data: {
      'room_id': roomId,
    });
    return User.fromJson(response.data);
  }

  @override
  Future<User> updateProfile(Map<String, dynamic> data) async {
    final response = await _dio.put('/profile', data: data);
    return User.fromJson(response.data);
  }

  @override
  Future<User> updateAvatar(List<int> bytes, String fileName) async {
    final formData = FormData.fromMap({
      'avatar': MultipartFile.fromBytes(bytes, filename: fileName),
    });
    
    final response = await _dio.post(
      '/profile/avatar',
      data: formData,
      options: Options(
        headers: {
          'Content-Type': 'multipart/form-data',
        },
      ),
    );
    return User.fromJson(response.data);
  }
}
