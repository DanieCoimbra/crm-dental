import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:frontend_flutter/core/theme/app_theme.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/auth/providers/auth_provider.dart';
import 'package:frontend_flutter/features/auth/widgets/room_selector.dart';
import 'package:frontend_flutter/features/auth/presentation/widgets/profile_dialog.dart';
import 'package:frontend_flutter/shared/widgets/theme_toggle_button.dart';
import 'package:go_router/go_router.dart';

class Topbar extends ConsumerWidget implements PreferredSizeWidget {
  const Topbar({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final authState = ref.watch(authProvider);
    final currentUserAsync = ref.watch(currentUserProvider);

    final theme = Theme.of(context);

    return Container(
      height: 70,
      decoration: BoxDecoration(
        color: theme.appBarTheme.backgroundColor,
        border: Border(
          bottom: BorderSide(color: theme.dividerTheme.color ?? AppTheme.border, width: 1),
        ),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 24),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Expanded(
            child: Row(
              children: [
                const Icon(LucideIcons.activity, color: AppTheme.primary, size: 28),
                const SizedBox(width: 8),
                const Flexible(
                  child: Text(
                    'Clínica Go',
                    style: TextStyle(
                      fontSize: 20,
                      fontWeight: FontWeight.w800,
                      color: AppTheme.primary,
                    ),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                const SizedBox(width: 16),
                Container(width: 1, height: 24, color: theme.dividerTheme.color ?? AppTheme.border),
                const SizedBox(width: 16),
                Expanded(
                  child: Text(
                    _getPageTitle(context),
                    style: TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.w600,
                      color: theme.textTheme.titleLarge?.color ?? AppTheme.textPrimary,
                    ),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
              ],
            ),
          ),
          Flexible(
            flex: 2,
            child: SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                children: [
                  currentUserAsync.when(
                    data: (user) {
                      final initials = user.name.isNotEmpty 
                          ? (user.name.split(' ').length > 1 
                              ? '${user.name.split(' ')[0][0]}${user.name.split(' ')[1][0]}' 
                              : user.name.substring(0, user.name.length > 1 ? 2 : 1))
                          : 'US';
                      
                      final isDoctor = user.role?.name == 'doctor' || authState.role == 'doctor' || authState.role == 'medico';

                      String? avatarUrl = user.avatar;
                      if (avatarUrl != null && avatarUrl.isNotEmpty && avatarUrl.startsWith('/uploads')) {
                        avatarUrl = 'http://localhost:8080$avatarUrl';
                      }

                      return Row(
                        children: [
                          if (isDoctor) ...[
                            const RoomSelector(),
                            const SizedBox(width: 16),
                          ],
                          const ThemeToggleButton(),
                          const SizedBox(width: 8),
                          IconButton(
                            icon: Icon(LucideIcons.bell, color: theme.iconTheme.color),
                            onPressed: () {},
                          ),
                          const SizedBox(width: 16),
                          avatarUrl != null && avatarUrl.isNotEmpty
                              ? Container(
                                  height: 40,
                                  width: 40,
                                  decoration: BoxDecoration(
                                    shape: BoxShape.circle,
                                    border: Border.all(color: AppTheme.primary, width: 2),
                                    image: DecorationImage(
                                      image: NetworkImage(avatarUrl),
                                      fit: BoxFit.cover,
                                    ),
                                  ),
                                )
                              : Container(
                                  height: 40,
                                  width: 40,
                                  decoration: BoxDecoration(
                                    color: AppTheme.primaryLight,
                                    shape: BoxShape.circle,
                                    border: Border.all(color: AppTheme.primary, width: 2),
                                  ),
                                  child: Center(
                                    child: Text(
                                      initials.toUpperCase(),
                                      style: const TextStyle(

                                        fontWeight: FontWeight.bold,
                                      ),
                                    ),
                                  ),
                                ),
                          const SizedBox(width: 12),
                          Column(
                            mainAxisAlignment: MainAxisAlignment.center,
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                user.name,
                                style: TextStyle(
                                  fontSize: 14,
                                  fontWeight: FontWeight.bold,
                                  color: theme.textTheme.titleLarge?.color ?? AppTheme.textPrimary,
                                ),
                              ),
                              Text(
                                user.role?.name ?? authState.role,
                                style: TextStyle(
                                  fontSize: 12,
                                  color: theme.textTheme.bodyMedium?.color ?? AppTheme.textSecondary,
                                ),
                              ),
                            ],
                          ),
                        ]
                      );
                    },
                    loading: () => const SizedBox(width: 100, child: Center(child: CircularProgressIndicator())),
                    error: (e, _) => const Icon(LucideIcons.alertCircle, color: Colors.red),
                  ),
                  const SizedBox(width: 8),
                  PopupMenuButton(
                    icon: Icon(LucideIcons.chevronDown, color: theme.iconTheme.color),
                    itemBuilder: (context) => [
                      const PopupMenuItem(
                        value: 'profile',
                        child: Text('Meu Perfil'),
                      ),
                      const PopupMenuItem(
                        value: 'logout',
                        child: Text('Sair', style: TextStyle(color: Colors.red)),
                      ),
                    ],
                    onSelected: (value) {
                      if (value == 'profile') {
                        showDialog(context: context, builder: (_) => const ProfileDialog());
                      } else if (value == 'logout') {
                        ref.read(authProvider.notifier).logout();
                      }
                    },
                  ),
                  const SizedBox(width: 8),
                  IconButton(
                    icon: Icon(LucideIcons.menu, color: theme.iconTheme.color, size: 28),
                    onPressed: () {
                      Scaffold.of(context).openEndDrawer();
                    },
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  @override
  Size get preferredSize => const Size.fromHeight(70);

  String _getPageTitle(BuildContext context) {
    final path = GoRouterState.of(context).uri.path;
    if (path.startsWith('/patients')) return 'Pacientes';
    if (path.startsWith('/communications')) return 'Comunicações';
    if (path.startsWith('/schedule')) return 'Agenda';
    if (path.startsWith('/settings')) return 'Configurações';
    return 'Painel de Controle';
  }
}

