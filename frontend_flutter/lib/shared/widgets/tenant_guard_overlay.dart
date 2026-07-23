import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/auth/providers/auth_provider.dart';
import 'package:go_router/go_router.dart';

class TenantGuardOverlay extends ConsumerWidget {
  final Widget child;

  const TenantGuardOverlay({super.key, required this.child});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final clinicAsync = ref.watch(currentClinicProvider);

    return clinicAsync.when(
      data: (clinic) {
        final now = DateTime.now();
        bool isBlocked = false;
        String blockMessage = '';

        if (clinic.status == 'trialing' || clinic.status == 'trial') {
          if (clinic.trialEndsAt != null && clinic.trialEndsAt!.isBefore(now)) {
            isBlocked = true;
            blockMessage = 'Seu período de teste acabou. Por favor, assine um plano.';
          }
        } else if (clinic.status == 'past_due') {
          isBlocked = true;
          blockMessage = 'Identificamos um problema com seu pagamento. Regularize sua situação.';
        } else if (clinic.status == 'canceled') {
          isBlocked = true;
          blockMessage = 'Sua assinatura foi cancelada. Assine novamente para usar o sistema.';
        }

        if (isBlocked) {
          return Scaffold(
            body: Center(
              child: Container(
                padding: const EdgeInsets.all(32),
                constraints: const BoxConstraints(maxWidth: 400),
                decoration: BoxDecoration(
                  color: Colors.white,
                  borderRadius: BorderRadius.circular(16),
                  boxShadow: [
                    BoxShadow(
                      color: Colors.black.withOpacity(0.1),
                      blurRadius: 10,
                      offset: const Offset(0, 4),
                    ),
                  ],
                ),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.lock, size: 64, color: Colors.red),
                    const SizedBox(height: 16),
                    Text(
                      'Acesso Bloqueado',
                      style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                            fontWeight: FontWeight.bold,
                          ),
                    ),
                    const SizedBox(height: 16),
                    Text(
                      blockMessage,
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.bodyMedium,
                    ),
                    const SizedBox(height: 24),
                    ElevatedButton(
                      onPressed: () {
                        // Vá para o checkout ou plano
                        context.go('/saas-checkout');
                      },
                      style: ElevatedButton.styleFrom(
                        minimumSize: const Size(double.infinity, 50),
                      ),
                      child: const Text('Regularizar Assinatura'),
                    ),
                    const SizedBox(height: 8),
                    TextButton(
                      onPressed: () {
                        ref.read(authProvider.notifier).logout();
                        context.go('/login');
                      },
                      child: const Text('Sair'),
                    ),
                  ],
                ),
              ),
            ),
          );
        }

        return child;
      },
      loading: () => const Scaffold(
        body: Center(child: CircularProgressIndicator()),
      ),
      error: (err, stack) => Scaffold(
        body: Center(
          child: Text('Erro ao carregar dados da clínica: $err'),
        ),
      ),
    );
  }
}
