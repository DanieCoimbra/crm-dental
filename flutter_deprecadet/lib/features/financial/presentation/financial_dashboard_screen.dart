import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:frontend_flutter/features/financial/data/financial_model.dart';
import 'package:frontend_flutter/features/financial/data/financial_repository.dart';
import 'package:frontend_flutter/features/financial/providers/financial_provider.dart';
import 'package:frontend_flutter/features/financial/utils/receipt_generator.dart';
import 'package:frontend_flutter/features/financial/presentation/widgets/transaction_form_dialog.dart';
import 'package:frontend_flutter/features/dashboard/providers/dashboard_provider.dart';

class FinancialDashboardScreen extends ConsumerStatefulWidget {
  const FinancialDashboardScreen({super.key});

  @override
  ConsumerState<FinancialDashboardScreen> createState() => _FinancialDashboardScreenState();
}

class _FinancialDashboardScreenState extends ConsumerState<FinancialDashboardScreen> {
  String _installmentFilter = 'ALL';

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final transactionsAsync = ref.watch(financialTransactionsProvider);
    final cashFlowAsync = ref.watch(cashFlowSummaryProvider);
    final formatCurrency = NumberFormat.currency(locale: 'pt_BR', symbol: 'R\$');
    final formatDate = DateFormat('dd/MM/yyyy');

