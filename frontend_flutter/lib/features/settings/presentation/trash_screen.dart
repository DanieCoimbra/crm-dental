import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/settings/data/trash_provider.dart';
import 'package:frontend_flutter/features/settings/data/trash_repository.dart';
import 'package:frontend_flutter/features/patients/data/patients_provider.dart';
import 'package:frontend_flutter/features/schedule/data/schedule_provider.dart';
import 'package:frontend_flutter/features/settings/data/settings_provider.dart';
import 'package:frontend_flutter/features/auth/providers/auth_provider.dart';
import 'package:intl/intl.dart';

class TrashScreen extends ConsumerStatefulWidget {
  const TrashScreen({super.key});

  @override
  ConsumerState<TrashScreen> createState() => _TrashScreenState();
}

class _TrashScreenState extends ConsumerState<TrashScreen> {
  Future<void> _restoreItem(String type, int id) async {
    final confirm = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Restaurar Registro'),
        content: const Text('Deseja realmente restaurar este registro?'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancelar')),
          ElevatedButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Restaurar'),
          ),
        ],
      ),
    );

    if (confirm != true) return;

    try {
      await ref.read(trashRepositoryProvider).restoreItem(type, id);
      ref.invalidate(trashProvider);
      ref.invalidate(trashStatusProvider);
      
      // Invalidate related lists to update UI
      if (type == 'user') ref.invalidate(usersProvider);
      if (type == 'patient') ref.invalidate(patientsListProvider);
      if (type == 'appointment') ref.invalidate(appointmentsProvider);

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Item restaurado com sucesso!'), backgroundColor: Colors.green));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Erro ao restaurar: $e'), backgroundColor: Colors.red));
      }
    }
  }

  Future<void> _forceDelete(String type, int id) async {
    final passwordController = TextEditingController();
    
    final confirm = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (context, setState) {
          return AlertDialog(
            title: Row(
              children: [
                const Icon(Icons.delete_forever, color: Colors.red),
                const SizedBox(width: 8),
                const Text('Atenção', style: TextStyle(color: Colors.red)),
              ],
            ),
            content: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('Você está prestes a excluir este registro de forma definitiva. Ele não poderá ser recuperado. Por favor, confirme com sua senha de gerente.'),
                const SizedBox(height: 16),
                TextField(
                  controller: passwordController,
                  obscureText: true,
                  decoration: const InputDecoration(
                    labelText: 'Sua senha de gerente',
                    border: OutlineInputBorder(),
                  ),
                ),
              ],
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(ctx, false), 
                child: const Text('Cancelar')
              ),
              ElevatedButton(
                style: ElevatedButton.styleFrom(backgroundColor: Colors.red),
                onPressed: () {
                  if (passwordController.text.isNotEmpty) {
                    Navigator.pop(ctx, true);
                  }
                },
                child: const Text('Excluir Definitivamente', style: TextStyle(color: Colors.white)),
              ),
            ],
          );
        }
      ),
    );

    if (confirm != true || passwordController.text.isEmpty) return;

    try {
      await ref.read(trashRepositoryProvider).forceDeleteItem(type, id, passwordController.text);
      ref.invalidate(trashProvider);
      ref.invalidate(trashStatusProvider);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Registro excluído permanentemente.'), backgroundColor: Colors.green));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Erro: $e'), backgroundColor: Colors.red));
      }
    }
  }

  String _formatDate(String? dateStr) {
    if (dateStr == null) return 'Desconhecido';
    try {
      final dt = DateTime.parse(dateStr);
      return DateFormat('dd/MM/yyyy HH:mm').format(dt);
    } catch (_) {
      return dateStr;
    }
  }

  String _getName(dynamic obj, [String defaultName = 'Desconhecido']) {
    if (obj == null) return defaultName;
    if (obj is Map) return obj['name'] ?? defaultName;
    return defaultName; // Case it's just an integer ID
  }

  Widget _buildTable(List items, String type, String userRole) {
    if (items.isEmpty) {
      return const Center(
        child: Padding(
          padding: EdgeInsets.all(48.0),
          child: Text('A lixeira está vazia para esta categoria.', style: TextStyle(color: Colors.grey, fontSize: 16)),
        ),
      );
    }

    final canForceDelete = ['owner', 'manager', 'admin'].contains(userRole.toLowerCase());

    return LayoutBuilder(
      builder: (context, constraints) {
        return SingleChildScrollView(
          scrollDirection: Axis.vertical,
          child: SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: ConstrainedBox(
              constraints: BoxConstraints(minWidth: constraints.maxWidth),
              child: DataTable(
                dataRowMaxHeight: double.infinity,
                dataRowMinHeight: 60,
                headingRowColor: WidgetStateProperty.all(Colors.grey.shade100),
                columns: const [
                  DataColumn(label: Text('DETALHES DO REGISTRO', style: TextStyle(fontWeight: FontWeight.bold))),
                  DataColumn(label: Text('EXCLUÍDO EM', style: TextStyle(fontWeight: FontWeight.bold))),
                  DataColumn(label: Text('EXCLUÍDO POR', style: TextStyle(fontWeight: FontWeight.bold))),
                  DataColumn(label: Text('AÇÕES', style: TextStyle(fontWeight: FontWeight.bold))),
                ],
                rows: items.map((item) {
                  Widget detailsWidget;
                  if (type == 'patient') {
                    detailsWidget = Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Text(item['name'] ?? 'Desconhecido', style: const TextStyle(fontWeight: FontWeight.w600)),
                        Text('CPF: ${item['cpf'] ?? ''}', style: const TextStyle(fontSize: 12, color: Colors.grey)),
                      ],
                    );
                  } else if (type == 'user') {
                    detailsWidget = Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Row(
                          children: [
                            Text(item['name'] ?? 'Desconhecido', style: const TextStyle(fontWeight: FontWeight.w600)),
                            const SizedBox(width: 8),
                            Container(
                              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                              decoration: BoxDecoration(color: Colors.grey.shade200, borderRadius: BorderRadius.circular(4)),
                              child: Text(item['role'] ?? '', style: const TextStyle(fontSize: 10)),
                            ),
                          ],
                        ),
                        Text('E-mail: ${item['email'] ?? ''}', style: const TextStyle(fontSize: 12, color: Colors.grey)),
                      ],
                    );
                  } else {
                    detailsWidget = Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Text('Consulta: ${_getName(item['patient'])}', style: const TextStyle(fontWeight: FontWeight.w600)),
                        Text('Médico: ${_getName(item['doctor'])} | Data: ${_formatDate(item['start_time'])}', style: const TextStyle(fontSize: 12, color: Colors.grey)),
                      ],
                    );
                  }

                  return DataRow(
                    cells: [
                      DataCell(
                        SizedBox(
                          width: constraints.maxWidth * 0.4,
                          child: Padding(
                            padding: const EdgeInsets.symmetric(vertical: 12.0),
                            child: detailsWidget,
                          ),
                        ),
                      ),
                      DataCell(Text(_formatDate(item['deleted_at']))),
                      DataCell(Text(_getName(item['deleted_by'], 'Sistema'))),
                      DataCell(
                        Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            IconButton(
                              icon: const Icon(Icons.restore),
                              color: Colors.blue,
                              tooltip: 'Restaurar',
                              onPressed: () => _restoreItem(type, item['id']),
                            ),
                            if (canForceDelete)
                              IconButton(
                                icon: const Icon(Icons.delete_outline),
                                color: Colors.red,
                                tooltip: 'Excluir Permanentemente',
                                onPressed: () => _forceDelete(type, item['id']),
                              ),
                          ],
                        ),
                      ),
                    ],
                  );
                }).toList(),
              ),
            ),
          ),
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final trashAsync = ref.watch(trashProvider);
    final statusAsync = ref.watch(trashStatusProvider);
    final userRole = ref.watch(authProvider).role.toLowerCase();
    
    final canViewAllTabs = ['owner', 'manager', 'admin'].contains(userRole);

    return Scaffold(

      body: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Expanded(
                child: Row(
                  children: [
                    const Icon(Icons.delete_outline, size: 32, color: Colors.red),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text('Lixeira do Sistema', style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold, color: Theme.of(context).textTheme.titleLarge?.color)),
                          Text('Visualize registros excluídos, audite quem os apagou e restaure se necessário.', style: TextStyle(color: Colors.grey.shade600, fontSize: 14)),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
              statusAsync.when(
                data: (status) {
                  return Container(
                    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                    decoration: BoxDecoration(
                      color: status['is_full'] ? Colors.red.shade100 : Colors.blue.shade50,
                      borderRadius: BorderRadius.circular(20),
                    ),
                    child: Text(
                      'Espaço Usado: ${status['total']} / ${status['limit']}',
                      style: TextStyle(
                        fontWeight: FontWeight.bold,
                        color: status['is_full'] ? Colors.red.shade900 : Colors.blue.shade900,
                      ),
                    ),
                  );
                },
                loading: () => const SizedBox.shrink(),
                error: (_, __) => const SizedBox.shrink(),
              ),
            ],
          ),
          const SizedBox(height: 24),
          Expanded(
            child: trashAsync.when(
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (e, _) => Center(child: Text('Erro: $e')),
              data: (data) {
                final users = data['users'] as List;
                final patients = data['patients'] as List;
                final appointments = data['appointments'] as List;
                
                int numTabs = 1; // Always show appointments
                if (canViewAllTabs) {
                  numTabs = 3;
                }

                return DefaultTabController(
                  length: numTabs,
                  child: Card(
                    clipBehavior: Clip.antiAlias,
                    elevation: 0,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(8),
                      side: BorderSide(color: Theme.of(context).dividerTheme.color ?? Colors.grey.shade200),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Container(
                          color: Colors.grey.shade50,
                          child: TabBar(
                            labelColor: Colors.blue.shade700,
                            unselectedLabelColor: Colors.grey.shade600,
                            indicatorColor: Colors.blue.shade700,
                            tabs: [
                              if (canViewAllTabs)
                                Tab(
                                  child: Row(
                                    mainAxisAlignment: MainAxisAlignment.center,
                                    children: [
                                      const Icon(Icons.person, size: 18),
                                      const SizedBox(width: 8),
                                      Text('Pacientes (${patients.length})'),
                                    ],
                                  ),
                                ),
                              Tab(
                                child: Row(
                                  mainAxisAlignment: MainAxisAlignment.center,
                                  children: [
                                    const Icon(Icons.calendar_today, size: 18),
                                    const SizedBox(width: 8),
                                    Text('Consultas (${appointments.length})'),
                                  ],
                                ),
                              ),
                              if (canViewAllTabs)
                                Tab(
                                  child: Row(
                                    mainAxisAlignment: MainAxisAlignment.center,
                                    children: [
                                      const Icon(Icons.people, size: 18),
                                      const SizedBox(width: 8),
                                      Text('Equipe (${users.length})'),
                                    ],
                                  ),
                                ),
                            ],
                          ),
                        ),
                        Expanded(
                          child: TabBarView(
                            children: [
                              if (canViewAllTabs)
                                _buildTable(patients, 'patient', userRole),
                              _buildTable(appointments, 'appointment', userRole),
                              if (canViewAllTabs)
                                _buildTable(users, 'user', userRole),
                            ],
                          ),
                        ),
                      ],
                    ),
                  ),
                );
              },
            ),
          ),
        ],
      ),
      ),
    );
  }
}



