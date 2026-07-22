import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/settings/data/settings_provider.dart';
import 'package:frontend_flutter/features/settings/data/settings_repository.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

class RoleManagerDialog extends ConsumerStatefulWidget {
  const RoleManagerDialog({super.key});

  @override
  ConsumerState<RoleManagerDialog> createState() => _RoleManagerDialogState();
}

class _RoleManagerDialogState extends ConsumerState<RoleManagerDialog> {
  final _roleNameCtrl = TextEditingController();
  final Map<String, bool> _selectedPermissions = {};
  bool _isLoading = false;
  bool _isCreating = false;
  int? _editingRoleId;

  @override
  void dispose() {
    _roleNameCtrl.dispose();
    super.dispose();
  }

  String _getRoleLabel(String r) {
    switch (r.toLowerCase()) {
      case 'owner': return 'Dono da Clínica';
      case 'manager': return 'Gerente';
      case 'doctor': return 'Dentista';
      case 'receptionist': return 'Recepcionista';
      default: return r;
    }
  }

  String _getPermissionLabel(String p) {
    switch (p) {
      case 'patients_view': return 'Visualizar Pacientes';
      case 'patients_edit': return 'Criar / Editar Pacientes';
      case 'patients_delete': return 'Excluir Pacientes';
      case 'appointments_view': return 'Visualizar Agendamentos';
      case 'appointments_edit': return 'Criar / Editar Agendamentos';
      case 'appointments_delete': return 'Excluir Agendamentos';
      case 'waitlist_manage': return 'Gerenciar Lista de Espera';
      case 'rooms_manage': return 'Gerenciar Salas';
      case 'settings_manage': return 'Gerenciar Configurações Gerais';
      case 'roles_manage': return 'Gerenciar Cargos e Permissões';
      case 'financial_view': return 'Visualizar Financeiro';
      case 'financial_edit': return 'Gerenciar Financeiro';
      default:
        // Caso surja uma nova permissão ainda não mapeada
        return p.replaceAll('_', ' ').replaceFirst(p[0], p[0].toUpperCase());
    }
  }

  Future<void> _saveRole() async {
    if (_roleNameCtrl.text.isEmpty) return;
    
    setState(() => _isLoading = true);
    try {
      final repo = ref.read(settingsRepositoryProvider);
      final payload = {
        'name': _roleNameCtrl.text,
        'permissions': _selectedPermissions.keys.where((p) => _selectedPermissions[p] == true).toList(),
      };

      if (_editingRoleId != null) {
        await repo.updateRole(_editingRoleId!, payload);
      } else {
        await repo.createRole(payload);
      }
      
      if (mounted) {
        ref.invalidate(rolesProvider);
        final wasEditing = _editingRoleId != null;
        setState(() {
          _isCreating = false;
          _editingRoleId = null;
          _roleNameCtrl.clear();
          _selectedPermissions.clear();
        });
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(wasEditing ? 'Cargo atualizado com sucesso!' : 'Cargo criado com sucesso!')));
      }
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Erro: $e')));
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final rolesAsync = ref.watch(rolesProvider);
    final permissionsAsync = ref.watch(permissionsProvider);

    return AlertDialog(
      title: const Text('Gerenciar Cargos e Permissões'),
      content: SizedBox(
        width: 500,
        height: 400,
        child: _isCreating ? _buildCreateRoleForm(permissionsAsync) : _buildRolesList(rolesAsync),
      ),
      actions: [
        if (_isCreating)
          TextButton(
            onPressed: () => setState(() {
              _isCreating = false;
              _editingRoleId = null;
              _roleNameCtrl.clear();
              _selectedPermissions.clear();
            }),
            child: const Text('Voltar'),
          ),
        if (!_isCreating)
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Fechar'),
          ),
        if (_isCreating)
          ElevatedButton(
            onPressed: _isLoading ? null : _saveRole,
            child: _isLoading ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2)) : const Text('Salvar Cargo'),
          ),
        if (!_isCreating)
          ElevatedButton.icon(
            onPressed: () => setState(() => _isCreating = true),
            icon: const Icon(LucideIcons.plus),
            label: const Text('Novo Cargo'),
          ),
      ],
    );
  }

  Widget _buildRolesList(AsyncValue rolesAsync) {
    return rolesAsync.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => Center(child: Text('Erro: $e')),
      data: (roles) {
        if (roles.isEmpty) return const Center(child: Text('Nenhum cargo encontrado.'));
        return ListView.separated(
          itemCount: roles.length,
          separatorBuilder: (_, _) => const Divider(),
          itemBuilder: (context, index) {
            final role = roles[index];
            return ListTile(
              title: Text(_getRoleLabel(role.name), style: const TextStyle(fontWeight: FontWeight.bold)),
              subtitle: Text('${role.permissions.length} permissões atreladas'),
              trailing: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  IconButton(
                    icon: const Icon(Icons.edit, color: Colors.blue),
                    onPressed: () {
                      _roleNameCtrl.text = role.name;
                      _selectedPermissions.clear();
                      for (final p in role.permissions) {
                        _selectedPermissions[p] = true;
                      }
                      setState(() {
                        _editingRoleId = role.id;
                        _isCreating = true;
                      });
                    },
                  ),
                  IconButton(
                    icon: const Icon(Icons.delete, color: Colors.red),
                    onPressed: () async {
                      final confirm = await showDialog<bool>(
                        context: context,
                        builder: (_) => AlertDialog(
                          title: const Text('Excluir Cargo'),
                          content: const Text('Certeza que deseja excluir este cargo?'),
                          actions: [
                            TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Não')),
                            ElevatedButton(onPressed: () => Navigator.pop(context, true), child: const Text('Sim')),
                          ],
                        ),
                      );
                      if (confirm == true) {
                        try {
                          await ref.read(settingsRepositoryProvider).deleteRole(role.id);
                          ref.invalidate(rolesProvider);
                        } catch (e) {
                          if (context.mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Erro: $e')));
                        }
                      }
                    },
                  ),
                ],
              ),
            );
          },
        );
      },
    );
  }

  Widget _buildCreateRoleForm(AsyncValue permissionsAsync) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        TextField(
          controller: _roleNameCtrl,
          decoration: const InputDecoration(labelText: 'Nome do Cargo', border: OutlineInputBorder()),
        ),
        const SizedBox(height: 16),
        const Text('Permissões', style: TextStyle(fontWeight: FontWeight.bold)),
        const SizedBox(height: 8),
        Expanded(
          child: permissionsAsync.when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (e, _) => Center(child: Text('Erro: $e')),
            data: (permissions) {
              return ListView.builder(
                itemCount: permissions.length,
                itemBuilder: (context, index) {
                  final p = permissions[index];
                  return CheckboxListTile(
                    title: Text(_getPermissionLabel(p)),
                    value: _selectedPermissions[p] ?? false,
                    onChanged: (val) {
                      setState(() {
                        _selectedPermissions[p] = val ?? false;
                      });
                    },
                  );
                },
              );
            },
          ),
        ),
      ],
    );
  }
}
