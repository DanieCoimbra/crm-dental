import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/shared/widgets/require_role.dart';
import 'package:frontend_flutter/features/settings/data/settings_provider.dart';
import 'package:frontend_flutter/features/trash/providers/trash_provider.dart';

class Sidebar extends ConsumerWidget {
  const Sidebar({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Para o web, GoRouterState tem a URI atual
    final currentRoute = GoRouterState.of(context).uri.toString();

    final theme = Theme.of(context);
    
    final clinicAsync = ref.watch(myClinicProvider);
    final String currentPlan = clinicAsync.maybeWhen(
      data: (c) => c.plan.toLowerCase(),
      orElse: () => 'basic',
    );
    
    final bool showInventory = currentPlan == 'pro' || currentPlan == 'premium';
    final bool showFinancial = currentPlan == 'premium';

    return Container(
      width: 260,
      decoration: BoxDecoration(
        color: theme.colorScheme.surface.withValues(alpha: 0.95),
        border: Border(
          right: BorderSide(color: theme.dividerTheme.color ?? theme.colorScheme.outline, width: 1),
        ),
      ),
      child: Column(
        children: [
          _buildLogo(context),
          const SizedBox(height: 16),
          Expanded(
            child: ListView(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              children: [
                _SidebarItem(
                  icon: LucideIcons.layoutDashboard,
                  label: 'Dashboard',
                  isSelected: currentRoute == '/dashboard',
                  onTap: () => context.go('/dashboard'),
                ),
                _SidebarItem(
                  icon: LucideIcons.calendar,
                  label: 'Agenda',
                  isSelected: currentRoute.startsWith('/schedule'),
                  onTap: () => context.go('/schedule'),
                ),
                _SidebarItem(
                  icon: LucideIcons.users,
                  label: 'Pacientes',
                  isSelected: currentRoute.startsWith('/patients'),
                  onTap: () => context.go('/patients'),
                ),
                if (showFinancial)
                  _SidebarItem(
                    icon: LucideIcons.dollarSign,
                    label: 'Financeiro',
                    isSelected: currentRoute.startsWith('/financial'),
                    onTap: () => context.go('/financial'),
                  ),
                if (showInventory)
                  _SidebarItem(
                    icon: LucideIcons.package,
                    label: 'Estoque',
                    isSelected: currentRoute.startsWith('/inventory'),
                    onTap: () => context.go('/inventory'),
                  ),
                RequireRole(
                  allowedRoles: const ['admin', 'manager', 'owner'],
                  child: _SidebarItem(
                    icon: LucideIcons.trash2,
                    label: 'Lixeira (LGPD)',
                    isSelected: currentRoute.startsWith('/trash'),
                    onTap: () => context.go('/trash'),
                  ),
                ),
                RequireRole(
                  allowedRoles: const ['admin', 'manager', 'owner'],
                  child: _SidebarItem(
                    icon: LucideIcons.megaphone,
                    label: 'Marketing (Cupons)',
                    isSelected: currentRoute.startsWith('/marketing'),
                    onTap: () => context.go('/marketing'),
                  ),
                ),
                RequireRole(
                  allowedRoles: const ['admin', 'manager', 'owner'],
                  child: _SidebarItem(
                    icon: LucideIcons.settings,
                    label: 'Configurações',
                    isSelected: currentRoute.startsWith('/settings'),
                    onTap: () => context.go('/settings'),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          _buildTrashBanner(ref),
        ],
      ),
    );
  }

  Widget _buildTrashBanner(WidgetRef ref) {
    final statusAsync = ref.watch(trashStatusProvider);
    return statusAsync.when(
      loading: () => const SizedBox.shrink(),
      error: (_, _) => const SizedBox.shrink(),
      data: (status) {
        if (!status.isAlmostFull && !status.isFull) return const SizedBox.shrink();

        final color = status.isFull ? Colors.red : Colors.orange;
        return Container(
          margin: const EdgeInsets.all(16),
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: color.withValues(alpha: 0.1),
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: color.withValues(alpha: 0.3)),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(LucideIcons.alertTriangle, color: color, size: 16),
                  const SizedBox(width: 8),
                  Text(
                    status.isFull ? 'Lixeira Cheia' : 'Lixeira Quase Cheia',
                    style: TextStyle(
                      color: color,
                      fontWeight: FontWeight.bold,
                      fontSize: 12,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 4),
              Text(
                '${status.total} / ${status.limit} itens. Limpe a lixeira para liberar espaço.',
                style: TextStyle(
                  color: color,
                  fontSize: 11,
                ),
              ),
            ],
          ),
        );
      }
    );
  }

  Widget _buildLogo(BuildContext context) {
    return Container(
      height: 70,
      padding: const EdgeInsets.symmetric(horizontal: 20),
      alignment: Alignment.centerLeft,
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              color: const Color(0xFF2563EB).withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(10),
              border: Border.all(color: const Color(0xFF2563EB).withValues(alpha: 0.3), width: 1),
            ),
            child: const Icon(LucideIcons.activity, color: Color(0xFF2563EB), size: 22),
          ),
          const SizedBox(width: 12),
          Text(
            'DentalCRM',
            style: TextStyle(
              fontSize: 20,
              fontWeight: FontWeight.bold,
              letterSpacing: -0.5,
              color: Theme.of(context).textTheme.titleLarge?.color ?? Theme.of(context).colorScheme.onSurface,
            ),
          ),
        ],
      ),
    );
  }
}

class _SidebarItem extends StatelessWidget {
  final IconData icon;
  final String label;
  final bool isSelected;
  final VoidCallback onTap;

  const _SidebarItem({
    required this.icon,
    required this.label,
    required this.isSelected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final primaryColor = const Color(0xFF2563EB); // Royal Blue
    final textSecondaryColor = theme.textTheme.bodyMedium?.color ?? theme.colorScheme.onSurfaceVariant;

    return Container(
      margin: const EdgeInsets.only(bottom: 6),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(10),
          hoverColor: primaryColor.withValues(alpha: 0.08),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            decoration: BoxDecoration(
              color: isSelected ? primaryColor.withValues(alpha: 0.12) : Colors.transparent,
              borderRadius: BorderRadius.circular(10),
              border: isSelected
                  ? Border.all(color: primaryColor.withValues(alpha: 0.3), width: 1)
                  : Border.all(color: Colors.transparent, width: 1),
            ),
            child: Row(
              children: [
                AnimatedContainer(
                  duration: const Duration(milliseconds: 200),
                  width: 4,
                  height: 20,
                  decoration: BoxDecoration(
                    color: isSelected ? primaryColor : Colors.transparent,
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
                const SizedBox(width: 8),
                Icon(
                  icon,
                  size: 20,
                  color: isSelected ? primaryColor : textSecondaryColor,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    label,
                    style: TextStyle(
                      fontSize: 14,
                      fontWeight: isSelected ? FontWeight.w600 : FontWeight.w500,
                      color: isSelected ? primaryColor : textSecondaryColor,
                    ),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

