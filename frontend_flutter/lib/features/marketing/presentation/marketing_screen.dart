import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:frontend_flutter/features/marketing/providers/marketing_provider.dart';
import 'package:frontend_flutter/features/marketing/data/marketing_repository.dart';

class MarketingScreen extends ConsumerStatefulWidget {
  const MarketingScreen({super.key});

  @override
  ConsumerState<MarketingScreen> createState() => _MarketingScreenState();
}

class _MarketingScreenState extends ConsumerState<MarketingScreen> with SingleTickerProviderStateMixin {
  late TabController _tabController;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 2, vsync: this);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Marketing e Afiliados'),
        bottom: TabBar(
          controller: _tabController,
          tabs: const [
            Tab(text: 'Cupons de Desconto', icon: Icon(LucideIcons.ticket)),
            Tab(text: 'Parceiros/Afiliados', icon: Icon(LucideIcons.users)),
          ],
        ),
      ),
      body: TabBarView(
        controller: _tabController,
        children: const [
          _CouponsTab(),
          _PartnersTab(),
        ],
      ),
    );
  }
}

class _CouponsTab extends ConsumerWidget {
  const _CouponsTab();

  void _showCreateDialog(BuildContext context, WidgetRef ref) {
    final codeCtrl = TextEditingController();
    final valueCtrl = TextEditingController();
    String type = 'percentage';

    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Novo Cupom'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(controller: codeCtrl, decoration: const InputDecoration(labelText: 'Código (ex: VERAO20)')),
            DropdownButtonFormField<String>(
              value: type,
              decoration: const InputDecoration(labelText: 'Tipo'),
              items: const [
                DropdownMenuItem(value: 'percentage', child: Text('Porcentagem (%)')),
                DropdownMenuItem(value: 'fixed', child: Text('Valor Fixo (R\$)')),
              ],
              onChanged: (v) => type = v ?? 'percentage',
            ),
            TextField(controller: valueCtrl, decoration: const InputDecoration(labelText: 'Valor/Porcentagem'), keyboardType: TextInputType.number),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancelar')),
          ElevatedButton(
            onPressed: () async {
              try {
                await ref.read(marketingRepositoryProvider).createPromoCode({
                  'code': codeCtrl.text,
                  'discount_type': type,
                  'discount_value': double.tryParse(valueCtrl.text) ?? 0,
                  'max_uses': 0,
                });
                ref.invalidate(promoCodesProvider);
                if (ctx.mounted) Navigator.pop(ctx);
              } catch (e) {
                if (ctx.mounted) ScaffoldMessenger.of(ctx).showSnackBar(SnackBar(content: Text('Erro: \$e')));
              }
            },
            child: const Text('Salvar'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final codesAsync = ref.watch(promoCodesProvider);

    return Scaffold(
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _showCreateDialog(context, ref),
        icon: const Icon(LucideIcons.plus),
        label: const Text('Novo Cupom'),
      ),
      body: codesAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('Erro: \$e')),
        data: (codes) {
          if (codes.isEmpty) return const Center(child: Text('Nenhum cupom cadastrado.'));
          return ListView.builder(
            itemCount: codes.length,
            itemBuilder: (context, index) {
              final code = codes[index];
              return ListTile(
                leading: const CircleAvatar(child: Icon(LucideIcons.ticket)),
                title: Text(code.code, style: const TextStyle(fontWeight: FontWeight.bold)),
                subtitle: Text("\${code.discountType == 'percentage' ? '\${code.discountValue}%' : 'R\$ \${code.discountValue}'} de desconto - Usos: \${code.usedCount}"),
                trailing: IconButton(
                  icon: const Icon(LucideIcons.trash2, color: Colors.red),
                  onPressed: () async {
                    await ref.read(marketingRepositoryProvider).deletePromoCode(code.id);
                    ref.invalidate(promoCodesProvider);
                  },
                ),
              );
            },
          );
        },
      ),
    );
  }
}

class _PartnersTab extends ConsumerWidget {
  const _PartnersTab();

  void _showCreateDialog(BuildContext context, WidgetRef ref) {
    final nameCtrl = TextEditingController();
    final emailCtrl = TextEditingController();
    final rateCtrl = TextEditingController();

    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Novo Parceiro'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(controller: nameCtrl, decoration: const InputDecoration(labelText: 'Nome')),
            TextField(controller: emailCtrl, decoration: const InputDecoration(labelText: 'Email')),
            TextField(controller: rateCtrl, decoration: const InputDecoration(labelText: 'Comissão (%)'), keyboardType: TextInputType.number),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancelar')),
          ElevatedButton(
            onPressed: () async {
              try {
                await ref.read(marketingRepositoryProvider).createPartner({
                  'name': nameCtrl.text,
                  'email': emailCtrl.text,
                  'commission_rate': double.tryParse(rateCtrl.text) ?? 0,
                });
                ref.invalidate(partnersProvider);
                if (ctx.mounted) Navigator.pop(ctx);
              } catch (e) {
                if (ctx.mounted) ScaffoldMessenger.of(ctx).showSnackBar(SnackBar(content: Text('Erro: \$e')));
              }
            },
            child: const Text('Salvar'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final partnersAsync = ref.watch(partnersProvider);

    return Scaffold(
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _showCreateDialog(context, ref),
        icon: const Icon(LucideIcons.userPlus),
        label: const Text('Novo Parceiro'),
      ),
      body: partnersAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('Erro: \$e')),
        data: (partners) {
          if (partners.isEmpty) return const Center(child: Text('Nenhum parceiro cadastrado.'));
          return ListView.builder(
            itemCount: partners.length,
            itemBuilder: (context, index) {
              final partner = partners[index];
              return ListTile(
                leading: const CircleAvatar(child: Icon(LucideIcons.users)),
                title: Text(partner.name, style: const TextStyle(fontWeight: FontWeight.bold)),
                subtitle: Text('\${partner.email} • Comissão: \${partner.commissionRate}%'),
                trailing: IconButton(
                  icon: const Icon(LucideIcons.trash2, color: Colors.red),
                  onPressed: () async {
                    await ref.read(marketingRepositoryProvider).deletePartner(partner.id);
                    ref.invalidate(partnersProvider);
                  },
                ),
              );
            },
          );
        },
      ),
    );
  }
}
