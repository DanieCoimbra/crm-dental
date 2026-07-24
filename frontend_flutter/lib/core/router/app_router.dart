import 'package:go_router/go_router.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/auth/presentation/login_screen.dart';
import 'package:frontend_flutter/features/auth/presentation/register_screen.dart';
import 'package:frontend_flutter/features/dashboard/presentation/dashboard_screen.dart';
import 'package:frontend_flutter/features/settings/presentation/trash_screen.dart';
import 'package:frontend_flutter/features/patients/presentation/patients_screen.dart';
import 'package:frontend_flutter/features/patients/presentation/patient_emr_screen.dart';
import 'package:frontend_flutter/features/schedule/presentation/schedule_screen.dart';
import 'package:frontend_flutter/features/settings/presentation/settings_screen.dart';
import 'package:frontend_flutter/shared/widgets/dashboard_layout.dart';
import 'package:frontend_flutter/features/auth/providers/auth_provider.dart';
import 'package:frontend_flutter/features/financial/presentation/financial_dashboard_screen.dart';
import 'package:frontend_flutter/features/inventory/presentation/inventory_screen.dart';
import 'package:frontend_flutter/features/marketing/presentation/marketing_screen.dart';
import 'package:frontend_flutter/features/saas/presentation/saas_checkout_screen.dart';
import 'package:frontend_flutter/features/saas/presentation/plans_screen.dart';

import 'package:frontend_flutter/shared/widgets/tenant_guard_overlay.dart';

final routerProvider = Provider<GoRouter>((ref) {
  final authState = ref.watch(authProvider);

  return GoRouter(
    initialLocation: '/dashboard',
    redirect: (context, state) {
      if (authState.isLoading) return null; // Wait for initial check

      final isAuthRoute = state.uri.toString() == '/login' ||
          state.uri.toString() == '/register' ||
          state.uri.toString().startsWith('/register-clinic') ||
          state.uri.toString().startsWith('/plans');

      if (!authState.isAuthenticated && !isAuthRoute) {
        return '/login'; // Block unauthenticated access
      }
      if (authState.isAuthenticated && isAuthRoute) {
        return '/dashboard'; // Block auth screens for authenticated users
      }
      return null;
    },
    routes: [
      GoRoute(
        path: '/login',
        builder: (context, state) => const LoginScreen(),
      ),
      GoRoute(
        path: '/plans',
        builder: (context, state) => const PlansScreen(),
      ),
      GoRoute(
        path: '/register',
        builder: (context, state) => const RegisterScreen(),
      ),
      GoRoute(
        path: '/register-clinic',
        builder: (context, state) => const RegisterScreen(),
      ),
      GoRoute(
        path: '/saas-checkout',
        builder: (context, state) => const SaasCheckoutScreen(),
      ),
      ShellRoute(
        builder: (context, state, child) {
          return TenantGuardOverlay(
            child: DashboardLayout(child: child),
          );
        },
        routes: [
          GoRoute(
            path: '/dashboard',
            builder: (context, state) => const DashboardScreen(),
          ),
          GoRoute(
            path: '/patients',
            builder: (context, state) => const PatientsScreen(),
            routes: [
              GoRoute(
                path: ':id/emr',
                builder: (context, state) {
                  final id = int.parse(state.pathParameters['id']!);
                  return PatientEmrScreen(patientId: id);
                },
              ),
            ],
          ),
          GoRoute(
            path: '/schedule',
            name: 'schedule',
            builder: (context, state) => const ScheduleScreen(),
          ),
          GoRoute(
            path: '/settings',
            name: 'settings',
            builder: (context, state) => const SettingsScreen(),
          ),
          GoRoute(
            path: '/trash',
            builder: (context, state) => const TrashScreen(),
          ),
          GoRoute(
            path: '/financial',
            builder: (context, state) => const FinancialDashboardScreen(),
          ),
          GoRoute(
            path: '/inventory',
            builder: (context, state) => const InventoryScreen(),
          ),
          GoRoute(
            path: '/marketing',
            builder: (context, state) => const MarketingScreen(),
          ),
        ],
      ),
    ],
  );
});
