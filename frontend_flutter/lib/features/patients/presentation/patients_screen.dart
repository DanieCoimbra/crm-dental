import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:frontend_flutter/features/patients/data/patients_provider.dart';
import 'package:frontend_flutter/features/patients/data/patients_repository.dart';
import 'package:frontend_flutter/shared/widgets/password_confirmation_dialog.dart';
import 'package:frontend_flutter/features/patients/presentation/widgets/patient_form_dialog.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

class PatientsScreen extends ConsumerStatefulWidget {
  const PatientsScreen({super.key});

  @override
  ConsumerState<PatientsScreen> createState() => _PatientsScreenState();
}

class _PatientsScreenState extends ConsumerState<PatientsScreen> {
  String _searchQuery = '';

  @override
  Widget build(BuildContext context) {
    final patientsAsync = ref.watch(patientsListProvider(_searchQuery));
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Pacientes'),
        scrolledUnderElevation: 0,
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () {
          showDialog(
            context: context,
            builder: (_) => const PatientFormDialog(),
          );
        },
        icon: const Icon(LucideIcons.plus),
        label: const Text('Novo Paciente'),
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(20.0),
            child: TextField(
              decoration: const InputDecoration(
                labelText: 'Buscar Paciente por Nome, CPF ou E-mail',
                prefixIcon: Icon(LucideIcons.search),
              ),
              onChanged: (value) {
                setState(() {
                  _searchQuery = value;
                });
              },
            ),
          ),
          Expanded(
            child: patientsAsync.when(
              data: (patients) {
                if (patients.isEmpty) {
                  return Center(
                    child: Text(
                      'Nenhum paciente encontrado.',
                      style: TextStyle(color: theme.textTheme.bodyMedium?.color, fontSize: 16),
                    ),
                  );
                }
                return ListView.builder(
                  padding: const EdgeInsets.symmetric(horizontal: 20.0, vertical: 8.0),
                  itemCount: patients.length,
                  itemBuilder: (context, index) {
                    final p = patients[index];
                    return Card(
                      margin: const EdgeInsets.only(bottom: 12.0),
                      elevation: 0,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(16),
                        side: BorderSide(
                          color: theme.dividerTheme.color ?? theme.colorScheme.outline,
                          width: 1,
                        ),
                      ),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 12.0),
                        child: Row(
                          children: [
                            Container(
                              width: 44,
                              height: 44,
                              decoration: BoxDecoration(
                                color: const Color(0xFF2563EB).withValues(alpha: 0.15),
                                shape: BoxShape.circle,
                                border: Border.all(color: const Color(0xFF2563EB).withValues(alpha: 0.3), width: 1),
                              ),
                              child: const Icon(LucideIcons.user, color: Color(0xFF2563EB), size: 22),
                            ),
                            const SizedBox(width: 16),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    p.name,
                                    style: TextStyle(
                                      fontWeight: FontWeight.bold,
                                      fontSize: 16,
                                      color: theme.textTheme.titleLarge?.color ?? theme.colorScheme.onSurface,
                                    ),
                                    overflow: TextOverflow.ellipsis,
                                  ),
                                  const SizedBox(height: 4),
                                  Text(
                                    p.email ?? 'Sem e-mail cadastrado',
                                    style: TextStyle(
                                      fontSize: 13,
                                      color: theme.textTheme.bodyMedium?.color ?? theme.colorScheme.onSurfaceVariant,
                                    ),
                                    overflow: TextOverflow.ellipsis,
                                  ),
                                ],
                              ),
                            ),
                            Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Semantics(
                                  label: 'Abrir prontuário de ${p.name}',
                                  button: true,
                                  child: IconButton(
                                    icon: const Icon(LucideIcons.fileText, color: Color(0xFF2563EB)),
                                    tooltip: 'Prontuário (EMR)',
                                    onPressed: () {
                                      context.go('/patients/${p.id}/emr');
                                    },
                                  ),
                                ),
                                Semantics(
                                  label: 'Editar paciente ${p.name}',
                                  button: true,
                                  child: IconButton(
                                    icon: Icon(LucideIcons.edit, color: theme.iconTheme.color),
                                    tooltip: 'Editar',
                                    onPressed: () {
                                      showDialog(
                                        context: context,
                                        builder: (_) => PatientFormDialog(patient: p),
                                      );
                                    },
                                  ),
                                ),
                                Semantics(
                                  label: 'Excluir paciente ${p.name}',
                                  button: true,
                                  child: IconButton(
                                    icon: const Icon(LucideIcons.trash2, size: 20, color: Color(0xFFEF4444)),
                                    tooltip: 'Excluir',
                                    onPressed: () async {
                                      final confirmed = await showPasswordConfirmationDialog(
                                        context: context,
                                        title: 'Excluir Paciente',
                                        message: 'Ao excluir este paciente, ele irá para a lixeira (Soft Delete) e poderá ser restaurado depois. Para confirmar, digite sua senha de administrador:',
                                        onConfirm: (password) async {
                                          await ref.read(patientsRepositoryProvider).deletePatient(p.id);
                                        },
                                      );

                                      if (confirmed == true) {
                                        ref.invalidate(patientsListProvider);
                                      }
                                    },
                                  ),
                                ),
                              ],
                            ),
                          ],
                        ),
                      ),
                    );
                  },
                );
              },
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (err, stack) => Center(child: Text('Erro: $err')),
            ),
          ),
        ],
      ),
    );
  }
}

