import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:frontend_flutter/features/patients/data/patient_model.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/patients/data/patients_provider.dart';

class PatientSummaryTab extends ConsumerWidget {
  final Patient patient;
  
  const PatientSummaryTab({super.key, required this.patient});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final evolutionsAsync = ref.watch(evolutionsProvider(patient.id));
    
    return Padding(
      padding: const EdgeInsets.all(24.0),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            flex: 1,
            child: Card(
              child: Padding(
                padding: const EdgeInsets.all(24.0),
                child: SingleChildScrollView(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text('Dados do Paciente', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                      const Divider(),
                      const SizedBox(height: 16),
                      _buildInfoRow(LucideIcons.user, 'Nome', patient.name),
                      _buildInfoRow(LucideIcons.mail, 'E-mail', patient.email ?? '-'),
                      _buildInfoRow(LucideIcons.phone, 'Telefone', patient.phone ?? '-'),
                      _buildInfoRow(LucideIcons.cake, 'Idade', _calculateAge(patient.birthDate)),
                    ],
                  ),
                ),
              ),
            ),
          ),
          const SizedBox(width: 24),
          Expanded(
            flex: 2,
            child: Card(
              child: Padding(
                padding: const EdgeInsets.all(24.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text('Últimas Evoluções', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                    const Divider(),
                    Expanded(
                      child: evolutionsAsync.when(
                        data: (evolutions) {
                          if (evolutions.isEmpty) {
                            return const Center(child: Text('Nenhuma evolução clínica registrada.'));
                          }
                          return ListView.builder(
                            itemCount: evolutions.length > 3 ? 3 : evolutions.length,
                            itemBuilder: (context, index) {
                              final ev = evolutions[index];
                              return ListTile(
                                leading: const Icon(LucideIcons.history, color: Colors.blue),
                                title: Text('Dr(a). ${ev.userName ?? '-'}'),
                                subtitle: Text('${ev.createdAt.day.toString().padLeft(2, '0')}/${ev.createdAt.month.toString().padLeft(2, '0')}/${ev.createdAt.year}'),
                              );
                            }
                          );
                        },
                        loading: () => const Center(child: CircularProgressIndicator()),
                        error: (e, _) => Center(child: Text('Erro: $e')),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  String _calculateAge(String? birthDate) {
    if (birthDate == null || birthDate.isEmpty) return '-';
    try {
      final DateTime birth = DateTime.parse(birthDate);
      final DateTime today = DateTime.now();
      int age = today.year - birth.year;
      if (today.month < birth.month || (today.month == birth.month && today.day < birth.day)) {
        age--;
      }
      return '$age anos';
    } catch (e) {
      return '-';
    }
  }

  Widget _buildInfoRow(IconData icon, String label, String value) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 16.0),
      child: Row(
        children: [
          Icon(icon, color: Colors.grey, size: 20),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(label, style: const TextStyle(fontSize: 12, color: Colors.grey)),
                SelectableText(
                  value, 
                  style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w500),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
