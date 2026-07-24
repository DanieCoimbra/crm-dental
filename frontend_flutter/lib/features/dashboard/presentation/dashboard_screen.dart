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
    final gridColor = theme.dividerTheme.color ?? const Color(0xFF334155);

    return Scaffold(
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // KPIs Row (Apenas Admin/Owner pode ver)
            RequireRole(
              allowedRoles: const ['admin', 'owner'],
              fallback: Padding(
                padding: const EdgeInsets.only(bottom: 24.0),
                child: Text(
                  'Bem-vindo ao DentalCRM!\nAcesso Rápido disponível abaixo.',
                  style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold, color: theme.textTheme.titleLarge?.color),
                ),
              ),
              child: statsAsync.when(
                loading: () => const Center(child: Padding(padding: EdgeInsets.all(32), child: CircularProgressIndicator())),
                error: (e, _) => Center(child: Text('Erro ao carregar dashboard: $e')),
                data: (stats) {
                  final formatCurrency = NumberFormat.currency(locale: 'pt_BR', symbol: 'R\$');
                  final monthlyRev = stats['monthlyRevenue'] ?? 0.0;
                  
                  return Column(
                    children: [
                      Row(
                        children: [
                          Expanded(
                            child: _buildKpiCard(
                              context,
                              'Total de Pacientes',
                              '${stats['totalPatients'] ?? 0}',
                              '+ ${stats['newPatientsThisMonth'] ?? 0} novos este mês',
                              LucideIcons.users,
                              const Color(0xFF2563EB),
                              growthBadge: '+12%',
                            ),
                          ),
                          const SizedBox(width: 16),
                          Expanded(
                            child: _buildKpiCard(
                              context,
                              'Agendamentos Hoje',
                              '${stats['appointmentsToday'] ?? 0}',
                              'atendimentos previstos hoje',
                              LucideIcons.calendarClock,
                              const Color(0xFFF59E0B),
                              growthBadge: '+5%',
                            ),
                          ),
                          const SizedBox(width: 16),
                          Expanded(
                            child: _buildKpiCard(
                              context,
                              'Taxa de Retorno',
                              stats['returnRate'] ?? '0%',
                              'retorno do mês',
                              LucideIcons.trendingUp,
                              const Color(0xFF10B981),
                              growthBadge: '+8%',
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 16),
                      Row(
                        children: [
                          if (showFinancial) ...[
                            Expanded(
                              child: _buildKpiCard(
                                context,
                                'Faturamento Mensal',
                                formatCurrency.format(monthlyRev),
                                'recebido no mês atual',
                                LucideIcons.wallet,
                                const Color(0xFF10B981),
                                growthBadge: '+15%',
                              ),
                            ),
                            const SizedBox(width: 16),
                          ],
                          Expanded(
                            child: _buildKpiCard(
                              context,
                              'Taxa de Faltas/Cancel.',
                              stats['cancellationRate'] ?? '0%',
                              'do mês atual',
                              LucideIcons.userX,
                              const Color(0xFFEF4444),
                              growthBadge: '-2%',
                              isPositiveGrowth: false,
                            ),
                          ),
                          const SizedBox(width: 16),
                          Expanded(child: Container()),
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
                // Main Chart (Apenas Admin/Owner)
                RequireRole(
                  allowedRoles: const ['admin', 'owner'],
                  child: Expanded(
                    flex: 2,
                    child: Card(
                      elevation: 0,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(16),
                        side: BorderSide(color: theme.dividerTheme.color ?? theme.colorScheme.outline, width: 1),
                      ),
                      child: Padding(
                        padding: const EdgeInsets.all(24.0),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              mainAxisAlignment: MainAxisAlignment.spaceBetween,
                              children: [
                                Text(
                                  'Fluxo de Consultas (Últimos 7 dias)',
                                  style: TextStyle(
                                    fontSize: 18,
                                    fontWeight: FontWeight.bold,
                                    color: theme.textTheme.titleLarge?.color,
                                  ),
                                ),
                                Container(
                                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                                  decoration: BoxDecoration(
                                    color: const Color(0xFF2563EB).withValues(alpha: 0.1),
                                    borderRadius: BorderRadius.circular(8),
                                  ),
                                  child: const Text(
                                    'Semanal',
                                    style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: Color(0xFF2563EB)),
                                  ),
                                ),
                              ],
                            ),
                            const SizedBox(height: 24),
                            SizedBox(
                              height: 280,
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
                                          final style = TextStyle(
                                            color: theme.textTheme.bodySmall?.color ?? theme.colorScheme.onSurfaceVariant,
                                            fontWeight: FontWeight.bold,
                                            fontSize: 12,
                                          );
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
                                        getTitlesWidget: (value, meta) => Text(
                                          value.toInt().toString(),
                                          style: TextStyle(
                                            color: theme.textTheme.bodySmall?.color ?? theme.colorScheme.onSurfaceVariant,
                                            fontSize: 12,
                                          ),
                                        ),
                                      ),
                                    ),
                                    topTitles: const AxisTitles(sideTitles: SideTitles(showTitles: false)),
                                    rightTitles: const AxisTitles(sideTitles: SideTitles(showTitles: false)),
                                  ),
                                  gridData: FlGridData(
                                    show: true,
                                    drawVerticalLine: false,
                                    getDrawingHorizontalLine: (value) => FlLine(
                                      color: gridColor.withValues(alpha: 0.5),
                                      strokeWidth: 1,
                                    ),
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
                              const SizedBox(height: 32),
                              const Divider(),
                              const SizedBox(height: 24),
                              Row(
                                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                                children: [
                                  Text(
                                    'Fluxo de Caixa (Últimos 7 dias)',
                                    style: TextStyle(
                                      fontSize: 18,
                                      fontWeight: FontWeight.bold,
                                      color: theme.textTheme.titleLarge?.color,
                                    ),
                                  ),
                                  Container(
                                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                                    decoration: BoxDecoration(
                                      color: const Color(0xFF10B981).withValues(alpha: 0.1),
                                      borderRadius: BorderRadius.circular(8),
                                    ),
                                    child: const Text(
                                      'Receita R\$',
                                      style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: Color(0xFF10B981)),
                                    ),
                                  ),
                                ],
                              ),
                              const SizedBox(height: 24),
                              SizedBox(
                                height: 260,
                                child: LineChart(
                                  LineChartData(
                                    gridData: FlGridData(
                                      show: true,
                                      drawVerticalLine: false,
                                      getDrawingHorizontalLine: (value) => FlLine(
                                        color: gridColor.withValues(alpha: 0.5),
                                        strokeWidth: 1,
                                      ),
                                    ),
                                    titlesData: FlTitlesData(
                                      show: true,
                                      rightTitles: const AxisTitles(sideTitles: SideTitles(showTitles: false)),
                                      topTitles: const AxisTitles(sideTitles: SideTitles(showTitles: false)),
                                      bottomTitles: AxisTitles(
                                        sideTitles: SideTitles(
                                          showTitles: true,
                                          getTitlesWidget: (double value, TitleMeta meta) {
                                            final style = TextStyle(
                                              color: theme.textTheme.bodySmall?.color ?? theme.colorScheme.onSurfaceVariant,
                                              fontWeight: FontWeight.bold,
                                              fontSize: 12,
                                            );
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
                                          reservedSize: 50,
                                          getTitlesWidget: (value, meta) => Text(
                                            NumberFormat.compactCurrency(symbol: 'R\$').format(value),
                                            style: TextStyle(
                                              color: theme.textTheme.bodySmall?.color ?? theme.colorScheme.onSurfaceVariant,
                                              fontSize: 10,
                                            ),
                                          ),
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
                                        curveSmoothness: 0.35,
                                        color: const Color(0xFF2563EB),
                                        barWidth: 3,
                                        isStrokeCapRound: true,
                                        dotData: FlDotData(
                                          show: true,
                                          getDotPainter: (spot, percent, barData, index) => FlDotCirclePainter(
                                            radius: 4,
                                            color: const Color(0xFF2563EB),
                                            strokeWidth: 2,
                                            strokeColor: Colors.white,
                                          ),
                                        ),
                                        belowBarData: BarAreaData(
                                          show: true,
                                          gradient: LinearGradient(
                                            colors: [
                                              const Color(0xFF2563EB).withValues(alpha: 0.35),
                                              const Color(0xFF10B981).withValues(alpha: 0.05),
                                            ],
                                            begin: Alignment.topCenter,
                                            end: Alignment.bottomCenter,
                                          ),
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
                
                // Quick Actions & Status Distribution
                Expanded(
                  flex: 1,
                  child: Column(
                    children: [
                      Card(
                        elevation: 0,
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(16),
                          side: BorderSide(color: theme.dividerTheme.color ?? theme.colorScheme.outline, width: 1),
                        ),
                        child: Padding(
                          padding: const EdgeInsets.all(24.0),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: [
                              Text(
                                'Ações Rápidas',
                                style: TextStyle(
                                  fontSize: 18,
                                  fontWeight: FontWeight.bold,
                                  color: theme.textTheme.titleLarge?.color,
                                ),
                              ),
                              const SizedBox(height: 16),
                              ElevatedButton.icon(
                                onPressed: () => context.go('/schedule'),
                                icon: const Icon(LucideIcons.plus),
                                label: const Text('Novo Agendamento'),
                                style: ElevatedButton.styleFrom(
                                  padding: const EdgeInsets.all(16),
                                  alignment: Alignment.centerLeft,
                                ),
                              ),
                              const SizedBox(height: 12),
                              OutlinedButton.icon(
                                onPressed: () => context.go('/patients'),
                                icon: const Icon(LucideIcons.userPlus),
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
                            borderRadius: BorderRadius.circular(16),
                            side: BorderSide(color: theme.dividerTheme.color ?? theme.colorScheme.outline, width: 1),
                          ),
                          child: Padding(
                            padding: const EdgeInsets.all(24.0),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.stretch,
                              children: [
                                Text(
                                  'Status de Consultas',
                                  style: TextStyle(
                                    fontSize: 18,
                                    fontWeight: FontWeight.bold,
                                    color: theme.textTheme.titleLarge?.color,
                                  ),
                                ),
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
                                            case 'completed': c = const Color(0xFF10B981); break; // Emerald Green
                                            case 'cancelled': c = const Color(0xFFEF4444); break; // Red
                                            case 'no_show': c = const Color(0xFFF59E0B); break; // Amber/Orange
                                            default: c = const Color(0xFF2563EB); break; // Royal Blue
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
                                const SizedBox(height: 20),
                                Wrap(
                                  spacing: 12,
                                  runSpacing: 8,
                                  children: [
                                    _buildLegendItem('Concluído', const Color(0xFF10B981)),
                                    _buildLegendItem('Agendado', const Color(0xFF2563EB)),
                                    _buildLegendItem('Falta', const Color(0xFFF59E0B)),
                                    _buildLegendItem('Cancelado', const Color(0xFFEF4444)),
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

  Widget _buildKpiCard(
    BuildContext context,
    String title,
    String value,
    String subtitle,
    IconData icon,
    Color accentColor, {
    String? growthBadge,
    bool isPositiveGrowth = true,
  }) {
    final theme = Theme.of(context);
    final isDark = theme.brightness == Brightness.dark;
    
    return Card(
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: BorderSide(color: theme.dividerTheme.color ?? theme.colorScheme.outline, width: 1),
      ),
      child: Padding(
        padding: const EdgeInsets.all(20.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Expanded(
                  child: Text(
                    title,
                    style: TextStyle(
                      color: theme.textTheme.bodyMedium?.color,
                      fontWeight: FontWeight.w600,
                      fontSize: 14,
                    ),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                Container(
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: accentColor.withValues(alpha: isDark ? 0.2 : 0.1), 
                    borderRadius: BorderRadius.circular(10),
                    border: Border.all(color: accentColor.withValues(alpha: 0.3), width: 1),
                  ),
                  child: Icon(icon, color: accentColor, size: 20),
                ),
              ],
            ),
            const SizedBox(height: 16),
            Row(
              crossAxisAlignment: CrossAxisAlignment.baseline,
              textBaseline: TextBaseline.alphabetic,
              children: [
                Expanded(
                  child: Text(
                    value,
                    style: TextStyle(
                      fontSize: 28,
                      fontWeight: FontWeight.bold,
                      letterSpacing: -0.5,
                      color: theme.textTheme.bodyLarge?.color,
                    ),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                if (growthBadge != null) ...[
                  const SizedBox(width: 8),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                    decoration: BoxDecoration(
                      color: isPositiveGrowth 
                          ? const Color(0xFF10B981).withValues(alpha: 0.15)
                          : const Color(0xFFEF4444).withValues(alpha: 0.15),
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(
                        color: isPositiveGrowth
                            ? const Color(0xFF10B981).withValues(alpha: 0.3)
                            : const Color(0xFFEF4444).withValues(alpha: 0.3),
                        width: 1,
                      ),
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          isPositiveGrowth ? LucideIcons.arrowUpRight : LucideIcons.arrowDownRight,
                          size: 12,
                          color: isPositiveGrowth ? const Color(0xFF10B981) : const Color(0xFFEF4444),
                        ),
                        const SizedBox(width: 2),
                        Text(
                          growthBadge,
                          style: TextStyle(
                            fontSize: 11,
                            fontWeight: FontWeight.bold,
                            color: isPositiveGrowth ? const Color(0xFF10B981) : const Color(0xFFEF4444),
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ],
            ),
            const SizedBox(height: 8),
            Text(
              subtitle,
              style: TextStyle(
                color: theme.textTheme.bodySmall?.color ?? Colors.grey,
                fontWeight: FontWeight.w500,
                fontSize: 12,
              ),
              overflow: TextOverflow.ellipsis,
            ),
          ],
        ),
      ),
    );
  }

  BarChartGroupData _makeGroupData(int x, double normais, double urgencias) {
    return BarChartGroupData(
      x: x,
      barRods: [
        BarChartRodData(
          toY: normais,
          color: const Color(0xFF2563EB),
          width: 16,
          borderRadius: const BorderRadius.only(topLeft: Radius.circular(6), topRight: Radius.circular(6)),
        ),
        if (urgencias > 0)
          BarChartRodData(
            toY: urgencias,
            color: const Color(0xFFF59E0B),
            width: 16,
            borderRadius: const BorderRadius.only(topLeft: Radius.circular(6), topRight: Radius.circular(6)),
          ),
      ],
    );
  }

  Widget _buildLegendItem(String label, Color color) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.1),
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: color.withValues(alpha: 0.2), width: 1),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(width: 8, height: 8, decoration: BoxDecoration(color: color, shape: BoxShape.circle)),
          const SizedBox(width: 6),
          Text(label, style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: color)),
        ],
      ),
    );
  }
}

