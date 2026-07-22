import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/settings/data/settings_provider.dart';
import 'package:frontend_flutter/features/settings/data/settings_repository.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

class SaaSTab extends ConsumerStatefulWidget {
  const SaaSTab({super.key});

  @override
  ConsumerState<SaaSTab> createState() => _SaaSTabState();
}

class _SaaSTabState extends ConsumerState<SaaSTab> {
  final _couponCtrl = TextEditingController();
  bool _isLoading = false;
  Map<String, dynamic>? _validatedCoupon;

  @override
  void dispose() {
    _couponCtrl.dispose();
    super.dispose();
  }

  Future<void> _validateCoupon() async {
    if (_couponCtrl.text.trim().isEmpty) return;
    
    setState(() => _isLoading = true);
    
    try {
      final repo = ref.read(settingsRepositoryProvider);
      final coupon = await repo.validateCoupon(_couponCtrl.text.trim());
      setState(() {
        _validatedCoupon = coupon;
      });
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Cupom validado com sucesso!')),
        );
      }
    } catch (e) {
      setState(() {
        _validatedCoupon = null;
      });
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Cupom inválido: $e')),
        );
      }
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  Future<void> _applyCoupon() async {
    if (_validatedCoupon == null) return;
    
    setState(() => _isLoading = true);
    
    try {
      final repo = ref.read(settingsRepositoryProvider);
      await repo.applyCoupon(_validatedCoupon!['code']);
      
      setState(() {
        _validatedCoupon = null;
        _couponCtrl.clear();
      });
      
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Cupom aplicado à sua assinatura com sucesso!'),
            backgroundColor: Colors.green,
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Erro ao aplicar: $e')),
        );
      }
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  Future<void> _changePlan(String plan) async {
    setState(() => _isLoading = true);
    try {
      await ref.read(settingsRepositoryProvider).changePlan(plan);
      ref.invalidate(myClinicProvider);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Plano $plan ativado com sucesso!')),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Erro ao alterar plano: $e')),
        );
      }
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  Widget _buildPlanCard(String title, String price, String description, List<String> features, bool isCurrentPlan, String planKey) {
    return Card(
      elevation: isCurrentPlan ? 4 : 1,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(
          color: isCurrentPlan ? Colors.blue : (Theme.of(context).dividerTheme.color ?? Colors.grey.shade200),
          width: isCurrentPlan ? 2 : 1,
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (isCurrentPlan)
              Container(
                margin: const EdgeInsets.only(bottom: 16),
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
                decoration: BoxDecoration(
                  color: Colors.blue.shade100,
                  borderRadius: BorderRadius.circular(16),
                ),
                child: const Text('SEU PLANO ATUAL', style: TextStyle(color: Colors.blue, fontWeight: FontWeight.bold, fontSize: 12)),
              ),
            Text(title, style: const TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            Text(description, style: const TextStyle(color: Colors.grey)),
            const SizedBox(height: 16),
            Text('R\$ $price', style: const TextStyle(fontSize: 32, fontWeight: FontWeight.bold)),
            const Text('/ mês', style: TextStyle(color: Colors.grey)),
            const SizedBox(height: 24),
            ...features.map((f) => Padding(
              padding: const EdgeInsets.only(bottom: 8.0),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.center,
                children: [
                  const Icon(LucideIcons.checkCircle2, color: Colors.green, size: 18),
                  const SizedBox(width: 8),
                  Expanded(child: Text(f)),
                ],
              ),
            )),
            const SizedBox(height: 24),
            SizedBox(
              width: double.infinity,
              child: ElevatedButton(
                onPressed: isCurrentPlan || _isLoading ? null : () => _changePlan(planKey),
                style: ElevatedButton.styleFrom(
                  backgroundColor: isCurrentPlan ? Colors.grey.shade200 : Colors.blue,
                  foregroundColor: isCurrentPlan ? Colors.grey : Colors.white,
                  elevation: 0,
                ),
                child: Text(isCurrentPlan ? 'Plano Atual' : 'Mudar para $title'),
              ),
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final clinicAsync = ref.watch(myClinicProvider);

    return SingleChildScrollView(
      padding: const EdgeInsets.all(24.0),
      child: Center(
        child: Container(
          constraints: const BoxConstraints(maxWidth: 1000),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Card(
                elevation: 0,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12),
                  side: BorderSide(color: Theme.of(context).dividerTheme.color ?? Colors.grey.shade200),
                ),
                child: Padding(
                  padding: const EdgeInsets.all(24.0),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Row(
                        children: [
                          Icon(LucideIcons.crown, color: Colors.amber, size: 28),
                          SizedBox(width: 12),
                          Text('Plano Atual', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                        ],
                      ),
                      const SizedBox(height: 24),
                      clinicAsync.when(
                        loading: () => const CircularProgressIndicator(),
                        error: (e, _) => Text('Erro: $e'),
                        data: (clinic) {
                          final currentPlan = clinic.plan.toLowerCase();
                          
                          return Column(
                            children: [
                              LayoutBuilder(
                                builder: (context, constraints) {
                                  if (constraints.maxWidth > 800) {
                                    return Row(
                                      crossAxisAlignment: CrossAxisAlignment.start,
                                      children: [
                                        Expanded(child: _buildPlanCard('Basic', '150,00', 'Para começar bem.', ['Agendas', 'Pacientes', 'Equipe', 'Dashboard Básico'], currentPlan == 'basic', 'basic')),
                                        const SizedBox(width: 16),
                                        Expanded(child: _buildPlanCard('Pro', '200,00', 'Mais controle.', ['Tudo do Basic', 'Módulo de Estoque completo'], currentPlan == 'pro', 'pro')),
                                        const SizedBox(width: 16),
                                        Expanded(child: _buildPlanCard('Premium', '250,00', 'Gestão total.', ['Tudo do Pro', 'Módulo Financeiro', 'Acesso Total'], currentPlan == 'premium', 'premium')),
                                      ],
                                    );
                                  } else {
                                    return Column(
                                      children: [
                                        _buildPlanCard('Basic', '150,00', 'Para começar bem.', ['Agendas', 'Pacientes', 'Equipe', 'Dashboard Básico'], currentPlan == 'basic', 'basic'),
                                        const SizedBox(height: 16),
                                        _buildPlanCard('Pro', '200,00', 'Mais controle.', ['Tudo do Basic', 'Módulo de Estoque completo'], currentPlan == 'pro', 'pro'),
                                        const SizedBox(height: 16),
                                        _buildPlanCard('Premium', '250,00', 'Gestão total.', ['Tudo do Pro', 'Módulo Financeiro', 'Acesso Total'], currentPlan == 'premium', 'premium'),
                                      ],
                                    );
                                  }
                                }
                              ),
                            ],
                          );
                        },
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 24),
              Card(
                elevation: 0,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12),
                  side: BorderSide(color: Theme.of(context).dividerTheme.color ?? Colors.grey.shade200),
                ),
                child: Padding(
                  padding: const EdgeInsets.all(24.0),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Row(
                        children: [
                          Icon(LucideIcons.tag, color: Colors.blue, size: 24),
                          SizedBox(width: 12),
                          Text('Cupom de Desconto', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                        ],
                      ),
                      const SizedBox(height: 8),
                      const Text(
                        'Tem um cupom de afiliado ou parceiro? Aplique aqui para receber o desconto na próxima fatura.',
                        style: TextStyle(color: Colors.grey),
                      ),
                      const SizedBox(height: 24),
                      Row(
                        children: [
                          Expanded(
                            child: TextFormField(
                              controller: _couponCtrl,
                              decoration: const InputDecoration(
                                labelText: 'Código do Cupom',
                                border: OutlineInputBorder(),
                                prefixIcon: Icon(LucideIcons.ticket),
                              ),
                              textCapitalization: TextCapitalization.characters,
                              onChanged: (_) {
                                if (_validatedCoupon != null) {
                                  setState(() => _validatedCoupon = null);
                                }
                              },
                            ),
                          ),
                          const SizedBox(width: 16),
                          ElevatedButton(
                            onPressed: _isLoading ? null : _validateCoupon,
                            style: ElevatedButton.styleFrom(
                              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
                            ),
                            child: _isLoading
                                ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                                : const Text('Validar'),
                          ),
                        ],
                      ),
                      if (_validatedCoupon != null) ...[
                        const SizedBox(height: 24),
                        Container(
                          padding: const EdgeInsets.all(16),
                          decoration: BoxDecoration(
                            color: Colors.blue.shade50,
                            borderRadius: BorderRadius.circular(8),
                            border: Border.all(color: Colors.blue.shade200),
                          ),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Row(
                                children: [
                                  const Icon(LucideIcons.checkCircle2, color: Colors.blue),
                                  const SizedBox(width: 8),
                                  Text(
                                    'Cupom ${_validatedCoupon!['code']} válido!',
                                    style: const TextStyle(fontWeight: FontWeight.bold, color: Colors.blue),
                                  ),
                                ],
                              ),
                              const SizedBox(height: 12),
                              Text(
                                'Desconto: ${_validatedCoupon!['discount_type'] == 'percentage' ? '${_validatedCoupon!['discount_value']}%' : 'R\$ ${_validatedCoupon!['discount_value']}'}',
                                style: const TextStyle(fontSize: 16),
                              ),
                              const SizedBox(height: 16),
                              SizedBox(
                                width: double.infinity,
                                child: FilledButton(
                                  onPressed: _isLoading ? null : _applyCoupon,
                                  child: _isLoading
                                      ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                                      : const Text('Aplicar Desconto'),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
