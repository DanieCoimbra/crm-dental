import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/financial/providers/financial_provider.dart';
import 'package:frontend_flutter/features/financial/data/financial_repository.dart';
import 'package:frontend_flutter/features/financial/presentation/widgets/transaction_form_dialog.dart';
import 'package:frontend_flutter/features/financial/utils/receipt_generator.dart';
import 'package:frontend_flutter/features/dashboard/providers/dashboard_provider.dart';
import 'package:intl/intl.dart';

class FinancialDashboardScreen extends ConsumerWidget {
  const FinancialDashboardScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final transactionsAsync = ref.watch(financialTransactionsProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Financeiro'),
        scrolledUnderElevation: 0,
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () {
          showDialog(
            context: context,
            builder: (ctx) => const TransactionFormDialog(),
          );
        },
        icon: const Icon(Icons.add),
        label: const Text('Novo Lançamento'),
      ),
      body: Padding(
        padding: const EdgeInsets.all(24.0),
        child: transactionsAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('Erro: $e')),
        data: (transactions) {
          double totalIncome = 0;
          double totalExpense = 0;
          
          for (var tx in transactions) {
            if (tx.type == 'income') {
              totalIncome += tx.amount;
            } else {
              totalExpense += tx.amount;
            }
          }

          final formatCurrency = NumberFormat.currency(locale: 'pt_BR', symbol: 'R\$');

          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  _buildSummaryCard(context, 'Receitas', formatCurrency.format(totalIncome), Colors.green),
                  const SizedBox(width: 16),
                  _buildSummaryCard(context, 'Despesas', formatCurrency.format(totalExpense), Colors.red),
                  const SizedBox(width: 16),
                  _buildSummaryCard(context, 'Saldo', formatCurrency.format(totalIncome - totalExpense), (totalIncome - totalExpense) >= 0 ? Colors.blue : Colors.red),
                ],
              ),
              const SizedBox(height: 24),
              Text('Transações Recentes', style: theme.textTheme.titleLarge),
              const SizedBox(height: 16),
              Expanded(
                child: Card(
                  child: ListView.builder(
                    itemCount: transactions.length,
                    itemBuilder: (context, index) {
                      final tx = transactions[index];
                      final isIncome = tx.type == 'income';
                      return ExpansionTile(
                        leading: CircleAvatar(
                          backgroundColor: isIncome ? Colors.green.withValues(alpha: 0.2) : Colors.red.withValues(alpha: 0.2),
                          child: Icon(
                            isIncome ? Icons.arrow_upward : Icons.arrow_downward,
                            color: isIncome ? Colors.green : Colors.red,
                          ),
                        ),
                        title: Text(tx.description),
                        subtitle: Text('${tx.category} • ${DateFormat('dd/MM/yyyy').format(tx.dueDate)}'),
                        trailing: Text(
                          formatCurrency.format(tx.amount),
                          style: TextStyle(
                            fontWeight: FontWeight.bold,
                            color: isIncome ? Colors.green : Colors.red,
                            fontSize: 16,
                          ),
                        ),
                        children: tx.installments.map((inst) {
                          final isPaid = inst.status == 'paid';
                          return ListTile(
                            contentPadding: const EdgeInsets.symmetric(horizontal: 40),
                            title: Text('Parcela ${inst.number} de ${inst.totalNumber}'),
                            subtitle: Text('Vencimento: ${DateFormat('dd/MM/yyyy').format(inst.dueDate)}'),
                            trailing: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Text(
                                  formatCurrency.format(inst.amount),
                                  style: TextStyle(
                                    fontWeight: FontWeight.bold,
                                    decoration: isPaid ? TextDecoration.lineThrough : null,
                                  ),
                                ),
                                const SizedBox(width: 8),
                                if (!isPaid)
                                  TextButton(
                                    onPressed: () async {
                                      await ref.read(financialRepositoryProvider).payInstallment(inst.id);
                                      ref.invalidate(financialTransactionsProvider);
                                      ref.invalidate(dashboardStatsProvider);
                                    },
                                    child: const Text('Dar Baixa'),
                                  )
                                else
                                  const Icon(Icons.check_circle, color: Colors.green),
                                const SizedBox(width: 8),
                                IconButton(
                                  icon: const Icon(Icons.receipt_long),
                                  tooltip: 'Gerar Recibo',
                                  onPressed: () async {
                                    await ReceiptGenerator.generateReceipt(
                                      transaction: tx,
                                      installment: inst,
                                    );
                                  },
                                ),
                              ],
                            ),
                          );
                        }).toList(),
                      );
                    },
                  ),
                ),
              ),
            ],
          );
        },
      ),
      ),
    );
  }

  Widget _buildSummaryCard(BuildContext context, String title, String value, Color color) {
    final theme = Theme.of(context);
    return Expanded(
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(20.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: theme.textTheme.titleMedium?.copyWith(color: theme.textTheme.bodySmall?.color)),
              const SizedBox(height: 8),
              Text(
                value,
                style: theme.textTheme.headlineSmall?.copyWith(
                  fontWeight: FontWeight.bold,
                  color: color,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
