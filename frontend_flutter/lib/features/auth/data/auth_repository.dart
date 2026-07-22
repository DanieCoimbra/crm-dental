import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/core/network/api_client.dart';
import 'package:frontend_flutter/features/settings/data/user_model.dart';

final authRepositoryProvider = Provider<AuthRepository>((ref) {
  return AuthRepository(ref.read(dioProvider));
});

class AuthRepository {
  final Dio _dio;

  AuthRepository(this._dio);

  Future<User> getProfile() async {
    final response = await _dio.get('/user');
    return User.fromJson(response.data);
  }

  Future<User> updateRoom(int? roomId) async {
    final response = await _dio.post('/profile/room', data: {
      'room_id': roomId,
    });
    return User.fromJson(response.data);
  }

  Future<User> updateProfile(Map<String, dynamic> data) async {
    final response = await _dio.put('/profile', data: data);
    return User.fromJson(response.data);
  }

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
