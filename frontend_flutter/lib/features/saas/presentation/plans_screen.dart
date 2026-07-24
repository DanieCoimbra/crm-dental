import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

class AnnualBillingNotifier extends Notifier<bool> {
  @override
  bool build() => false;
  void setAnnual(bool val) => state = val;
}

final isAnnualBillingProvider = NotifierProvider<AnnualBillingNotifier, bool>(AnnualBillingNotifier.new);

class PlansScreen extends ConsumerWidget {
  const PlansScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final billingParam = GoRouterState.of(context).uri.queryParameters['billing'];
    if (billingParam == 'annual' && !ref.read(isAnnualBillingProvider)) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        ref.read(isAnnualBillingProvider.notifier).setAnnual(true);
      });
    }

    final isAnnual = ref.watch(isAnnualBillingProvider);
    final theme = Theme.of(context);

    return Scaffold(
      backgroundColor: Colors.grey[50],
      appBar: AppBar(
        backgroundColor: Colors.white,
        elevation: 0,
        title: Row(
          children: [
            Icon(LucideIcons.activity, color: theme.colorScheme.primary),
            const SizedBox(width: 8),
            const Text(
              'DentalCRM',
              style: TextStyle(color: Colors.black, fontWeight: FontWeight.bold),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => context.go('/login'),
            child: const Text('Já tem conta? Entrar', style: TextStyle(fontWeight: FontWeight.bold)),
          ),
          const SizedBox(width: 16),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 40),
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 1200),
            child: Column(
              children: [
                // Header
                const Text(
                  'Planos e Preços',
                  style: TextStyle(fontSize: 36, fontWeight: FontWeight.bold, color: Colors.black87),
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 12),
                const Text(
                  'Escolha o plano ideal para a sua clínica dental e comece a transformar seu atendimento hoje mesmo.',
                  style: TextStyle(fontSize: 16, color: Colors.grey),
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 32),

                // Toggle Mensal / Anual
                Container(
                  padding: const EdgeInsets.all(4),
                  decoration: BoxDecoration(
                    color: Colors.grey[200],
                    borderRadius: BorderRadius.circular(30),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      GestureDetector(
                        onTap: () => ref.read(isAnnualBillingProvider.notifier).setAnnual(false),
                        child: Container(
                          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 10),
                          decoration: BoxDecoration(
                            color: !isAnnual ? theme.colorScheme.primary : Colors.transparent,
                            borderRadius: BorderRadius.circular(25),
                          ),
                          child: Text(
                            'Mensal',
                            style: TextStyle(
                              color: !isAnnual ? Colors.white : Colors.black87,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                        ),
                      ),
                      GestureDetector(
                        onTap: () => ref.read(isAnnualBillingProvider.notifier).setAnnual(true),
                        child: Container(
                          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 10),
                          decoration: BoxDecoration(
                            color: isAnnual ? theme.colorScheme.primary : Colors.transparent,
                            borderRadius: BorderRadius.circular(25),
                          ),
                          child: Row(
                            children: [
                              Text(
                                'Anual',
                                style: TextStyle(
                                  color: isAnnual ? Colors.white : Colors.black87,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                              const SizedBox(width: 6),
                              Container(
                                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                                decoration: BoxDecoration(
                                  color: Colors.green,
                                  borderRadius: BorderRadius.circular(10),
                                ),
                                child: const Text(
                                  '20% OFF',
                                  style: TextStyle(color: Colors.white, fontSize: 10, fontWeight: FontWeight.bold),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ],
                  ),
                ),

                const SizedBox(height: 48),

                // Pricing Cards Layout
                LayoutBuilder(
                  builder: (context, constraints) {
                    final isMobile = constraints.maxWidth < 900;
                    return Flex(
                      direction: isMobile ? Axis.vertical : Axis.horizontal,
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        // Trial Card
                        Expanded(
                          flex: isMobile ? 0 : 1,
                          child: _buildPlanCard(
                            context: context,
                            title: 'Modo Trial',
                            subtitle: '14 dias grátis para experimentar',
                            price: 'R\$ 0',
                            period: 'por 14 dias',
                            isPopular: false,
                            buttonText: 'Testar Grátis',
                            buttonColor: Colors.blue[50]!,
                            textColor: Colors.blue[900]!,
                            features: [
                              'Acesso total ao sistema',
                              'Sem cartão de crédito',
                              'Até 2 odontólogos',
                              'Suporte padrão',
                            ],
                            onTap: () => context.go('/register-clinic?plan=trial'),
                          ),
                        ),
                        const SizedBox(width: 16, height: 16),
                        // Start Card
                        Expanded(
                          flex: isMobile ? 0 : 1,
                          child: _buildPlanCard(
                            context: context,
                            title: 'Start',
                            subtitle: 'Ideal para consultórios individuais',
                            price: isAnnual ? 'R\$ 79' : 'R\$ 99',
                            period: '/mês',
                            isPopular: false,
                            buttonText: 'Assinar Start',
                            buttonColor: Colors.grey[900]!,
                            textColor: Colors.white,
                            features: [
                              '1 Odontólogo',
                              'Prontuário Eletrônico',
                              'Agenda Inteligente',
                              'Exportação de Relatórios',
                              'Suporte via E-mail',
                            ],
                            onTap: () => _onSelectPaidPlan(context, 'start', isAnnual),
                          ),
                        ),
                        const SizedBox(width: 16, height: 16),
                        // Pro Card (Mais Popular)
                        Expanded(
                          flex: isMobile ? 0 : 1,
                          child: _buildPlanCard(
                            context: context,
                            title: 'Pro',
                            subtitle: 'Para clínicas em crescimento',
                            price: isAnnual ? 'R\$ 159' : 'R\$ 199',
                            period: '/mês',
                            isPopular: true,
                            buttonText: 'Assinar Pro',
                            buttonColor: theme.colorScheme.primary,
                            textColor: Colors.white,
                            features: [
                              'Até 5 Odontólogos',
                              'Gestão Financeira Completa',
                              'Notificações WhatsApp',
                              'Controle de Estoque',
                              'Suporte Prioritário 24/7',
                            ],
                            onTap: () => _onSelectPaidPlan(context, 'pro', isAnnual),
                          ),
                        ),
                        const SizedBox(width: 16, height: 16),
                        // Enterprise Card
                        Expanded(
                          flex: isMobile ? 0 : 1,
                          child: _buildPlanCard(
                            context: context,
                            title: 'Enterprise',
                            subtitle: 'Para grandes clínicas e redes',
                            price: isAnnual ? 'R\$ 319' : 'R\$ 399',
                            period: '/mês',
                            isPopular: false,
                            buttonText: 'Assinar Enterprise',
                            buttonColor: Colors.grey[900]!,
                            textColor: Colors.white,
                            features: [
                              'Odontólogos Ilimitados',
                              'Gestão Multi-Salas',
                              'Módulo Marketing & Afiliados',
                              'Relatórios Gerenciais Avançados',
                              'Gerente de Conta Dedicado',
                            ],
                            onTap: () => _onSelectPaidPlan(context, 'enterprise', isAnnual),
                          ),
                        ),
                      ],
                    );
                  },
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  void _onSelectPaidPlan(BuildContext context, String plan, bool isAnnual) {
    // Por enquanto, direciona para o registro com a query informando o plano
    // Na F06, integraremos com o Stripe Checkout pré-cadastro
    final cycle = isAnnual ? 'annual' : 'monthly';
    context.go('/register-clinic?plan=$plan&billing=$cycle');
  }

  Widget _buildPlanCard({
    required BuildContext context,
    required String title,
    required String subtitle,
    required String price,
    required String period,
    required bool isPopular,
    required String buttonText,
    required Color buttonColor,
    required Color textColor,
    required List<String> features,
    required VoidCallback onTap,
  }) {
    final theme = Theme.of(context);

    return Container(
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: isPopular ? theme.colorScheme.primary : Colors.grey[200]!,
          width: isPopular ? 2 : 1,
        ),
        boxShadow: [
          BoxShadow(
            color: isPopular ? theme.colorScheme.primary.withValues(alpha: 0.1) : Colors.black.withValues(alpha: 0.04),
            blurRadius: 20,
            offset: const Offset(0, 8),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (isPopular)
                Container(
                  margin: const EdgeInsets.only(bottom: 12),
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
                  decoration: BoxDecoration(
                    color: theme.colorScheme.primary,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: const Text(
                    'Mais Popular',
                    style: TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.bold),
                  ),
                ),
              Text(title, style: const TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
              const SizedBox(height: 4),
              Text(subtitle, style: const TextStyle(fontSize: 12, color: Colors.grey)),
              const SizedBox(height: 20),
              Row(
                crossAxisAlignment: CrossAxisAlignment.baseline,
                textBaseline: TextBaseline.alphabetic,
                children: [
                  Text(price, style: const TextStyle(fontSize: 32, fontWeight: FontWeight.bold)),
                  const SizedBox(width: 4),
                  Text(period, style: const TextStyle(fontSize: 14, color: Colors.grey)),
                ],
              ),
              const Divider(height: 32),
              ...features.map((f) => Padding(
                    padding: const EdgeInsets.only(bottom: 10),
                    child: Row(
                      children: [
                        const Icon(LucideIcons.check, size: 16, color: Colors.green),
                        const SizedBox(width: 8),
                        Expanded(child: Text(f, style: const TextStyle(fontSize: 13))),
                      ],
                    ),
                  )),
            ],
          ),
          const SizedBox(height: 24),
          ElevatedButton(
            onPressed: onTap,
            style: ElevatedButton.styleFrom(
              backgroundColor: buttonColor,
              padding: const EdgeInsets.symmetric(vertical: 14),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
            ),
            child: Text(
              buttonText,
              style: TextStyle(color: textColor, fontWeight: FontWeight.bold, fontSize: 15),
            ),
          ),
        ],
      ),
    );
  }
}
