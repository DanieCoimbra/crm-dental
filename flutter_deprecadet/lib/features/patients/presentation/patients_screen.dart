import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:frontend_flutter/features/patients/data/patient_model.dart';
import 'package:frontend_flutter/features/patients/data/patients_provider.dart';
import 'package:frontend_flutter/features/patients/data/patients_repository.dart';
import 'package:frontend_flutter/features/patients/presentation/widgets/patient_form_dialog.dart';
import 'package:frontend_flutter/features/patients/presentation/widgets/import_export_dialog.dart';
import 'package:frontend_flutter/shared/widgets/password_confirmation_dialog.dart';

class PatientsScreen extends ConsumerStatefulWidget {
  const PatientsScreen({super.key});

  @override
  ConsumerState<PatientsScreen> createState() => _PatientsScreenState();
}

class _PatientsScreenState extends ConsumerState<PatientsScreen> {
  final TextEditingController _searchController = TextEditingController();
  Timer? _debounce;
  String _searchQuery = '';
  int _currentPage = 1;
  String _selectedInsurance = 'TODOS';

  final List<String> _insuranceFilters = [
    'TODOS',
    'Particular',
    'Unimed',
    'Amil',
    'Bradesco Saúde',
    'SulAmérica',
    'Porto Seguro',
  ];

  @override
  void dispose() {
    _debounce?.cancel();
    _searchController.dispose();
    super.dispose();
  }

