import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:frontend_flutter/features/settings/data/settings_provider.dart';
import 'package:frontend_flutter/features/settings/data/settings_repository.dart';
import 'package:frontend_flutter/features/settings/presentation/invite_member_dialog.dart';
import 'package:frontend_flutter/features/settings/presentation/widgets/role_manager_dialog.dart';
import 'package:frontend_flutter/shared/widgets/password_confirmation_dialog.dart';
import 'package:frontend_flutter/features/schedule/data/schedule_provider.dart';
import 'package:frontend_flutter/features/settings/presentation/widgets/room_dialog.dart';
import 'package:frontend_flutter/features/settings/presentation/widgets/appointment_type_dialog.dart';
import 'package:frontend_flutter/features/settings/data/setting_model.dart';
import 'package:frontend_flutter/features/patients/presentation/widgets/import_export_dialog.dart';
import 'package:frontend_flutter/features/settings/presentation/saas_tab.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 7,
      child: Scaffold(

        appBar: AppBar(
          title: const Text('Configurações'),
          bottom: const TabBar(
            tabs: [
              Tab(icon: Icon(Icons.business), text: 'Perfil da Clínica'),
              Tab(icon: Icon(Icons.meeting_room), text: 'Salas'),
              Tab(icon: Icon(Icons.access_time), text: 'Regras e Horários'),
              Tab(icon: Icon(Icons.group), text: 'Equipe e Acessos'),
              Tab(icon: Icon(Icons.calendar_today), text: 'Tipos de Agendamento'),
              Tab(icon: Icon(Icons.security), text: 'Auditoria LGPD'),
              Tab(icon: Icon(Icons.card_membership), text: 'Plano e Assinatura'),
            ],
          ),
        ),
        body: const TabBarView(
          children: [
            _ClinicProfileTab(),
            _RoomsTab(),
            _ClinicRulesTab(),
            _TeamTab(),
            _AppointmentTypesTab(),
            _AuditTab(),
            SaaSTab(),
          ],
        ),
      ),
    );
  }
}

class _ClinicProfileTab extends ConsumerStatefulWidget {
  const _ClinicProfileTab();

  @override
  ConsumerState<_ClinicProfileTab> createState() => _ClinicProfileTabState();
}

class _ClinicProfileTabState extends ConsumerState<_ClinicProfileTab> {
  final _formKey = GlobalKey<FormState>();
  late TextEditingController _nameCtrl;
  late TextEditingController _cnpjCtrl;
  late TextEditingController _emailCtrl;
  late TextEditingController _phoneCtrl;
  bool _isLoading = false;

  @override
  void initState() {
    super.initState();
    _nameCtrl = TextEditingController();
    _cnpjCtrl = TextEditingController();
    _emailCtrl = TextEditingController();
    _phoneCtrl = TextEditingController();
  }

  @override
  void dispose() {
    _nameCtrl.dispose();
    _cnpjCtrl.dispose();
    _emailCtrl.dispose();
    _phoneCtrl.dispose();
    super.dispose();
  }