    return Scaffold(
      appBar: AppBar(
        title: const Text('Dashboard Financeiro'),
        scrolledUnderElevation: 0,
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: 8.0),
            child: OutlinedButton.icon(
              onPressed: () => context.push('/financial/procedures'),
              icon: const Icon(LucideIcons.stethoscope, size: 16),
              label: const Text('Procedimentos'),
            ),
          ),
          Padding(
            padding: const EdgeInsets.only(right: 8.0),
            child: OutlinedButton.icon(
              onPressed: () => context.push('/financial/budgets'),
              icon: const Icon(LucideIcons.fileText, size: 16),
              label: const Text('Orçamentos'),
            ),
          ),
          Padding(
            padding: const EdgeInsets.only(right: 16.0),
            child: ElevatedButton.icon(
              onPressed: () => _openTransactionDialog(context),
              icon: const Icon(LucideIcons.plus, size: 16),
              label: const Text('Nova Transação'),
            ),
          ),
        ],
      ),
      body: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // KPI Summary Row (Entradas, Pendentes, Inadimplência)
            transactionsAsync.when(
              loading: () => const Center(child: LinearProgressIndicator()),
              error: (e, _) => const SizedBox.shrink(),
              data: (transactions) {
                double totalIncome = 0;
                double totalPending = 0;
                double totalOverdue = 0;

                final now = DateTime.now();

                // Compute cash flow statistics
                for (var tx in transactions) {
                  if (tx.installments.isNotEmpty) {
                    for (var inst in tx.installments) {
                      if (inst.status == 'paid') {
                        if (tx.type == 'income') totalIncome += inst.amount;
                      } else {
                        if (inst.dueDate.isBefore(now)) {
                          totalOverdue += inst.amount;
                        } else {
                          totalPending += inst.amount;
                        }
                      }
                    }
                  } else {
                    if (tx.status == 'paid') {
                      if (tx.type == 'income') totalIncome += tx.amount;
                    } else if (tx.dueDate.isBefore(now)) {
                      totalOverdue += tx.amount;
                    } else {
                      totalPending += tx.amount;
                    }
                  }
                }

                // If backend cash flow endpoint returns specific data, use cashFlowAsync data if available
                cashFlowAsync.whenData((cf) {
                  if (cf.totalIncomeCents > 0 || cf.totalPendingCents > 0 || cf.totalOverdueCents > 0) {
                    totalIncome = cf.totalIncome;
                    totalPending = cf.totalPending;
                    totalOverdue = cf.totalOverdue;
                  }
                });

                return Row(
                  children: [
                    _buildKpiCard(
                      context,
                      title: 'Entradas (Recebido)',
                      value: formatCurrency.format(totalIncome),
                      icon: LucideIcons.arrowUpRight,
                      color: Colors.green,
                    ),
                    const SizedBox(width: 16),
                    _buildKpiCard(
                      context,
                      title: 'Pendentes (A Receber)',
                      value: formatCurrency.format(totalPending),
                      icon: LucideIcons.clock,
                      color: Colors.blue,
                    ),
                    const SizedBox(width: 16),
                    _buildKpiCard(
                      context,
                      title: 'Inadimplência (Vencidos)',
                      value: formatCurrency.format(totalOverdue),
                      icon: LucideIcons.alertTriangle,
                      color: Colors.red,
                    ),
                  ],
                );
              },
            ),
            const SizedBox(height: 28),

            // Parcelas & Transações Header + Filter Chips
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  'Parcelas & Contas a Receber',
                  style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold),
                ),
                SingleChildScrollView(
                  scrollDirection: Axis.horizontal,
                  child: Row(
                    children: [
                      FilterChip(
                        selected: _installmentFilter == 'ALL',
                        label: const Text('Todas'),
                        onSelected: (val) {
                          if (val) setState(() => _installmentFilter = 'ALL');
                        },
                      ),
                      const SizedBox(width: 8),
                      FilterChip(
                        selected: _installmentFilter == 'PENDING',
                        label: const Text('A Receber'),
                        onSelected: (val) {
                          if (val) setState(() => _installmentFilter = 'PENDING');
                        },
                      ),
                      const SizedBox(width: 8),
                      FilterChip(
                        selected: _installmentFilter == 'OVERDUE',
                        label: const Text('Vencidas'),
                        onSelected: (val) {
                          if (val) setState(() => _installmentFilter = 'OVERDUE');
                        },
                      ),
                      const SizedBox(width: 8),
                      FilterChip(
                        selected: _installmentFilter == 'PAID',
                        label: const Text('Pagas'),
                        onSelected: (val) {
                          if (val) setState(() => _installmentFilter = 'PAID');
                        },
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),

            // Transactions & Installments List
            Expanded(
              child: transactionsAsync.when(
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (e, _) => Center(child: Text('Erro ao carregar transações: $e')),
                data: (transactions) {
                  if (transactions.isEmpty) {
                    return Center(
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          const Icon(LucideIcons.dollarSign, size: 64, color: Colors.grey),
                          const SizedBox(height: 16),
                          const Text(
                            'Nenhuma transação ou parcela registrada.',
                            style: TextStyle(fontSize: 16, color: Colors.grey),
                          ),
                          const SizedBox(height: 16),
                          ElevatedButton.icon(
                            onPressed: () => _openTransactionDialog(context),
                            icon: const Icon(LucideIcons.plus),
                            label: const Text('Registrar Transação'),
                          ),
                        ],
                      ),
                    );
                  }

                  // Flatten installments with parent transaction context
                  final List<_FlatInstallmentItem> flatItems = [];
                  final now = DateTime.now();

                  for (var tx in transactions) {
                    if (tx.installments.isNotEmpty) {
                      for (var inst in tx.installments) {
                        final isPaid = inst.status == 'paid';
                        final isOverdue = !isPaid && inst.dueDate.isBefore(now);

                        if (_installmentFilter == 'PAID' && !isPaid) continue;
                        if (_installmentFilter == 'PENDING' && (isPaid || isOverdue)) continue;
                        if (_installmentFilter == 'OVERDUE' && !isOverdue) continue;

                        flatItems.add(_FlatInstallmentItem(
                          transaction: tx,
                          installment: inst,
                          isOverdue: isOverdue,
                        ));
                      }
                    } else {
                      final isPaid = tx.status == 'paid';
                      final isOverdue = !isPaid && tx.dueDate.isBefore(now);

                      if (_installmentFilter == 'PAID' && !isPaid) continue;
                      if (_installmentFilter == 'PENDING' && (isPaid || isOverdue)) continue;
                      if (_installmentFilter == 'OVERDUE' && !isOverdue) continue;

                      // Single payment pseudo installment
                      final dummyInst = ClinicInstallment(
                        id: tx.id,
                        transactionId: tx.id,
                        number: 1,
                        totalNumber: 1,
                        amountCents: tx.totalAmountCents,
                        dueDate: tx.dueDate,
                        paidAt: tx.paidAt,
                        status: tx.status,
                      );

                      flatItems.add(_FlatInstallmentItem(
                        transaction: tx,
                        installment: dummyInst,
                        isOverdue: isOverdue,
                      ));
                    }
                  }

                  if (flatItems.isEmpty) {
                    return const Center(
                      child: Text('Nenhuma parcela encontrada para o filtro selecionado.'),
                    );
                  }

                  return ListView.builder(
                    itemCount: flatItems.length,
                    itemBuilder: (context, index) {
                      final item = flatItems[index];
                      final tx = item.transaction;
                      final inst = item.installment;
                      final isIncome = tx.type == 'income';
                      final isPaid = inst.status == 'paid';

                      return Card(
                        margin: const EdgeInsets.only(bottom: 12),
                        child: ListTile(
                          contentPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
                          leading: CircleAvatar(
                            backgroundColor: isPaid
                                ? Colors.green.withValues(alpha: 0.2)
                                : item.isOverdue
                                    ? Colors.red.withValues(alpha: 0.2)
                                    : Colors.blue.withValues(alpha: 0.2),
                            child: Icon(
                              isPaid
                                  ? LucideIcons.check
                                  : item.isOverdue
                                      ? LucideIcons.alertTriangle
                                      : isIncome
                                          ? LucideIcons.arrowUpRight
                                          : LucideIcons.arrowDownRight,
                              color: isPaid
                                  ? Colors.green
                                  : item.isOverdue
                                      ? Colors.red
                                      : isIncome
                                          ? Colors.blue
                                          : Colors.orange,
                            ),
                          ),
                          title: Row(
                            children: [
                              Expanded(
                                child: Text(
                                  tx.patientName != null && tx.patientName!.isNotEmpty
                                      ? '${tx.patientName} — ${tx.description}'
                                      : tx.description,
                                  style: const TextStyle(fontWeight: FontWeight.bold),
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ),
                              _buildInstallmentStatusBadge(inst.status, item.isOverdue),
                            ],
                          ),
                          subtitle: Padding(
                            padding: const EdgeInsets.only(top: 4.0),
                            child: Row(
                              children: [
                                Text('Parcela ${inst.number}/${inst.totalNumber}'),
                                const SizedBox(width: 12),
                                Text('Vencimento: ${formatDate.format(inst.dueDate)}'),
                                if (inst.paidAt != null) ...[
                                  const SizedBox(width: 12),
                                  Text(
                                    'Pago em: ${formatDate.format(inst.paidAt!)}',
                                    style: const TextStyle(color: Colors.green, fontWeight: FontWeight.bold),
                                  ),
                                ],
                              ],
                            ),
                          ),
                          trailing: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Text(
                                formatCurrency.format(inst.amount),
                                style: TextStyle(
                                  fontWeight: FontWeight.bold,
                                  fontSize: 16,
                                  color: isPaid ? Colors.green : (item.isOverdue ? Colors.red : theme.colorScheme.primary),
                                  decoration: isPaid ? TextDecoration.lineThrough : null,
                                ),
                              ),
                              const SizedBox(width: 16),
                              if (!isPaid)
                                ElevatedButton.icon(
                                  style: ElevatedButton.styleFrom(
                                    backgroundColor: Colors.green,
                                    foregroundColor: Colors.white,
                                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                                  ),
                                  onPressed: () => _showPaymentRegistrationModal(context, inst),
                                  icon: const Icon(LucideIcons.dollarSign, size: 16),
                                  label: const Text('Registrar Pagamento'),
                                )
                              else
                                IconButton(
                                  icon: const Icon(LucideIcons.receipt, color: Colors.blue),
                                  tooltip: 'Gerar Recibo PDF',
                                  onPressed: () async {
                                    await ReceiptGenerator.generateReceipt(
                                      transaction: tx,
                                      installment: inst,
                                    );
                                  },
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

  Widget _buildKpiCard(
    BuildContext context, {
    required String title,
    required String value,
    required IconData icon,
    required Color color,
  }) {
    final theme = Theme.of(context);
    return Expanded(
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(20.0),
          child: Row(
            children: [
              CircleAvatar(
                radius: 24,
                backgroundColor: color.withValues(alpha: 0.15),
                child: Icon(icon, color: color, size: 24),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: theme.textTheme.titleMedium?.copyWith(
                        color: theme.textTheme.bodySmall?.color,
                        fontSize: 13,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      value,
                      style: theme.textTheme.headlineSmall?.copyWith(
                        fontWeight: FontWeight.bold,
                        color: color,
                        fontSize: 22,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildInstallmentStatusBadge(String status, bool isOverdue) {
    if (status == 'paid') {
      return Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
        decoration: BoxDecoration(
          color: Colors.green.withValues(alpha: 0.15),
          borderRadius: BorderRadius.circular(12),
        ),
        child: const Text('PAGO', style: TextStyle(color: Colors.green, fontSize: 11, fontWeight: FontWeight.bold)),
      );
    }
    if (isOverdue) {
      return Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
        decoration: BoxDecoration(
          color: Colors.red.withValues(alpha: 0.15),
          borderRadius: BorderRadius.circular(12),
        ),
        child: const Text('VENCIDO', style: TextStyle(color: Colors.red, fontSize: 11, fontWeight: FontWeight.bold)),
      );
    }
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: Colors.blue.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(12),
      ),
      child: const Text('PENDENTE', style: TextStyle(color: Colors.blue, fontSize: 11, fontWeight: FontWeight.bold)),
    );
  }

  void _openTransactionDialog(BuildContext context) {
    showDialog(
      context: context,
      builder: (context) => const TransactionFormDialog(),
    ).then((_) {
      ref.invalidate(financialTransactionsProvider);
      ref.invalidate(cashFlowSummaryProvider);
      ref.invalidate(dashboardStatsProvider);
    });
  }

  void _showPaymentRegistrationModal(BuildContext context, ClinicInstallment installment) {
    showDialog(
      context: context,
      builder: (context) => _PaymentRegistrationModal(installment: installment),
    ).then((paid) {
      if (paid == true) {
        ref.invalidate(financialTransactionsProvider);
        ref.invalidate(cashFlowSummaryProvider);
        ref.invalidate(dashboardStatsProvider);
      }
    });
  }
}

class _FlatInstallmentItem {
  final ClinicTransaction transaction;
  final ClinicInstallment installment;
  final bool isOverdue;

  _FlatInstallmentItem({
    required this.transaction,
    required this.installment,
    required this.isOverdue,
  });
}

class _PaymentRegistrationModal extends ConsumerStatefulWidget {
  final ClinicInstallment installment;

  const _PaymentRegistrationModal({required this.installment});

  @override
  ConsumerState<_PaymentRegistrationModal> createState() => _PaymentRegistrationModalState();
}

class _PaymentRegistrationModalState extends ConsumerState<_PaymentRegistrationModal> {
  String _selectedMethod = 'PIX';
  bool _isProcessing = false;

  final List<String> _methods = [
    'PIX',
    'Dinheiro',
    'Cartão de Crédito',
    'Cartão de Débito',
    'Transferência / TED',
  ];

  @override
  Widget build(BuildContext context) {
    final currencyFormatter = NumberFormat.currency(locale: 'pt_BR', symbol: 'R\$');

    return AlertDialog(
      title: const Text('Registrar Pagamento'),
      content: SizedBox(
        width: 400,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Parcela ${widget.installment.number} de ${widget.installment.totalNumber}'),
            const SizedBox(height: 4),
            Text(
              'Valor a Receber: ${currencyFormatter.format(widget.installment.amount)}',
              style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
            ),
            const Divider(height: 24),
            DropdownButtonFormField<String>(
              initialValue: _selectedMethod,
              decoration: const InputDecoration(
                labelText: 'Forma de Recebimento *',
                border: OutlineInputBorder(),
              ),
              items: _methods.map((m) => DropdownMenuItem(value: m, child: Text(m))).toList(),
              onChanged: (val) {
                if (val != null) setState(() => _selectedMethod = val);
              },
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _isProcessing ? null : () => Navigator.of(context).pop(false),
          child: const Text('Cancelar'),
        ),
        ElevatedButton.icon(
          style: ElevatedButton.styleFrom(backgroundColor: Colors.green, foregroundColor: Colors.white),
          onPressed: _isProcessing ? null : _confirmPayment,
          icon: const Icon(LucideIcons.check, size: 18),
          label: _isProcessing
              ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
              : const Text('Confirmar Pagamento'),
        ),
      ],
    );
  }

  Future<void> _confirmPayment() async {
    setState(() => _isProcessing = true);
    try {
      final repo = ref.read(financialRepositoryProvider);
      await repo.payInstallment(widget.installment.id, paymentMethod: _selectedMethod);

      if (mounted) {
        Navigator.of(context).pop(true);
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Pagamento registrado com sucesso!')),
        );
      }
    } catch (e) {
      if (mounted) {
        setState(() => _isProcessing = false);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Erro ao registrar pagamento: $e')),
        );
      }
    }
  }
}
