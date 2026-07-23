import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:frontend_flutter/features/auth/providers/auth_provider.dart';
import 'package:frontend_flutter/core/network/api_client.dart';
import 'package:dio/dio.dart';

class SaasCheckoutScreen extends ConsumerWidget {
  const SaasCheckoutScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      backgroundColor: Colors.grey[100],
      appBar: AppBar(
        title: const Text('Regularizar Assinatura'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => context.go('/login'),
        ),
      ),
      body: Center(
        child: Container(
          constraints: const BoxConstraints(maxWidth: 800),
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Icon(Icons.workspace_premium, size: 64, color: Colors.blue),
              const SizedBox(height: 24),
              Text(
                'Escolha seu Plano',
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
              ),
              const SizedBox(height: 24),
              const Center(
                child: SizedBox(
                  width: 400,
                  child: _PromoCodeInput(),
                ),
              ),
              const SizedBox(height: 48),
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  _PlanCard(
                    title: 'Plano Pro',
                    price: 'R\$ 99,00 / mês',
                    features: const [
                      'Até 5 dentistas',
                      'Agenda Inteligente',
                      'Controle Financeiro',
                      'Suporte por E-mail',
                    ],
                    onSelect: () {
                      _showPaymentDialog(context, ref, 'pro');
                    },
                  ),
                  const SizedBox(width: 32),
                  _PlanCard(
                    title: 'Plano Premium',
                    price: 'R\$ 199,00 / mês',
                    isPopular: true,
                    features: const [
                      'Dentistas Ilimitados',
                      'Módulo de Estoque',
                      'Integração WhatsApp',
                      'Suporte Prioritário',
                    ],
                    onSelect: () {
                      _showPaymentDialog(context, ref, 'premium');
                    },
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  void _showPaymentDialog(BuildContext context, WidgetRef ref, String plan) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Pagamento - Plano $plan'),
        content: const Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('Escaneie o QR Code PIX para pagar:'),
            SizedBox(height: 16),
            Icon(Icons.qr_code_2, size: 150),
            SizedBox(height: 16),
            Text('Ou pague com Cartão de Crédito pela Stripe.'),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('Cancelar'),
          ),
          ElevatedButton(
            onPressed: () async {
              // Simulated API request
              // await ref.read(settingsRepositoryProvider).changePlan(plan);
              
              // Em um sistema real, o webhook da stripe faria o desbloqueio 
              // no banco, ou a API retornaria sucesso. Aqui, vamos só dar reload
              Navigator.of(ctx).pop();
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('Pagamento aprovado! O sistema será liberado.')),
              );
              
              // Invalida a clínica atual para recarregar o status
              ref.invalidate(currentClinicProvider);
              context.go('/dashboard');
            },
            child: const Text('Simular Pagamento Sucedido'),
          ),
        ],
      ),
    );
  }
}

class _PlanCard extends StatelessWidget {
  final String title;
  final String price;
  final List<String> features;
  final bool isPopular;
  final VoidCallback onSelect;

  const _PlanCard({
    required this.title,
    required this.price,
    required this.features,
    required this.onSelect,
    this.isPopular = false,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 300,
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        border: isPopular ? Border.all(color: Colors.blue, width: 2) : null,
        boxShadow: [
          BoxShadow(
            color: Colors.black.withOpacity(0.05),
            blurRadius: 10,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (isPopular)
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
              margin: const EdgeInsets.only(bottom: 16),
              decoration: BoxDecoration(
                color: Colors.blue,
                borderRadius: BorderRadius.circular(16),
              ),
              child: const Text(
                'MAIS POPULAR',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 12,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ),
          Text(
            title,
            style: const TextStyle(fontSize: 24, fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 8),
          Text(
            price,
            style: TextStyle(
              fontSize: 20,
              color: Colors.grey[600],
              fontWeight: FontWeight.w500,
            ),
          ),
          const SizedBox(height: 24),
          ...features.map((f) => Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: Row(
                  children: [
                    const Icon(Icons.check_circle, color: Colors.green, size: 20),
                    const SizedBox(width: 8),
                    Text(f),
                  ],
                ),
              )),
          const SizedBox(height: 24),
          ElevatedButton(
            onPressed: onSelect,
            style: ElevatedButton.styleFrom(
              minimumSize: const Size(double.infinity, 48),
            ),
            child: const Text('Assinar Agora'),
          ),
        ],
      ),
    );
  }
}

class _PromoCodeInput extends ConsumerStatefulWidget {
  const _PromoCodeInput();

  @override
  ConsumerState<_PromoCodeInput> createState() => _PromoCodeInputState();
}

class _PromoCodeInputState extends ConsumerState<_PromoCodeInput> {
  final _controller = TextEditingController();
  bool _isLoading = false;
  String? _successMessage;
  String? _errorMessage;

  Future<void> _applyCoupon() async {
    final code = _controller.text.trim();
    if (code.isEmpty) return;

    setState(() {
      _isLoading = true;
      _successMessage = null;
      _errorMessage = null;
    });

    try {
      final dio = ref.read(dioProvider);
      await dio.post('/saas/apply-coupon', data: {'code': code});
      if (!mounted) return;
      setState(() {
        _successMessage = 'Cupom aplicado com sucesso!';
      });
    } on DioException catch (e) {
      if (!mounted) return;
      setState(() {
        _errorMessage = e.response?.data['error'] ?? 'Erro ao aplicar cupom';
      });
    } finally {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Expanded(
              child: TextField(
                controller: _controller,
                decoration: const InputDecoration(
                  labelText: 'Código Promocional / Afiliado',
                  border: OutlineInputBorder(),
                  prefixIcon: Icon(Icons.local_offer),
                ),
                textCapitalization: TextCapitalization.characters,
              ),
            ),
            const SizedBox(width: 16),
            ElevatedButton(
              onPressed: _isLoading ? null : _applyCoupon,
              style: ElevatedButton.styleFrom(
                padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 20),
              ),
              child: _isLoading
                  ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Text('Aplicar'),
            ),
          ],
        ),
        if (_successMessage != null)
          Padding(
            padding: const EdgeInsets.only(top: 8.0),
            child: Text(_successMessage!, style: const TextStyle(color: Colors.green, fontWeight: FontWeight.bold)),
          ),
        if (_errorMessage != null)
          Padding(
            padding: const EdgeInsets.only(top: 8.0),
            child: Text(_errorMessage!, style: const TextStyle(color: Colors.red, fontWeight: FontWeight.bold)),
          ),
      ],
    );
  }
}