  void _onSearchChanged(String query) {
    if (_debounce?.isActive ?? false) _debounce!.cancel();
    _debounce = Timer(const Duration(milliseconds: 300), () {
      setState(() {
        _searchQuery = query;
        _currentPage = 1;
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    final queryParams = PatientQueryParams(
      search: _searchQuery,
      page: _currentPage,
      healthInsurance: _selectedInsurance == 'TODOS' ? null : _selectedInsurance,
    );

    final patientsAsync = ref.watch(patientsPaginatedProvider(queryParams));
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Gestão de Pacientes'),
        scrolledUnderElevation: 0,
        actions: [
          IconButton(
            icon: const Icon(LucideIcons.fileSpreadsheet),
            tooltip: 'Importar / Exportar CSV',
            onPressed: () {
              showDialog(
                context: context,
                builder: (_) => const ImportExportDialog(),
              );
            },
          ),
          const SizedBox(width: 8),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () async {
          final res = await showDialog<bool>(
            context: context,
            builder: (_) => const PatientFormDialog(),
          );
          if (res == true) {
            ref.invalidate(patientsPaginatedProvider);
          }
        },
        icon: const Icon(LucideIcons.userPlus),
        label: const Text('Novo Paciente'),
      ),
      body: Column(
        children: [
          // Filter & Search bar container
          Container(
            padding: const EdgeInsets.all(20.0),
            color: theme.scaffoldBackgroundColor,
            child: Wrap(
              spacing: 12,
              runSpacing: 12,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                // Search Input
                SizedBox(
                  width: 380,
                  child: TextField(
                    controller: _searchController,
                    onChanged: _onSearchChanged,
                    decoration: InputDecoration(
                      hintText: 'Buscar por Nome, CPF ou E-mail...',
                      prefixIcon: const Icon(LucideIcons.search),
                      suffixIcon: _searchController.text.isNotEmpty
                          ? IconButton(
                              icon: const Icon(LucideIcons.x),
                              onPressed: () {
                                _searchController.clear();
                                _onSearchChanged('');
                              },
                            )
                          : null,
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(12),
                      ),
                      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                    ),
                  ),
                ),

                // Health Insurance Filter Dropdown
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
                  decoration: BoxDecoration(
                    border: Border.all(color: theme.dividerColor),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: DropdownButtonHideUnderline(
                    child: DropdownButton<String>(
                      value: _selectedInsurance,
                      icon: const Icon(LucideIcons.shieldCheck, size: 18),
                      hint: const Text('Filtrar Convênio'),
                      items: _insuranceFilters.map((ins) {
                        return DropdownMenuItem<String>(
                          value: ins,
                          child: Text(ins == 'TODOS' ? 'Todos os Convênios' : ins),
                        );
                      }).toList(),
                      onChanged: (val) {
                        if (val != null) {
                          setState(() {
                            _selectedInsurance = val;
                            _currentPage = 1;
                          });
                        }
                      },
                    ),
                  ),
                ),
              ],
            ),
          ),

          // Patients List View
          Expanded(
            child: patientsAsync.when(
              data: (patients) {
                if (patients.isEmpty) {
                  return Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(LucideIcons.users, size: 64, color: theme.disabledColor),
                        const SizedBox(height: 16),
                        Text(
                          'Nenhum paciente encontrado.',
                          style: TextStyle(
                            color: theme.textTheme.bodyMedium?.color,
                            fontSize: 16,
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                        const SizedBox(height: 8),
                        Text(
                          _searchQuery.isNotEmpty
                              ? 'Tente alterar os termos de busca ou o filtro de convênio.'
                              : 'Clique em "Novo Paciente" para cadastrar o primeiro cliente.',
                          style: TextStyle(color: theme.textTheme.bodySmall?.color),
                        ),
                      ],
                    ),
                  );
                }

                return ListView.builder(
                  padding: const EdgeInsets.symmetric(horizontal: 20.0, vertical: 8.0),
                  itemCount: patients.length,
                  itemBuilder: (context, index) {
                    final p = patients[index];
                    return _buildPatientCard(context, p, theme);
                  },
                );
              },
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (err, stack) => Center(
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    const Icon(LucideIcons.alertTriangle, color: Colors.amber, size: 48),
                    const SizedBox(height: 12),
                    Text('Erro ao carregar lista de pacientes: $err'),
                    const SizedBox(height: 12),
                    ElevatedButton.icon(
                      onPressed: () => ref.invalidate(patientsPaginatedProvider),
                      icon: const Icon(LucideIcons.refreshCw),
                      label: const Text('Tentar Novamente'),
                    ),
                  ],
                ),
              ),
            ),
          ),

          // Pagination Footer Bar
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
            decoration: BoxDecoration(
              color: theme.cardColor,
              border: Border(top: BorderSide(color: theme.dividerColor)),
            ),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  'Página $_currentPage',
                  style: TextStyle(fontWeight: FontWeight.bold, color: theme.textTheme.bodyMedium?.color),
                ),
                Row(
                  children: [
                    OutlinedButton.icon(
                      onPressed: _currentPage > 1
                          ? () {
                              setState(() {
                                _currentPage--;
                              });
                            }
                          : null,
                      icon: const Icon(LucideIcons.chevronLeft, size: 18),
                      label: const Text('Anterior'),
                    ),
                    const SizedBox(width: 12),
                    OutlinedButton.icon(
                      onPressed: patientsAsync.maybeWhen(
                        data: (list) => list.length >= 20,
                        orElse: () => false,
                      )
                          ? () {
                              setState(() {
                                _currentPage++;
                              });
                            }
                          : null,
                      icon: const Icon(LucideIcons.chevronRight, size: 18),
                      label: const Text('Próxima'),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildPatientCard(BuildContext context, Patient p, ThemeData theme) {
    return Card(
      margin: const EdgeInsets.only(bottom: 12.0),
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: BorderSide(
          color: theme.dividerColor,
          width: 1,
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 12.0),
        child: Row(
          children: [
            // Initials Avatar
            CircleAvatar(
              radius: 24,
              backgroundColor: theme.colorScheme.primary.withValues(alpha: 0.15),
              child: Text(
                p.initials,
                style: TextStyle(
                  fontWeight: FontWeight.bold,
                  color: theme.colorScheme.primary,
                  fontSize: 16,
                ),
              ),
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Flexible(
                        child: Text(
                          p.name,
                          style: TextStyle(
                            fontWeight: FontWeight.bold,
                            fontSize: 16,
                            color: theme.textTheme.titleLarge?.color ?? theme.colorScheme.onSurface,
                          ),
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                      if (p.healthInsurance != null && p.healthInsurance!.isNotEmpty) ...[
                        const SizedBox(width: 8),
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                          decoration: BoxDecoration(
                            color: Colors.blue.withValues(alpha: 0.1),
                            borderRadius: BorderRadius.circular(8),
                            border: Border.all(color: Colors.blue.withValues(alpha: 0.3)),
                          ),
                          child: Text(
                            p.healthInsurance!,
                            style: const TextStyle(fontSize: 11, color: Colors.blue, fontWeight: FontWeight.w600),
                          ),
                        ),
                      ],
                    ],
                  ),
                  const SizedBox(height: 4),
                  Wrap(
                    spacing: 16,
                    children: [
                      if (p.cpf != null && p.cpf!.isNotEmpty)
                        Text(
                          'CPF: ${p.cpf}',
                          style: TextStyle(fontSize: 13, color: theme.textTheme.bodyMedium?.color),
                        ),
                      if (p.phone != null && p.phone!.isNotEmpty)
                        Text(
                          'Tel: ${p.phone}',
                          style: TextStyle(fontSize: 13, color: theme.textTheme.bodyMedium?.color),
                        ),
                      if (p.email != null && p.email!.isNotEmpty)
                        Text(
                          'Email: ${p.email}',
                          style: TextStyle(fontSize: 13, color: theme.textTheme.bodyMedium?.color),
                        ),
                    ],
                  ),
                ],
              ),
            ),
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                IconButton(
                  icon: const Icon(LucideIcons.stethoscope, color: Color(0xFF2563EB)),
                  tooltip: 'Prontuário Eletrônico (EMR)',
                  onPressed: () {
                    context.go('/patients/${p.id}/emr');
                  },
                ),
                IconButton(
                  icon: Icon(LucideIcons.edit, color: theme.iconTheme.color),
                  tooltip: 'Editar Cadastro',
                  onPressed: () async {
                    final res = await showDialog<bool>(
                      context: context,
                      builder: (_) => PatientFormDialog(patient: p),
                    );
                    if (res == true) {
                      ref.invalidate(patientsPaginatedProvider);
                    }
                  },
                ),
                IconButton(
                  icon: const Icon(LucideIcons.trash2, size: 20, color: Color(0xFFEF4444)),
                  tooltip: 'Excluir Paciente',
                  onPressed: () async {
                    final confirmed = await showPasswordConfirmationDialog(
                      context: context,
                      title: 'Excluir Paciente',
                      message:
                          'Ao excluir este paciente, ele irá para a lixeira (Soft Delete) e poderá ser restaurado depois. Para confirmar, digite sua senha:',
                      onConfirm: (password) async {
                        await ref.read(patientsRepositoryProvider).deletePatient(p.id);
                      },
                    );

                    if (confirmed == true) {
                      ref.invalidate(patientsPaginatedProvider);
                    }
                  },
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