  Future<void> _save(int clinicId) async {
    if (!_formKey.currentState!.validate()) return;
    setState(() => _isLoading = true);
    try {
      final repo = ref.read(settingsRepositoryProvider);
      await repo.updateClinic(clinicId, {
        'name': _nameCtrl.text,
        'cnpj': _cnpjCtrl.text,
        'email': _emailCtrl.text,
        'phone': _phoneCtrl.text,
      });
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Configurações salvas!')));
        ref.invalidate(myClinicProvider);
      }
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Erro: $e')));
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final clinicAsync = ref.watch(myClinicProvider);

    return clinicAsync.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => Center(child: Text('Erro: $e')),
      data: (clinic) {
        // Preenche os controllers (apenas se vazios ou se precisarmos forçar update. 
        // Simplificado: sempre preenche no build inicial se não estiver editando, 
        // mas o ideal é no initState/didChangeDependencies. Para o demo está ok).
        if (_nameCtrl.text.isEmpty && _cnpjCtrl.text.isEmpty) {
          _nameCtrl.text = clinic.name;
          _cnpjCtrl.text = clinic.cnpj;
          _emailCtrl.text = clinic.email;
          _phoneCtrl.text = clinic.phone;
        }

        return SingleChildScrollView(
          padding: const EdgeInsets.all(24.0),
          child: Center(
            child: Container(
              constraints: const BoxConstraints(maxWidth: 600),
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
                      child: Form(
                        key: _formKey,
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              mainAxisAlignment: MainAxisAlignment.spaceBetween,
                              children: [
                                const Text('Dados da Clínica', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                                Container(
                                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
                                  decoration: BoxDecoration(
                                    color: clinic.status == 'active' ? Colors.green.shade100 : Colors.orange.shade100,
                                    borderRadius: BorderRadius.circular(16),
                                  ),
                                  child: Text(
                                    'Plano: ${clinic.status.toUpperCase()}',
                                    style: TextStyle(
                                      color: clinic.status == 'active' ? Colors.green.shade800 : Colors.orange.shade800,
                                      fontWeight: FontWeight.bold,
                                      fontSize: 12,
                                    ),
                                  ),
                                )
                              ],
                            ),
                            const SizedBox(height: 24),
                            TextFormField(
                              controller: _nameCtrl,
                              decoration: const InputDecoration(labelText: 'Nome da Clínica', border: OutlineInputBorder()),
                              validator: (v) => v!.isEmpty ? 'Obrigatório' : null,
                            ),
                            const SizedBox(height: 16),
                            TextFormField(
                              controller: _cnpjCtrl,
                              decoration: const InputDecoration(labelText: 'CNPJ', border: OutlineInputBorder()),
                            ),
                            const SizedBox(height: 16),
                            TextFormField(
                              controller: _emailCtrl,
                              decoration: const InputDecoration(labelText: 'E-mail de Contato', border: OutlineInputBorder()),
                            ),
                            const SizedBox(height: 16),
                            TextFormField(
                              controller: _phoneCtrl,
                              decoration: const InputDecoration(labelText: 'Telefone', border: OutlineInputBorder()),
                            ),
                            const SizedBox(height: 24),
                            SizedBox(
                              width: double.infinity,
                              height: 48,
                              child: ElevatedButton(
                                onPressed: _isLoading ? null : () => _save(clinic.id),
                                child: _isLoading 
                                    ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)) 
                                    : const Text('Salvar Alterações'),
                              ),
                            ),
                          ],
                        ),
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
                          const Text('Backup e Restauração', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                          const SizedBox(height: 8),
                          const Text('Gerencie a base de dados de pacientes.', style: TextStyle(color: Colors.grey)),
                          const SizedBox(height: 16),
                          Row(
                            children: [
                              ElevatedButton.icon(
                                icon: const Icon(LucideIcons.fileUp),
                                label: const Text('Importar / Exportar Base de Pacientes'),
                                onPressed: () {
                                  showDialog(
                                    context: context,
                                    builder: (_) => const ImportExportDialog(),
                                  );
                                },
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}

class _TeamTab extends ConsumerWidget {
  const _TeamTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final usersAsync = ref.watch(usersProvider);

    return usersAsync.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => Center(child: Text('Erro: $e')),
      data: (users) {
        return SingleChildScrollView(
          padding: const EdgeInsets.all(24.0),
          child: Center(
            child: Container(
              constraints: const BoxConstraints(maxWidth: 800),
              child: Card(

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
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          const Text('Equipe', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                          Row(
                            children: [
                              TextButton.icon(
                                onPressed: () {
                                  showDialog(
                                    context: context,
                                    builder: (_) => const RoleManagerDialog(),
                                  );
                                },
                                icon: const Icon(Icons.security),
                                label: const Text('Gerenciar Cargos'),
                              ),
                              const SizedBox(width: 16),
                              ElevatedButton.icon(
                                onPressed: () {
                                  showDialog(
                                    context: context,
                                    builder: (_) => const MemberDialog(),
                                  );
                                },
                                icon: const Icon(Icons.add),
                                label: const Text('Adicionar Membro'),
                              ),
                            ],
                          ),
                        ],
                      ),
                      const SizedBox(height: 24),
                      ListView.separated(
                        shrinkWrap: true,
                        physics: const NeverScrollableScrollPhysics(),
                        itemCount: users.length,
                        separatorBuilder: (_, _) => const Divider(),
                        itemBuilder: (context, index) {
                          final user = users[index];
                          return ListTile(
                            leading: CircleAvatar(
                              backgroundColor: Colors.blue.shade100,
                              child: Text(user.name.substring(0, 1).toUpperCase()),
                            ),
                            title: Text(user.name, style: const TextStyle(fontWeight: FontWeight.bold)),
                            subtitle: Text(user.email),
                            trailing: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Container(
                                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
                                  decoration: BoxDecoration(
                                    color: Colors.grey.shade100,
                                    borderRadius: BorderRadius.circular(16),
                                  ),
                                  child: Text(
                                    user.role?.name ?? 'Sem Papel',
                                    style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 12),
                                  ),
                                ),
                                const SizedBox(width: 8),
                                IconButton(
                                  icon: const Icon(Icons.edit, color: Colors.blue, size: 20),
                                  onPressed: () {
                                    showDialog(
                                      context: context,
                                      builder: (_) => MemberDialog(user: user),
                                    ).then((value) {
                                      if (value == true) {
                                        ref.invalidate(usersProvider);
                                      }
                                    });
                                  },
                                ),
                                IconButton(
                                  icon: const Icon(LucideIcons.trash2, color: Colors.red, size: 20),
                                  onPressed: () async {
                                    final confirmed = await showPasswordConfirmationDialog(
                                      context: context,
                                      title: 'Remover Membro',
                                      message: 'O usuário será removido da clínica e perderá acesso ao sistema. Para confirmar, digite sua senha de administrador:',
                                      onConfirm: (password) async {
                                        await ref.read(settingsRepositoryProvider).deleteTeamMember(user.id, password);
                                      },
                                    );
                                    if (confirmed == true) {
                                      ref.invalidate(usersProvider);
                                    }
                                  },
                                ),
                              ],
                            ),
                          );
                        },
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}

class _AuditTab extends ConsumerWidget {
  const _AuditTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final logsAsync = ref.watch(auditLogsProvider);
    return SingleChildScrollView(
      padding: const EdgeInsets.all(24.0),
      child: Center(
        child: Container(
          constraints: const BoxConstraints(maxWidth: 900),
          child: Card(

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
                  const Text('Logs de Segurança e Acesso', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                  const SizedBox(height: 8),
                  const Text('Histórico imutável de ações críticas no sistema (Compliance LGPD).', style: TextStyle(color: Colors.grey)),
                  const SizedBox(height: 24),
                  logsAsync.when(
                    loading: () => const Center(child: CircularProgressIndicator()),
                    error: (e, _) => Center(child: Text('Erro: $e')),
                    data: (logs) {
                      if (logs.isEmpty) {
                        return const Center(child: Text('Nenhum log de auditoria encontrado.'));
                      }
                      return SingleChildScrollView(
                        scrollDirection: Axis.horizontal,
                        child: DataTable(
                          headingTextStyle: const TextStyle(fontWeight: FontWeight.bold, color: Colors.black87),
                          columns: const [
                            DataColumn(label: Text('Data/Hora')),
                            DataColumn(label: Text('Usuário')),
                            DataColumn(label: Text('Ação')),
                            DataColumn(label: Text('Recurso')),
                            DataColumn(label: Text('Detalhes')),
                          ],
                          rows: logs.map((log) {
                            String formattedDate = log['created_at']?.toString() ?? '-';
                            if (formattedDate != '-') {
                              try {
                                final dt = DateTime.parse(formattedDate).toLocal();
                                formattedDate = '${dt.day.toString().padLeft(2, '0')}/${dt.month.toString().padLeft(2, '0')}/${dt.year} ${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}';
                              } catch (_) {}
                            }
                            final userName = log['user'] != null ? log['user']['name'] : log['user_id']?.toString();
                            
                            return DataRow(cells: [
                              DataCell(Text(formattedDate)),
                              DataCell(Text(userName ?? '-')),
                              DataCell(Text(log['action']?.toString() ?? '-')),
                              DataCell(Text('${log['entity']} #${log['entity_id']}')),
                              DataCell(Text(log['details']?.toString() ?? '-')),
                            ]);
                          }).toList(),
                        ),
                      );
                    },
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _AppointmentTypesTab extends ConsumerWidget {
  const _AppointmentTypesTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final typesAsync = ref.watch(appointmentTypesProvider);

    return typesAsync.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => Center(child: Text('Erro: $e')),
      data: (types) {
        return SingleChildScrollView(
          padding: const EdgeInsets.all(24.0),
          child: Center(
            child: Container(
              constraints: const BoxConstraints(maxWidth: 800),
              child: Card(

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
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          const Text('Tipos de Agendamento', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                          ElevatedButton.icon(
                            onPressed: () {
                              showDialog(
                                context: context,
                                builder: (_) => const AppointmentTypeDialog(),
                              );
                            },
                            icon: const Icon(Icons.add),
                            label: const Text('Novo Tipo'),
                          ),
                        ],
                      ),
                      const SizedBox(height: 24),
                      if (types.isEmpty)
                        const Text('Nenhum tipo de agendamento cadastrado.')
                      else
                        ListView.separated(
                          shrinkWrap: true,
                          physics: const NeverScrollableScrollPhysics(),
                          itemCount: types.length,
                          separatorBuilder: (_, _) => const Divider(),
                          itemBuilder: (context, index) {
                            final type = types[index];
                            final color = Color(int.parse(type.color.replaceAll('#', 'FF'), radix: 16));
                            return ListTile(
                              leading: CircleAvatar(
                                backgroundColor: color,
                                child: Icon(Icons.class_, color: color.computeLuminance() > 0.5 ? Colors.black : Colors.white),
                              ),
                              title: Text(type.name, style: const TextStyle(fontWeight: FontWeight.bold)),
                              subtitle: Text(type.description.isNotEmpty ? type.description : 'Sem descrição'),
                              trailing: Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Text('${type.durationMinutes} min', style: const TextStyle(fontWeight: FontWeight.bold)),
                                  const SizedBox(width: 16),
                                  IconButton(
                                    icon: const Icon(Icons.edit, color: Colors.blue),
                                    onPressed: () {
                                      showDialog(
                                        context: context,
                                        builder: (_) => AppointmentTypeDialog(type: type),
                                      );
                                    },
                                  ),
                                  IconButton(
                                    icon: const Icon(Icons.delete, color: Colors.red),
                                    onPressed: () async {
                                      final confirm = await showDialog<bool>(
                                        context: context,
                                        builder: (_) => AlertDialog(
                                          title: const Text('Excluir'),
                                          content: const Text('Tem certeza que deseja excluir?'),
                                          actions: [
                                            TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Não')),
                                            ElevatedButton(onPressed: () => Navigator.pop(context, true), child: const Text('Sim')),
                                          ],
                                        )
                                      );
                                      if (confirm == true) {
                                        await ref.read(settingsRepositoryProvider).deleteAppointmentType(type.id);
                                        ref.invalidate(appointmentTypesProvider);
                                      }
                                    },
                                  ),
                                ],
                              ),
                            );
                          },
                        ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}

class _RoomsTab extends ConsumerWidget {
  const _RoomsTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final roomsAsync = ref.watch(roomsProvider);

    return roomsAsync.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => Center(child: Text('Erro: $e')),
      data: (rooms) {
        return SingleChildScrollView(
          padding: const EdgeInsets.all(24.0),
          child: Center(
            child: Container(
              constraints: const BoxConstraints(maxWidth: 800),
              child: Card(

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
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          const Text('Gerenciamento de Salas', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                          ElevatedButton.icon(
                            onPressed: () {
                              showDialog(
                                context: context,
                                builder: (_) => const RoomDialog(),
                              );
                            },
                            icon: const Icon(Icons.add),
                            label: const Text('Nova Sala'),
                          ),
                        ],
                      ),
                      const SizedBox(height: 24),
                      if (rooms.isEmpty)
                        const Text('Nenhuma sala cadastrada.')
                      else
                        ListView.separated(
                          shrinkWrap: true,
                          physics: const NeverScrollableScrollPhysics(),
                          itemCount: rooms.length,
                          separatorBuilder: (_, _) => const Divider(),
                          itemBuilder: (context, index) {
                            final room = rooms[index];
                            return ListTile(
                              leading: CircleAvatar(
                                backgroundColor: room.isActive ? Colors.green.shade100 : Colors.red.shade100,
                                child: Icon(Icons.meeting_room, color: room.isActive ? Colors.green.shade800 : Colors.red.shade800),
                              ),
                              title: Text(room.name, style: const TextStyle(fontWeight: FontWeight.bold)),
                              subtitle: Text(room.description ?? 'Sem descrição'),
                              trailing: Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Switch(
                                    value: room.isActive,
                                    onChanged: (val) async {
                                      await ref.read(settingsRepositoryProvider).updateRoom(room.id, {'is_active': val});
                                      ref.invalidate(roomsProvider);
                                    },
                                  ),
                                  IconButton(
                                    icon: const Icon(Icons.edit, color: Colors.blue),
                                    onPressed: () {
                                      showDialog(
                                        context: context,
                                        builder: (_) => RoomDialog(room: room),
                                      );
                                    },
                                  ),
                                  IconButton(
                                    icon: const Icon(Icons.delete, color: Colors.red),
                                    onPressed: () async {
                                      final confirm = await showDialog<bool>(
                                        context: context,
                                        builder: (_) => AlertDialog(
                                          title: const Text('Excluir'),
                                          content: const Text('Tem certeza que deseja excluir esta sala?'),
                                          actions: [
                                            TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Não')),
                                            ElevatedButton(onPressed: () => Navigator.pop(context, true), child: const Text('Sim')),
                                          ],
                                        )
                                      );
                                      if (confirm == true) {
                                        await ref.read(settingsRepositoryProvider).deleteRoom(room.id);
                                        ref.invalidate(roomsProvider);
                                      }
                                    },
                                  ),
                                ],
                              ),
                            );
                          },
                        ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}

class _ClinicRulesTab extends ConsumerStatefulWidget {
  const _ClinicRulesTab();

  @override
  ConsumerState<_ClinicRulesTab> createState() => _ClinicRulesTabState();
}

class _ClinicRulesTabState extends ConsumerState<_ClinicRulesTab> {
  final _hoursCtrl = TextEditingController();

  Future<void> _addHoliday() async {
    final DateTime? picked = await showDatePicker(
      context: context,
      initialDate: DateTime.now(),
      firstDate: DateTime.now().subtract(const Duration(days: 365)),
      lastDate: DateTime.now().add(const Duration(days: 1095)),
    );
    if (picked != null) {
      final dateStr = "${picked.year}-${picked.month.toString().padLeft(2, '0')}-${picked.day.toString().padLeft(2, '0')}";
      
      final settings = ref.read(appSettingsProvider).value ?? [];
      final customHolidaysSetting = settings.firstWhere(
        (s) => s.key == 'custom_holidays', 
        orElse: () => AppSetting(id: 0, key: 'custom_holidays', value: '[]')
      );
      
      // Parse current
      final currentList = customHolidaysSetting.value.replaceAll('[', '').replaceAll(']', '').replaceAll('"', '').split(',').where((e) => e.isNotEmpty).toList();
      currentList.add(dateStr);
      
      final newValue = '["${currentList.join('","')}"]';
      
      await ref.read(settingsRepositoryProvider).saveSettings({'custom_holidays': newValue});
      ref.invalidate(appSettingsProvider);
      ref.invalidate(clinicHolidaysProvider);
    }
  }

  Future<void> _saveHours() async {
    await ref.read(settingsRepositoryProvider).saveSettings({'business_hours': _hoursCtrl.text});
    ref.invalidate(appSettingsProvider);
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Horários salvos com sucesso!')));
    }
  }

  @override
  void dispose() {
    _hoursCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final settingsAsync = ref.watch(appSettingsProvider);
    final holidaysAsync = ref.watch(clinicHolidaysProvider);

    return settingsAsync.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => Center(child: Text('Erro: $e')),
      data: (settings) {
        if (_hoursCtrl.text.isEmpty) {
          final hoursSetting = settings.firstWhere((s) => s.key == 'business_hours', orElse: () => AppSetting(id: 0, key: 'business_hours', value: '08:00 - 18:00'));
          _hoursCtrl.text = hoursSetting.value;
        }

        return SingleChildScrollView(
          padding: const EdgeInsets.all(24.0),
          child: Center(
            child: Container(
              constraints: const BoxConstraints(maxWidth: 800),
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
                          const Text('Horários de Funcionamento', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                          const SizedBox(height: 24),
                          TextFormField(
                            controller: _hoursCtrl,
                            decoration: const InputDecoration(
                              labelText: 'Horário (ex: 08:00 - 18:00)',
                              border: OutlineInputBorder(),
                            ),
                          ),
                          const SizedBox(height: 16),
                          ElevatedButton(
                            onPressed: _saveHours,
                            child: const Text('Salvar Horários'),
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
                          Row(
                            mainAxisAlignment: MainAxisAlignment.spaceBetween,
                            children: [
                              const Text('Feriados e Bloqueios', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                              ElevatedButton.icon(
                                onPressed: _addHoliday,
                                icon: const Icon(Icons.add),
                                label: const Text('Bloquear Data'),
                              ),
                            ],
                          ),
                          const SizedBox(height: 24),
                          holidaysAsync.when(
                            loading: () => const CircularProgressIndicator(),
                            error: (e, _) => Text('Erro: $e'),
                            data: (holidays) {
                              if (holidays.isEmpty) return const Text('Nenhum feriado configurado.');
                              return ListView.separated(
                                shrinkWrap: true,
                                physics: const NeverScrollableScrollPhysics(),
                                itemCount: holidays.length,
                                separatorBuilder: (_, _) => const Divider(),
                                itemBuilder: (context, index) {
                                  final holiday = holidays[index];
                                  final isCustom = holiday['type'] == 'custom';
                                  return ListTile(
                                    leading: CircleAvatar(
                                      backgroundColor: isCustom ? Colors.orange.shade100 : Colors.blue.shade100,
                                      child: Icon(Icons.event_busy, color: isCustom ? Colors.orange.shade800 : Colors.blue.shade800),
                                    ),
                                    title: Text(holiday['name'] ?? 'Feriado'),
                                    subtitle: Text(holiday['date'] ?? ''),
                                    trailing: isCustom ? IconButton(
                                      icon: const Icon(Icons.delete, color: Colors.red),
                                      onPressed: () async {
                                        final settings = ref.read(appSettingsProvider).value ?? [];
                                        final customHolidaysSetting = settings.firstWhere(
                                          (s) => s.key == 'custom_holidays', 
                                          orElse: () => AppSetting(id: 0, key: 'custom_holidays', value: '[]')
                                        );
                                        final currentList = customHolidaysSetting.value.replaceAll('[', '').replaceAll(']', '').replaceAll('"', '').split(',').where((e) => e.isNotEmpty).toList();
                                        currentList.remove(holiday['date']);
                                        var newValue = '["${currentList.join('","')}"]';
                                        if (newValue == '[""]') newValue = '[]';
                                        
                                        await ref.read(settingsRepositoryProvider).saveSettings({'custom_holidays': newValue});
                                        ref.invalidate(appSettingsProvider);
                                        ref.invalidate(clinicHolidaysProvider);
                                      },
                                    ) : null,
                                  );
                                },
                              );
                            },
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}






