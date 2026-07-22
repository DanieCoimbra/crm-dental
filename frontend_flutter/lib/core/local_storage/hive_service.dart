import 'dart:convert';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:hive_flutter/hive_flutter.dart';

class HiveService {
  static const _secureStorage = FlutterSecureStorage();
  static const _encryptionKeyId = 'hive_encryption_key';

  static Future<void> init() async {
    await Hive.initFlutter();
    
    final encryptionKey = await _getOrCreateEncryptionKey();
    
    // Open encrypted boxes
    await Hive.openBox('patients', encryptionCipher: HiveAesCipher(encryptionKey));
    await Hive.openBox('appointments', encryptionCipher: HiveAesCipher(encryptionKey));
    await Hive.openBox('sync_queue', encryptionCipher: HiveAesCipher(encryptionKey)); // Etapa 3
    
    // Open unencrypted boxes for simple settings
    await Hive.openBox('settings');
  }

  static Future<List<int>> _getOrCreateEncryptionKey() async {
    final keyString = await _secureStorage.read(key: _encryptionKeyId);
    
    if (keyString != null) {
      return base64Url.decode(keyString);
    } else {
      // Generate a new key and save it securely
      final newKey = Hive.generateSecureKey();
      await _secureStorage.write(
        key: _encryptionKeyId,
        value: base64Url.encode(newKey),
      );
      return newKey;
    }
  }

  static Box get patientsBox => Hive.box('patients');
  static Box get appointmentsBox => Hive.box('appointments');
  static Box get syncQueueBox => Hive.box('sync_queue');
  static Box get settingsBox => Hive.box('settings');
}
