import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/core/router/app_router.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_quill/flutter_quill.dart' as quill;
import 'package:frontend_flutter/core/theme/app_theme.dart';
import 'package:frontend_flutter/core/theme/theme_provider.dart';
import 'package:frontend_flutter/core/local_storage/hive_service.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  
  // Inicializa o Hive Local First com Criptografia AES-256
  await HiveService.init();

  runApp(
    const ProviderScope(
      child: DentalCrmApp(),
    ),
  );
}

class DentalCrmApp extends ConsumerWidget {
  const DentalCrmApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final router = ref.watch(routerProvider);
    final currentThemeMode = ref.watch(themeProvider);

    return MaterialApp.router(
      title: 'Dental CRM',
      theme: AppTheme.lightTheme,
      darkTheme: AppTheme.darkTheme,
      themeMode: currentThemeMode,
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
        quill.FlutterQuillLocalizations.delegate,
      ],
      supportedLocales: const [
        Locale('pt', 'BR'),
        Locale('en', 'US'),
      ],
      routerConfig: router,
    );
  }
}
