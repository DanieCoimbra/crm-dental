import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/schedule/data/schedule_provider.dart';
import 'package:frontend_flutter/features/schedule/data/schedule_repository.dart';
import 'package:frontend_flutter/shared/widgets/dashboard_layout.dart';
import 'package:intl/intl.dart';
import 'package:frontend_flutter/features/schedule/presentation/widgets/shift_assignment_form_dialog.dart';

class ShiftAssignmentsScreen extends ConsumerStatefulWidget {
  const ShiftAssignmentsScreen({super.key});

  @override
  ConsumerState<ShiftAssignmentsScreen> createState() => _ShiftAssignmentsScreenState();
}

class _ShiftAssignmentsScreenState extends ConsumerState<ShiftAssignmentsScreen> {
  Future<void> _deleteShift(int id) async {
    final confirm = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Confirmar exclusão'),
        content: const Text('Deseja realmente remover este turno?'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancelar')),
          TextButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Excluir')),
        ],
      ),
    );

    if (confirm != true) return;

    try {
      await ref.read(scheduleRepositoryProvider).deleteShiftAssignment(id);
      ref.invalidate(shiftAssignmentsProvider);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Turno removido com sucesso')));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Erro: $e')));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final shiftsAsync = ref.watch(shiftAssignmentsProvider);

    return DashboardLayout(
      child: Card(
        margin: const EdgeInsets.all(24),
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Text('Gerenciamento de Turnos', style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
                  ElevatedButton.icon(
                    onPressed: () {
                      showDialog(
                        context: context,
                        builder: (_) => const ShiftAssignmentFormDialog(),
                      );
                    },
                    icon: const Icon(Icons.add),
                    label: const Text('Novo Turno'),
                  )
                ],
              ),
              const SizedBox(height: 24),
              Expanded(
                child: shiftsAsync.when(
                  loading: () => const Center(child: CircularProgressIndicator()),
                  error: (err, stack) => Center(child: Text('Erro ao carregar turnos: $err')),
                  data: (shifts) {
                    if (shifts.isEmpty) {
                      return const Center(child: Text('Nenhum turno cadastrado.'));
                    }
                    return ListView.builder(
                      itemCount: shifts.length,
                      itemBuilder: (context, index) {
                        final shift = shifts[index];
                        final dateStr = shift.date != null ? DateFormat('dd/MM/yyyy').format(shift.date!) : 'Data não definida';
                        final shiftName = shift.shift == 'morning' ? 'Manhã' : 'Tarde';
                        
                        return ListTile(
                          leading: const CircleAvatar(child: Icon(Icons.access_time)),
                          title: Text('Dr(a). ${shift.doctor?.name ?? 'Desconhecido'} - $shiftName'),
                          subtitle: Text('Data: $dateStr | Sala: ${shift.room?.name ?? 'Não definida'}'),
                          trailing: IconButton(
                            icon: const Icon(Icons.delete, color: Colors.red),
                            onPressed: () => _deleteShift(shift.id),
                          ),
                        );
                      },
                    );
                  },
                ),
              )
            ],
          ),
        ),
      ),
    );
  }
}
