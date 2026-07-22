import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:fl_chart/fl_chart.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:frontend_flutter/shared/widgets/require_role.dart';
import 'package:frontend_flutter/features/dashboard/providers/dashboard_provider.dart';
import 'package:frontend_flutter/features/settings/data/settings_provider.dart';
import 'package:intl/intl.dart';

class DashboardScreen extends ConsumerWidget {
  const DashboardScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final statsAsync = ref.watch(dashboardStatsProvider);
    final clinicAsync = ref.watch(myClinicProvider);
    
    final String currentPlan = clinicAsync.maybeWhen(
      data: (c) => c.plan.toLowerCase(),
      orElse: () => 'basic',
    );
    final bool showFinancial = currentPlan == 'premium';

    final theme = Theme.of(context);
    return Scaffold(

      body: SingleChildScrollView(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // KPIs Row (Apenas Admin pode ver)
            RequireRole(
              allowedRoles: const ['admin', 'owner'],
              fallback: Padding(
                padding: EdgeInsets.only(bottom: 24.0),
                child: Text(
                  'Bem-vindo ao DentalCRM!\nAcesso Rápido disponível abaixo.',
                  style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold, color: theme.textTheme.titleLarge?.color),
                ),
              ),
              child: statsAsync.when(
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (e, _) => Center(child: Text('Erro ao carregar dashboard: $e')),
                data: (stats) {
                  final formatCurrency = NumberFormat.currency(locale: 'pt_BR', symbol: 'R\$');
                  final monthlyRev = stats['monthlyRevenue'] ?? 0.0;
                  
                  return Column(
                    children: [
                      Row(
                        children: [
                          Expanded(child: _buildKpiCard('Total de Pacientes', '${stats['totalPatients'] ?? 0}', '+ ${stats['newPatientsThisMonth'] ?? 0} novos este mês', LucideIcons.users, Colors.blue)),
                          const SizedBox(width: 16),
                          Expanded(child: _buildKpiCard('Agendamentos Hoje', '${stats['appointmentsToday'] ?? 0}', 'geral', LucideIcons.calendarClock, Colors.orange)),
                          const SizedBox(width: 16),
                          Expanded(child: _buildKpiCard('Taxa de Retorno', stats['returnRate'] ?? '0%', 'retorno do mês', LucideIcons.trendingUp, Colors.green)),
                        ],
                      ),
                      const SizedBox(height: 16),
                      Row(
                        children: [
                          if (showFinancial) ...[
                            Expanded(child: _buildKpiCard('Faturamento Mensal', formatCurrency.format(monthlyRev), 'recebido no mês atual', LucideIcons.wallet, Colors.teal)),
                            const SizedBox(width: 16),
                          ],
                          Expanded(child: _buildKpiCard('Taxa de Faltas/Cancel.', stats['cancellationRate'] ?? '0%', 'do mês atual', LucideIcons.userX, Colors.red)),
                          const SizedBox(width: 16),
                          Expanded(child: Container()), // Espaço vazio para manter o layout se necessário
                          if (!showFinancial) ...[
                            const SizedBox(width: 16),
                            Expanded(child: Container()),
                          ],
                        ],
                      ),
                      const SizedBox(height: 32),
                    ],
                  );
                },
              ),
            ),
            
            // Charts Row
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // Main Chart (Apenas Admin)
                RequireRole(
                  allowedRoles: const ['admin', 'owner'],
                  child: Expanded(
                    flex: 2,
                    child: Card(
                      elevation: 0,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(12),
                        side: BorderSide(color: theme.dividerTheme.color ?? Colors.grey.shade200),
                      ),
                      child: Padding(
                        padding: const EdgeInsets.all(24.0),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            const Text('Fluxo de Consultas (Últimos 7 dias)', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                            const SizedBox(height: 24),
                            SizedBox(
                              height: 300,
                              child: BarChart(
                                BarChartData(
                                  alignment: BarChartAlignment.spaceAround,
                                  maxY: 30,
                                  barTouchData: BarTouchData(enabled: true),
                                  titlesData: FlTitlesData(
                                    show: true,
                                    bottomTitles: AxisTitles(
                                      sideTitles: SideTitles(
                                        showTitles: true,
                                        getTitlesWidget: (double value, TitleMeta meta) {
                                          const style = TextStyle(color: Colors.grey, fontWeight: FontWeight.bold, fontSize: 12);
                                          String text;
                                          switch (value.toInt()) {
                                            case 0: text = 'Seg'; break;
                                            case 1: text = 'Ter'; break;
                                            case 2: text = 'Qua'; break;
                                            case 3: text = 'Qui'; break;
                                            case 4: text = 'Sex'; break;
                                            case 5: text = 'Sáb'; break;
                                            case 6: text = 'Dom'; break;
                                            default: text = ''; break;
                                          }
                                          return SideTitleWidget(
                                            meta: meta,
                                            child: Text(text, style: style),
                                          );
                                        },
                                      ),
                                    ),
                                    leftTitles: AxisTitles(
                                      sideTitles: SideTitles(
                                        showTitles: true,
                                        reservedSize: 30,
                                        getTitlesWidget: (value, meta) => Text(value.toInt().toString(), style: const TextStyle(color: Colors.grey, fontSize: 12)),
                                      ),
                                    ),
                                    topTitles: const AxisTitles(sideTitles: SideTitles(showTitles: false)),
                                    rightTitles: const AxisTitles(sideTitles: SideTitles(showTitles: false)),
                                  ),
                                  gridData: FlGridData(
                                    show: true,
                                    drawVerticalLine: false,
                                    getDrawingHorizontalLine: (value) => FlLine(color: Colors.grey.shade200, strokeWidth: 1),
                                  ),
                                  borderData: FlBorderData(show: false),
                                  barGroups: statsAsync.maybeWhen(
                                    data: (stats) {
                                      final List<dynamic> weekly = stats['weeklyData'] ?? [];
                                      return List.generate(
                                        weekly.length > 7 ? 7 : weekly.length, 
                                        (i) => _makeGroupData(i, (weekly[i] as num).toDouble(), 0)
                                      );
                                    },
                                    orElse: () => [],
                                  ),
                                ),
                              ),
                            ),
                            if (showFinancial) ...[
                              const SizedBox(height: 24),
                              const Text('Faturamento (Últimos 7 dias)', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                              const SizedBox(height: 24),
                              SizedBox(
                                height: 250,
                                child: LineChart(
                                  LineChartData(
                                    gridData: FlGridData(show: true, drawVerticalLine: false),
                                    titlesData: FlTitlesData(
                                      show: true,
                                      rightTitles: const AxisTitles(sideTitles: SideTitles(showTitles: false)),
                                      topTitles: const AxisTitles(sideTitles: SideTitles(showTitles: false)),
                                      bottomTitles: AxisTitles(
                                        sideTitles: SideTitles(
                                          showTitles: true,
                                          getTitlesWidget: (double value, TitleMeta meta) {
                                            const style = TextStyle(color: Colors.grey, fontWeight: FontWeight.bold, fontSize: 12);
                                            String text;
                                            switch (value.toInt()) {
                                              case 0: text = 'Seg'; break;
                                              case 1: text = 'Ter'; break;
                                              case 2: text = 'Qua'; break;
                                              case 3: text = 'Qui'; break;
                                              case 4: text = 'Sex'; break;
                                              case 5: text = 'Sáb'; break;
                                              case 6: text = 'Dom'; break;
                                              default: text = ''; break;
                                            }
                                            return SideTitleWidget(meta: meta, child: Text(text, style: style));
                                          },
                                        ),
                                      ),
                                      leftTitles: AxisTitles(
                                        sideTitles: SideTitles(
                                          showTitles: true,
                                          reservedSize: 45,
                                          getTitlesWidget: (value, meta) => Text(NumberFormat.compactCurrency(symbol: 'R\$').format(value), style: const TextStyle(color: Colors.grey, fontSize: 10)),
                                        ),
                                      ),
                                    ),
                                    borderData: FlBorderData(show: false),
                                    lineBarsData: [
                                      LineChartBarData(
                                        spots: statsAsync.maybeWhen(
                                          data: (stats) {
                                            final List<dynamic> rev = stats['revenueData'] ?? [];
                                            return List.generate(
                                              rev.length > 7 ? 7 : rev.length, 
                                              (i) => FlSpot(i.toDouble(), (rev[i] as num).toDouble())
                                            );
                                          },
                                          orElse: () => [],
                                        ),
                                        isCurved: true,
                                        color: Colors.teal,
                                        barWidth: 4,
                                        isStrokeCapRound: true,
                                        dotData: const FlDotData(show: true),
                                        belowBarData: BarAreaData(
                                          show: true,
                                          color: Colors.teal.withValues(alpha: 0.2),
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                              ),
                            ],
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
                
                RequireRole(
                  allowedRoles: const ['admin', 'owner'],
                  child: const SizedBox(width: 24),
                ),
                
                // Quick Actions
                Expanded(
                  flex: 1,
                  child: Column(
                    children: [
                      Card(
                        elevation: 0,
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(12),
                          side: BorderSide(color: theme.dividerTheme.color ?? Colors.grey.shade200),
                        ),
                        child: Padding(
                          padding: const EdgeInsets.all(24.0),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: [
                              const Text('Ações Rápidas', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                              const SizedBox(height: 16),
                              ElevatedButton.icon(
                                onPressed: () => context.go('/schedule'),
                                icon: const Icon(Icons.add),
                                label: const Text('Novo Agendamento'),
                                style: ElevatedButton.styleFrom(
                                  padding: const EdgeInsets.all(16),
                                  alignment: Alignment.centerLeft,
                                ),
                              ),
                              const SizedBox(height: 12),
                              OutlinedButton.icon(
                                onPressed: () => context.go('/patients'),
                                icon: const Icon(Icons.person_add),
                                label: const Text('Cadastrar Paciente'),
                                style: OutlinedButton.styleFrom(
                                  padding: const EdgeInsets.all(16),
                                  alignment: Alignment.centerLeft,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                      const SizedBox(height: 24),
                      RequireRole(
                        allowedRoles: const ['admin', 'owner'],
                        child: Card(
                          elevation: 0,
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(12),
                            side: BorderSide(color: theme.dividerTheme.color ?? Colors.grey.shade200),
                          ),
                          child: Padding(
                            padding: const EdgeInsets.all(24.0),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.stretch,
                              children: [
                                const Text('Status de Consultas', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                                const SizedBox(height: 24),
                                SizedBox(
                                  height: 200,
                                  child: statsAsync.maybeWhen(
                                    data: (stats) {
                                      final dist = (stats['statusDistribution'] as Map<String, dynamic>?) ?? {};
                                      if (dist.isEmpty) return const Center(child: Text('Sem dados'));
                                      
                                      final total = dist.values.fold(0, (sum, v) => sum + ((v as num?)?.toInt() ?? 0));
                                      if (total == 0) return const Center(child: Text('Sem dados'));

                                      List<PieChartSectionData> sections = [];
                                      dist.forEach((key, value) {
                                        final count = (value as num).toInt();
                                        if (count > 0) {
                                          Color c;
                                          switch(key) {
                                            case 'completed': c = Colors.green; break;
                                            case 'cancelled': c = Colors.red; break;
                                            case 'no_show': c = Colors.orange; break;
                                            default: c = Colors.blue; break; // scheduled
                                          }
                                          sections.add(PieChartSectionData(
                                            color: c,
                                            value: count.toDouble(),
                                            title: '${((count / total) * 100).toStringAsFixed(1)}%',
                                            radius: 50,
                                            titleStyle: const TextStyle(fontSize: 12, fontWeight: FontWeight.bold, color: Colors.white),
                                          ));
                                        }
                                      });
                                      
                                      return PieChart(
                                        PieChartData(
                                          sections: sections,
                                          centerSpaceRadius: 40,
                                          sectionsSpace: 2,
                                        ),
                                      );
                                    },
                                    orElse: () => const Center(child: CircularProgressIndicator()),
                                  ),
                                ),
                                const SizedBox(height: 16),
                                Wrap(
                                  spacing: 8,
                                  runSpacing: 4,
                                  children: [
                                    _buildLegendItem('Concluído', Colors.green),
                                    _buildLegendItem('Agendado', Colors.blue),
                                    _buildLegendItem('Falta', Colors.orange),
                                    _buildLegendItem('Cancelado', Colors.red),
                                  ],
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildKpiCard(String title, String value, String subtitle, IconData icon, MaterialColor color) {
    return Builder(
      builder: (context) {
        final theme = Theme.of(context);
        final isDark = theme.brightness == Brightness.dark;
        
        return Card(
          elevation: 0,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
            side: BorderSide(color: theme.dividerTheme.color ?? Colors.grey.shade200),
          ),
      child: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(title, style: TextStyle(color: theme.textTheme.bodyMedium?.color, fontWeight: FontWeight.w600)),
                Container(
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: isDark ? color.shade900.withValues(alpha: 0.3) : color.shade50, 
                    borderRadius: BorderRadius.circular(8)
                  ),
                  child: Icon(icon, color: isDark ? color.shade200 : color.shade600, size: 20),
                ),
              ],
            ),
            const SizedBox(height: 16),
            Text(value, style: TextStyle(fontSize: 32, fontWeight: FontWeight.bold, color: theme.textTheme.bodyLarge?.color)),
            const SizedBox(height: 8),
            Text(subtitle, style: TextStyle(color: isDark ? color.shade300 : color.shade700, fontWeight: FontWeight.w500, fontSize: 13)),
          ],
        ),
      ),
    );
    });
  }

  BarChartGroupData _makeGroupData(int x, double normais, double urgencias) {
    return BarChartGroupData(
      x: x,
      barRods: [
        BarChartRodData(
          toY: normais,
          color: Colors.blue.shade400,
          width: 16,
          borderRadius: const BorderRadius.only(topLeft: Radius.circular(4), topRight: Radius.circular(4)),
        ),
        BarChartRodData(
          toY: urgencias,
          color: Colors.orange.shade400,
          width: 16,
          borderRadius: const BorderRadius.only(topLeft: Radius.circular(4), topRight: Radius.circular(4)),
        ),
      ],
    );
  }

  Widget _buildLegendItem(String label, Color color) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(width: 12, height: 12, decoration: BoxDecoration(color: color, shape: BoxShape.circle)),
        const SizedBox(width: 4),
        Text(label, style: const TextStyle(fontSize: 12)),
      ],
    );
  }
}
