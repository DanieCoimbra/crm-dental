import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:frontend_flutter/features/financial/data/financial_model.dart';
import 'package:frontend_flutter/features/financial/data/financial_repository.dart';
import 'package:frontend_flutter/features/financial/providers/financial_provider.dart';
import 'package:frontend_flutter/features/financial/presentation/budget_form_dialog.dart';

class BudgetsScreen extends ConsumerStatefulWidget {
  final int? initialPatientId;

  const BudgetsScreen({super.key, this.initialPatientId});

  @override
  ConsumerState<BudgetsScreen> createState() => _BudgetsScreenState();
}

class _BudgetsScreenState extends ConsumerState<BudgetsScreen> {
  String _selectedStatusFilter = 'ALL';

  final List<Map<String, String>> _statusFilters = [
    {'value': 'ALL', 'label': 'Todos'},
    {'value': 'draft', 'label': 'Rascunhos'},
    {'value': 'sent', 'label': 'Enviados'},
    {'value': 'approved', 'label': 'Aprovados'},
    {'value': 'rejected', 'label': 'Rejeitados'},
  ];

  @override
  Widget build(BuildContext context) {
    final statusQuery = _selectedStatusFilter == 'ALL' ? null : _selectedStatusFilter;
    final budgetsAsync = ref.watch(
      budgetsListProvider(BudgetQueryParams(patientId: widget.initialPatientId, status: statusQuery)),
    );
    final theme = Theme.of(context);
    final currencyFormatter = NumberFormat.currency(locale: 'pt_BR', symbol: 'R\$');
    final dateFormatter = DateFormat('dd/MM/yyyy HH:mm');

    return Scaffold(
      appBar: AppBar(
        title: Text(widget.initialPatientId != null ? 'Orçamentos do Paciente' : 'Gestão de Orçamentos'),
        scrolledUnderElevation: 0,
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: 16.0),
            child: ElevatedButton.icon(
              onPressed: () => _openNewBudgetDialog(context),
              icon: const Icon(LucideIcons.plus, size: 18),
              label: const Text('Novo Orçamento'),
            ),
          ),
        ],
      ),
      body: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Status Filter Chips
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                children: _statusFilters.map((filter) {
                  final isSelected = _selectedStatusFilter == filter['value'];
                  return Padding(
                    padding: const EdgeInsets.only(right: 8.0),
                    child: FilterChip(
                      selected: isSelected,
                      label: Text(filter['label']!),
                      onSelected: (selected) {
                        if (selected) {
                          setState(() => _selectedStatusFilter = filter['value']!);
                        }
                      },
                    ),
                  );
                }).toList(),
              ),
            ),
            const SizedBox(height: 20),

            // Budgets List
            Expanded(
              child: budgetsAsync.when(
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (err, _) => Center(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      const Icon(LucideIcons.alertTriangle, color: Colors.red, size: 48),
                      const SizedBox(height: 12),
                      Text('Erro ao carregar orçamentos: $err'),
                      const SizedBox(height: 16),
                      ElevatedButton.icon(
                        onPressed: () => ref.invalidate(budgetsListProvider),
                        icon: const Icon(LucideIcons.refreshCw),
                        label: const Text('Tentar Novamente'),
                      ),
                    ],
                  ),
                ),
                data: (budgets) {
                  if (budgets.isEmpty) {
                    return Center(
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          const Icon(LucideIcons.fileText, size: 64, color: Colors.grey),
                          const SizedBox(height: 16),
                          const Text(
                            'Nenhum orçamento encontrado.',
                            style: TextStyle(fontSize: 16, color: Colors.grey),
                          ),
                          const SizedBox(height: 16),
                          ElevatedButton.icon(
                            onPressed: () => _openNewBudgetDialog(context),
                            icon: const Icon(LucideIcons.plus),
                            label: const Text('Criar Orçamento'),
                          ),
                        ],
                      ),
                    );
                  }

                  return ListView.builder(
                    itemCount: budgets.length,
                    itemBuilder: (context, index) {
                      final b = budgets[index];
                      final isPendingAction = b.status == 'draft' || b.status == 'sent';

                      return Card(
                        margin: const EdgeInsets.only(bottom: 16),
                        child: Padding(
                          padding: const EdgeInsets.all(20.0),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              // Top Row: Patient Name & Status Chip
                              Row(
                                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                                children: [
                                  Row(
                                    children: [
                                      const CircleAvatar(
                                        radius: 18,
                                        child: Icon(LucideIcons.user, size: 18),
                                      ),
                                      const SizedBox(width: 12),
                                      Column(
                                        crossAxisAlignment: CrossAxisAlignment.start,
                                        children: [
                                          Text(
                                            b.patientName ?? 'Paciente #${b.patientId}',
                                            style: const TextStyle(
                                              fontWeight: FontWeight.bold,
                                              fontSize: 16,
                                            ),
                                          ),
                                          if (b.createdAt != null)
                                            Text(
                                              'Criado em: ${dateFormatter.format(b.createdAt!)}',
                                              style: TextStyle(fontSize: 12, color: theme.textTheme.bodySmall?.color),
                                            ),
                                        ],
                                      ),
                                    ],
                                  ),
                                  _buildStatusBadge(b.status),
                                ],
                              ),
                              const Divider(height: 24),

                              // Summary Info
                              Row(
                                children: [
                                  Expanded(
                                    child: Column(
                                      crossAxisAlignment: CrossAxisAlignment.start,
                                      children: [
                                        Text(
                                          'Itens: ${b.items.length} procedimento(s)',
                                          style: const TextStyle(fontWeight: FontWeight.w500),
                                        ),
                                        if (b.notes != null && b.notes!.isNotEmpty)
                                          Padding(
                                            padding: const EdgeInsets.only(top: 4.0),
                                            child: Text(
                                              'Obs: ${b.notes}',
                                              style: TextStyle(
                                                fontSize: 13,
                                                fontStyle: FontStyle.italic,
                                                color: theme.textTheme.bodyMedium?.color,
                                              ),
                                              maxLines: 1,
                                              overflow: TextOverflow.ellipsis,
                                            ),
                                          ),
                                      ],
                                    ),
                                  ),
                                  Column(
                                    crossAxisAlignment: CrossAxisAlignment.end,
                                    children: [
                                      if (b.discountCents > 0)
                                        Text(
                                          'Desconto: - ${currencyFormatter.format(b.discount)}',
                                          style: const TextStyle(fontSize: 12, color: Colors.red),
                                        ),
                                      Text(
                                        currencyFormatter.format(b.finalAmount),
                                        style: TextStyle(
                                          fontSize: 20,
                                          fontWeight: FontWeight.bold,
                                          color: theme.colorScheme.primary,
                                        ),
                                      ),
                                    ],
                                  ),
                                ],
                              ),
                              const SizedBox(height: 16),

                              // Actions Row
                              Row(
                                mainAxisAlignment: MainAxisAlignment.end,
                                children: [
                                  OutlinedButton.icon(
                                    onPressed: () => _showBudgetDetailsModal(context, b),
                                    icon: const Icon(LucideIcons.eye, size: 16),
                                    label: const Text('Ver Detalhes'),
                                  ),
                                  const SizedBox(width: 8),
                                  if (isPendingAction) ...[
                                    OutlinedButton.icon(
                                      style: OutlinedButton.styleFrom(
                                        foregroundColor: Colors.red,
                                        side: const BorderSide(color: Colors.red),
                                      ),
                                      onPressed: () => _confirmReject(context, b),
                                      icon: const Icon(LucideIcons.xCircle, size: 16),
                                      label: const Text('Rejeitar'),
                                    ),
                                    const SizedBox(width: 8),
                                    ElevatedButton.icon(
                                      style: ElevatedButton.styleFrom(
                                        backgroundColor: Colors.green,
                                        foregroundColor: Colors.white,
                                      ),
                                      onPressed: () => _showApproveModal(context, b),
                                      icon: const Icon(LucideIcons.checkCircle, size: 16),
                                      label: const Text('Aprovar Orçamento'),
                                    ),
                                  ],
                                ],
                              ),
                            ],
                          ),
                        ),
                      );
                    },
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildStatusBadge(String status) {
    Color bg;
    Color fg;
    String label;

    switch (status) {
      case 'approved':
        bg = Colors.green.withValues(alpha: 0.15);
        fg = Colors.green.shade700;
        label = 'Aprovado';
        break;
      case 'rejected':
        bg = Colors.red.withValues(alpha: 0.15);
        fg = Colors.red.shade700;
        label = 'Rejeitado';
        break;
      case 'sent':
        bg = Colors.blue.withValues(alpha: 0.15);
        fg = Colors.blue.shade700;
        label = 'Enviado';
        break;
      case 'draft':
      default:
        bg = Colors.grey.withValues(alpha: 0.2);
        fg = Colors.grey.shade800;
        label = 'Rascunho';
        break;
    }

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: fg.withValues(alpha: 0.4)),
      ),
      child: Text(
        label,
        style: TextStyle(color: fg, fontWeight: FontWeight.bold, fontSize: 13),
      ),
    );
  }

  void _openNewBudgetDialog(BuildContext context) {
    showDialog(
      context: context,
      builder: (context) => BudgetFormDialog(initialPatientId: widget.initialPatientId),
    ).then((updated) {
      if (updated == true) {
        ref.invalidate(budgetsListProvider);
      }
    });
  }

  void _showBudgetDetailsModal(BuildContext context, Budget budget) {
    final currencyFormatter = NumberFormat.currency(locale: 'pt_BR', symbol: 'R\$');
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Detalhes do Orçamento #${budget.id}'),
        content: SizedBox(
          width: 550,
          child: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text('Paciente: ${budget.patientName ?? 'Paciente #${budget.patientId}'}', style: const TextStyle(fontWeight: FontWeight.bold)),
                Text('Status: ${budget.status.toUpperCase()}'),
                if (budget.notes != null && budget.notes!.isNotEmpty) ...[
                  const SizedBox(height: 8),
                  Text('Observações: ${budget.notes}'),
                ],
                const Divider(height: 24),
                const Text('Itens do Orçamento:', style: TextStyle(fontWeight: FontWeight.bold)),
                const SizedBox(height: 8),
                Table(
                  border: TableBorder.all(color: Colors.grey.shade300),
                  columnWidths: const {
                    0: FlexColumnWidth(3),
                    1: FlexColumnWidth(1.5),
                    2: FlexColumnWidth(1.5),
                    3: FlexColumnWidth(2),
                  },
                  children: [
                    const TableRow(
                      decoration: BoxDecoration(color: Color(0xFFF3F4F6)),
                      children: [
                        Padding(padding: EdgeInsets.all(8), child: Text('Procedimento', style: TextStyle(fontWeight: FontWeight.bold))),
                        Padding(padding: EdgeInsets.all(8), child: Text('Dente/Face', style: TextStyle(fontWeight: FontWeight.bold))),
                        Padding(padding: EdgeInsets.all(8), child: Text('Qtd', style: TextStyle(fontWeight: FontWeight.bold))),
                        Padding(padding: EdgeInsets.all(8), child: Text('Subtotal', style: TextStyle(fontWeight: FontWeight.bold))),
                      ],
                    ),
                    ...budget.items.map((item) {
                      final toothFaceStr = [
                        if (item.toothNumber != null) 'Dente ${item.toothNumber}',
                        if (item.face != null && item.face!.isNotEmpty) item.face,
                      ].join(' - ');

                      return TableRow(
                        children: [
                          Padding(padding: const EdgeInsets.all(8), child: Text(item.procedureName ?? 'Item #${item.procedureId}')),
                          Padding(padding: const EdgeInsets.all(8), child: Text(toothFaceStr.isEmpty ? '-' : toothFaceStr)),
                          Padding(padding: const EdgeInsets.all(8), child: Text('${item.quantity}')),
                          Padding(padding: const EdgeInsets.all(8), child: Text(currencyFormatter.format(item.subtotal))),
                        ],
                      );
                    }),
                  ],
                ),
                const SizedBox(height: 16),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    const Text('Subtotal:'),
                    Text(currencyFormatter.format(budget.totalAmount)),
                  ],
                ),
                if (budget.discountCents > 0)
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      const Text('Desconto:'),
                      Text('- ${currencyFormatter.format(budget.discount)}', style: const TextStyle(color: Colors.red)),
                    ],
                  ),
                const Divider(),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    const Text('Valor Final:', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
                    Text(
                      currencyFormatter.format(budget.finalAmount),
                      style: TextStyle(fontWeight: FontWeight.bold, fontSize: 18, color: Theme.of(context).colorScheme.primary),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Fechar'),
          ),
        ],
      ),
    );
  }

  void _showApproveModal(BuildContext context, Budget budget) {
    showDialog(
      context: context,
      builder: (context) => _ApproveBudgetDialog(budget: budget),
    ).then((approved) {
      if (approved == true) {
        ref.invalidate(budgetsListProvider);
        ref.invalidate(financialTransactionsProvider);
        ref.invalidate(cashFlowSummaryProvider);
      }
    });
  }

  void _confirmReject(BuildContext context, Budget budget) {
    final currencyFormatter = NumberFormat.currency(locale: 'pt_BR', symbol: 'R\$');
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Rejeitar Orçamento'),
        content: Text('Tem certeza que deseja marcar o orçamento de ${currencyFormatter.format(budget.finalAmount)} como REJEITADO?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Cancelar'),
          ),
          ElevatedButton(
            style: ElevatedButton.styleFrom(backgroundColor: Colors.red, foregroundColor: Colors.white),
            onPressed: () async {
              Navigator.of(context).pop();
              try {
                await ref.read(financialRepositoryProvider).rejectBudget(budget.id);
                ref.invalidate(budgetsListProvider);
                if (context.mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('Orçamento marcado como rejeitado.')),
                  );
                }
              } catch (e) {
                if (context.mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(
                    SnackBar(content: Text('Erro ao rejeitar orçamento: $e')),
                  );
                }
              }
            },
            child: const Text('Confirmar Rejeição'),
          ),
        ],
      ),
    );
  }
}

