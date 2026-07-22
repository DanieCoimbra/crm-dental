import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/core/local_storage/hive_service.dart';
import 'package:frontend_flutter/core/network/api_client.dart'; // Using generic api_client path for now, maybe need to check path

enum AppThemeMode { light, dark, system }

final themeProvider = NotifierProvider<ThemeNotifier, ThemeMode>(ThemeNotifier.new);

class ThemeNotifier extends Notifier<ThemeMode> {
  static const String _key = 'theme_preference';

  @override
  ThemeMode build() {
    final box = HiveService.settingsBox;
    final savedTheme = box.get(_key, defaultValue: 'light');
    return _parseThemeMode(savedTheme);
  }

  Future<void> setTheme(ThemeMode mode) async {
    state = mode;
    final box = HiveService.settingsBox;
    await box.put(_key, mode.name);
    
    // Sync with backend asynchronously
    try {
      final dio = ref.read(dioProvider);
      await dio.put(
        '/users/preferences', 
        data: {'theme_preference': mode.name},
      );
    } catch (e) {
      // Ignore errors for preference sync to not block UI
      debugPrint('Failed to sync theme preference: $e');
    }
  }

  ThemeMode _parseThemeMode(String name) {
    switch (name) {
      case 'dark':
        return ThemeMode.dark;
      case 'light':
      default:
        return ThemeMode.light;
    }
  }
}
