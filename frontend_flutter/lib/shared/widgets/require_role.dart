import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/auth/providers/auth_provider.dart';

class RequireRole extends ConsumerWidget {
  final List<String> allowedRoles;
  final Widget child;
  final Widget fallback;

  const RequireRole({
    super.key,
    required this.allowedRoles,
    required this.child,
    this.fallback = const SizedBox.shrink(),
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final authState = ref.watch(authProvider);
    
    if (allowedRoles.contains(authState.role)) {
      return child;
    }
    
    return fallback;
  }
}
