import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/core/theme/theme_provider.dart';

class ThemeToggleButton extends ConsumerWidget {
  const ThemeToggleButton({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final themeMode = ref.watch(themeProvider);

    return IconButton(
      icon: Icon(
        themeMode == ThemeMode.dark 
            ? Icons.dark_mode_outlined 
            : Icons.wb_sunny_outlined,
      ),
      tooltip: 'Alternar Tema',
      onPressed: () {
        ThemeMode nextMode = themeMode == ThemeMode.light 
            ? ThemeMode.dark 
            : ThemeMode.light;
        ref.read(themeProvider.notifier).setTheme(nextMode);
      },
    );
  }
}
