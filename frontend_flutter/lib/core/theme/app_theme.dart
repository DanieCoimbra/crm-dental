import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

class AppTheme {
  // Cores Principais - Clinical Precision Glass
  static const Color primary = Color(0xFF2563EB); // Royal Blue
  static const Color primaryDark = Color(0xFF3B82F6); // Blue 500 for dark contrast
  static const Color primaryLight = Color(0xFF60A5FA);

  static const Color secondary = Color(0xFF10B981); // Emerald Green
  static const Color accent = Color(0xFF8B5CF6); // Violet

  // Cores de Fundo - Light
  static const Color background = Color(0xFFF8FAFC); // Slate 50
  static const Color surface = Color(0xFFFFFFFF);
  static const Color surfaceVariant = Color(0xFFF1F5F9); // Slate 100

  // Textos - Light
  // Textos - Light
  static const Color textPrimary = Color(0xFF0F172A); // Slate 900
  static const Color textSecondary = Color(0xFF475569); // Slate 600

  // Bordas e Divisores - Light
  static const Color border = Color(0xFFE2E8F0); // Slate 200

  // Cores de Fundo - Dark (Deep Slate)
  static const Color backgroundDark = Color(0xFF0F172A); // Slate 900 Deep Slate
  static const Color surfaceDark = Color(0xFF1E293B); // Slate 800
  static const Color surfaceVariantDark = Color(0xFF334155); // Slate 700

  // Textos - Dark
  static const Color textPrimaryDark = Color(0xFFF8FAFC); // Slate 50
  static const Color textSecondaryDark = Color(0xFF94A3B8); // Slate 400

  // Bordas e Divisores - Dark
  static const Color borderDark = Color(0xFF334155); // Slate 700