class _ApproveBudgetDialog extends ConsumerStatefulWidget {
  final Budget budget;
  const _ApproveBudgetDialog({required this.budget});

  @override
  ConsumerState<_ApproveBudgetDialog> createState() => _ApproveBudgetDialogState();
}

class _ApproveBudgetDialogState extends ConsumerState<_ApproveBudgetDialog> {
  int _installmentsCount = 1;
  String _selectedPaymentMethod = 'PIX';
  bool _isApproving = false;

  final List<String> _paymentMethods = [
    'PIX',
    'Dinheiro',
    'Cartão de Crédito',
    'Cartão de Débito',
    'Boleto Bancário',
  ];

  @override
  Widget build(BuildContext context) {
    final currencyFormatter = NumberFormat.currency(locale: 'pt_BR', symbol: 'R\$');
    final installmentVal = widget.budget.finalAmount / _installmentsCount;

    return AlertDialog(
      title: const Text('Aprovar Orçamento'),
      content: SizedBox(
        width: 440,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Paciente: ${widget.budget.patientName ?? 'Paciente #${widget.budget.patientId}'}'),
            const SizedBox(height: 4),
            Text(
              'Valor Total: ${currencyFormatter.format(widget.budget.finalAmount)}',
              style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
            ),
            const Divider(height: 24),
            DropdownButtonFormField<int>(
              initialValue: _installmentsCount,
              decoration: const InputDecoration(
                labelText: 'Número de Parcelas',
                border: OutlineInputBorder(),
              ),
              items: List.generate(12, (index) => index + 1).map((count) {
                return DropdownMenuItem<int>(
                  value: count,
                  child: Text('${count}x ${count == 1 ? '(À vista)' : ''}'),
                );
              }).toList(),
              onChanged: (val) {
                if (val != null) setState(() => _installmentsCount = val);
              },
            ),
            const SizedBox(height: 16),
            DropdownButtonFormField<String>(
              initialValue: _selectedPaymentMethod,
              decoration: const InputDecoration(
                labelText: 'Forma de Pagamento',
                border: OutlineInputBorder(),
              ),
              items: _paymentMethods.map((pm) {
                return DropdownMenuItem<String>(
                  value: pm,
                  child: Text(pm),
                );
              }).toList(),
              onChanged: (val) {
                if (val != null) setState(() => _selectedPaymentMethod = val);
              },
            ),
            const SizedBox(height: 20),
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Colors.green.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: Colors.green.withValues(alpha: 0.3)),
              ),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Text('Plano de Parcelamento:', style: TextStyle(fontWeight: FontWeight.bold)),
                  Text(
                    '${_installmentsCount}x de ${currencyFormatter.format(installmentVal)}',
                    style: const TextStyle(fontWeight: FontWeight.bold, color: Colors.green),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _isApproving ? null : () => Navigator.of(context).pop(false),
          child: const Text('Cancelar'),
        ),
        ElevatedButton.icon(
          style: ElevatedButton.styleFrom(backgroundColor: Colors.green, foregroundColor: Colors.white),
          onPressed: _isApproving ? null : _approve,
          icon: const Icon(LucideIcons.check, size: 18),
          label: _isApproving
              ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
              : const Text('Confirmar Aprovação'),
        ),
      ],
    );
  }

  Future<void> _approve() async {
    setState(() => _isApproving = true);
    try {
      final repo = ref.read(financialRepositoryProvider);
      await repo.approveBudget(
        widget.budget.id,
        installmentsCount: _installmentsCount,
        paymentMethod: _selectedPaymentMethod,
      );

      if (mounted) {
        Navigator.of(context).pop(true);
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Orçamento APROVADO! Contas a receber e parcelas geradas com sucesso.')),
        );
      }
    } catch (e) {
      if (mounted) {
        setState(() => _isApproving = false);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Erro ao aprovar orçamento: $e')),
        );
      }
    }
  }
}