  static ThemeData get lightTheme {
    final baseTextTheme = GoogleFonts.interTextTheme();

    return ThemeData(
      useMaterial3: true,
      colorScheme: const ColorScheme.light(
        primary: primary,
        secondary: secondary,
        surface: surface,
        surfaceContainerHighest: surfaceVariant,
        error: Color(0xFFEF4444),
        onPrimary: Colors.white,
        onSecondary: Colors.white,
        onSurface: textPrimary,
        outline: border,
      ),
      scaffoldBackgroundColor: background,
      textTheme: baseTextTheme.copyWith(
        displayLarge: baseTextTheme.displayLarge?.copyWith(color: textPrimary, fontWeight: FontWeight.bold),
        displayMedium: baseTextTheme.displayMedium?.copyWith(color: textPrimary, fontWeight: FontWeight.bold),
        displaySmall: baseTextTheme.displaySmall?.copyWith(color: textPrimary, fontWeight: FontWeight.w600),
        headlineLarge: baseTextTheme.headlineLarge?.copyWith(color: textPrimary, fontWeight: FontWeight.w600),
        headlineMedium: baseTextTheme.headlineMedium?.copyWith(color: textPrimary, fontWeight: FontWeight.w600),
        headlineSmall: baseTextTheme.headlineSmall?.copyWith(color: textPrimary, fontWeight: FontWeight.w600),
        titleLarge: baseTextTheme.titleLarge?.copyWith(color: textPrimary, fontWeight: FontWeight.w600),
        titleMedium: baseTextTheme.titleMedium?.copyWith(color: textPrimary, fontWeight: FontWeight.w600),
        titleSmall: baseTextTheme.titleSmall?.copyWith(color: textPrimary, fontWeight: FontWeight.w600),
        bodyLarge: baseTextTheme.bodyLarge?.copyWith(color: textPrimary),
        bodyMedium: baseTextTheme.bodyMedium?.copyWith(color: textSecondary),
        bodySmall: baseTextTheme.bodySmall?.copyWith(color: textSecondary),
        labelLarge: baseTextTheme.labelLarge?.copyWith(color: textPrimary, fontWeight: FontWeight.w600),
        labelMedium: baseTextTheme.labelMedium?.copyWith(color: textSecondary),
        labelSmall: baseTextTheme.labelSmall?.copyWith(color: textSecondary),
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: surface,
        elevation: 0,
        scrolledUnderElevation: 0,
        iconTheme: IconThemeData(color: textPrimary),
        titleTextStyle: TextStyle(
          color: textPrimary,
          fontSize: 20,
          fontWeight: FontWeight.w600,
        ),
      ),
      cardTheme: CardThemeData(
        color: surface,
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(16),
          side: const BorderSide(color: border, width: 1),
        ),
        margin: EdgeInsets.zero,
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          backgroundColor: primary,
          foregroundColor: Colors.white,
          elevation: 0,
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
          textStyle: const TextStyle(
            fontSize: 16,
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: textPrimary,
          side: const BorderSide(color: border, width: 1),
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
          textStyle: const TextStyle(
            fontSize: 16,
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: surface,
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: border),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: border),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: primary, width: 2),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: Color(0xFFEF4444)),
        ),
        hintStyle: const TextStyle(color: textSecondary),
        labelStyle: const TextStyle(color: textSecondary),
      ),
      dividerTheme: const DividerThemeData(
        color: border,
        space: 1,
        thickness: 1,
      ),
    );
  }

  static ThemeData get darkTheme {
    final baseTextTheme = GoogleFonts.interTextTheme();

    return ThemeData(
      useMaterial3: true,
      colorScheme: const ColorScheme.dark(
        primary: primaryDark,
        secondary: secondary,
        surface: surfaceDark,
        surfaceContainerHighest: surfaceVariantDark,
        error: Color(0xFFF87171),
        onPrimary: Colors.white,
        onSecondary: Colors.white,
        onSurface: textPrimaryDark,
        outline: borderDark,
      ),
      scaffoldBackgroundColor: backgroundDark,
      textTheme: baseTextTheme.copyWith(
        displayLarge: baseTextTheme.displayLarge?.copyWith(color: textPrimaryDark, fontWeight: FontWeight.bold),
        displayMedium: baseTextTheme.displayMedium?.copyWith(color: textPrimaryDark, fontWeight: FontWeight.bold),
        displaySmall: baseTextTheme.displaySmall?.copyWith(color: textPrimaryDark, fontWeight: FontWeight.w600),
        headlineLarge: baseTextTheme.headlineLarge?.copyWith(color: textPrimaryDark, fontWeight: FontWeight.w600),
        headlineMedium: baseTextTheme.headlineMedium?.copyWith(color: textPrimaryDark, fontWeight: FontWeight.w600),
        headlineSmall: baseTextTheme.headlineSmall?.copyWith(color: textPrimaryDark, fontWeight: FontWeight.w600),
        titleLarge: baseTextTheme.titleLarge?.copyWith(color: textPrimaryDark, fontWeight: FontWeight.w600),
        titleMedium: baseTextTheme.titleMedium?.copyWith(color: textPrimaryDark, fontWeight: FontWeight.w600),
        titleSmall: baseTextTheme.titleSmall?.copyWith(color: textPrimaryDark, fontWeight: FontWeight.w600),
        bodyLarge: baseTextTheme.bodyLarge?.copyWith(color: textPrimaryDark),
        bodyMedium: baseTextTheme.bodyMedium?.copyWith(color: textSecondaryDark),
        bodySmall: baseTextTheme.bodySmall?.copyWith(color: textSecondaryDark),
        labelLarge: baseTextTheme.labelLarge?.copyWith(color: textPrimaryDark, fontWeight: FontWeight.w600),
        labelMedium: baseTextTheme.labelMedium?.copyWith(color: textSecondaryDark),
        labelSmall: baseTextTheme.labelSmall?.copyWith(color: textSecondaryDark),
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: surfaceDark,
        elevation: 0,
        scrolledUnderElevation: 0,
        iconTheme: IconThemeData(color: textPrimaryDark),
        titleTextStyle: TextStyle(
          color: textPrimaryDark,
          fontSize: 20,
          fontWeight: FontWeight.w600,
        ),
      ),
      cardTheme: CardThemeData(
        color: surfaceDark,
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(16),
          side: const BorderSide(color: borderDark, width: 1),
        ),
        margin: EdgeInsets.zero,
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          backgroundColor: primary,
          foregroundColor: Colors.white,
          elevation: 0,
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
          textStyle: const TextStyle(
            fontSize: 16,
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: textPrimaryDark,
          side: const BorderSide(color: borderDark, width: 1),
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
          textStyle: const TextStyle(
            fontSize: 16,
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: surfaceDark,
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: borderDark),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: borderDark),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: primary, width: 2),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: Color(0xFFF87171)),
        ),
        hintStyle: const TextStyle(color: textSecondaryDark),
        labelStyle: const TextStyle(color: textSecondaryDark),
      ),
      dividerTheme: const DividerThemeData(
        color: borderDark,
        space: 1,
        thickness: 1,
      ),
    );
  }
}

