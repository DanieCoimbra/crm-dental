import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:frontend_flutter/features/patients/data/patient_model.dart';
import 'package:frontend_flutter/features/patients/presentation/widgets/patient_form_dialog.dart';

class PatientSummaryTab extends StatelessWidget {
  final Patient patient;

  const PatientSummaryTab({
    super.key,
    required this.patient,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return SingleChildScrollView(
      padding: const EdgeInsets.all(24.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                'Resumo & Ficha de Anamnese',
                style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
              ),
              OutlinedButton.icon(
                onPressed: () {
                  showDialog(
                    context: context,
                    builder: (_) => PatientFormDialog(patient: patient),
                  );
                },
                icon: const Icon(LucideIcons.edit, size: 16),
                label: const Text('Editar Dados'),
              ),
            ],
          ),
          const SizedBox(height: 20),

          // Main Info Cards Grid
          Wrap(
            spacing: 20,
            runSpacing: 20,
            children: [
              // Card 1: Informações Pessoais
              SizedBox(
                width: 380,
                child: Card(
                  elevation: 0,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                    side: BorderSide(color: theme.dividerColor),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.all(20.0),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            const Icon(LucideIcons.idCard, color: Color(0xFF2563EB)),
                            const SizedBox(width: 10),
                            Text('Dados Pessoais', style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.bold)),
                          ],
                        ),
                        const Divider(height: 20),
                        _buildInfoTile('Nome Completo', patient.name),
                        _buildInfoTile('CPF', patient.cpf ?? 'Não informado'),
                        _buildInfoTile('Data de Nascimento', patient.birthDate ?? 'Não informada'),
                        _buildInfoTile('Convênio', patient.healthInsurance ?? 'Particular'),
                      ],
                    ),
                  ),
                ),
              ),

              // Card 2: Contato e Endereço
              SizedBox(
                width: 380,
                child: Card(
                  elevation: 0,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                    side: BorderSide(color: theme.dividerColor),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.all(20.0),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            const Icon(LucideIcons.mapPin, color: Color(0xFF2563EB)),
                            const SizedBox(width: 10),
                            Text('Contato e Endereço', style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.bold)),
                          ],
                        ),
                        const Divider(height: 20),
                        _buildInfoTile('Telefone', patient.phone ?? 'Não informado'),
                        _buildInfoTile('E-mail', patient.email ?? 'Não informado'),
                        _buildInfoTile(
                          'Endereço',
                          patient.street != null && patient.street!.isNotEmpty
                              ? '${patient.street}, ${patient.number ?? 'S/N'} - ${patient.neighborhood ?? ''} (${patient.cep ?? ''})'
                              : 'Não informado',
                        ),
                      ],
                    ),
                  ),
                ),
              ),

              // Card 3: Observações de Anamnese & Alergias
              SizedBox(
                width: 780,
                child: Card(
                  elevation: 0,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                    side: BorderSide(color: theme.dividerColor),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.all(20.0),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            const Icon(LucideIcons.stethoscope, color: Color(0xFF2563EB)),
                            const SizedBox(width: 10),
                            Text('Observações & Histórico de Anamnese (Criptografado)', style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.bold)),
                          ],
                        ),
                        const Divider(height: 20),
                        Text(
                          patient.notes != null && patient.notes!.isNotEmpty
                              ? patient.notes!
                              : 'Nenhuma observação ou restrição médica cadastrada.',
                          style: const TextStyle(fontSize: 14, height: 1.5),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildInfoTile(String label, String value) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: const TextStyle(fontSize: 12, color: Colors.grey, fontWeight: FontWeight.w500)),
          const SizedBox(height: 2),
          Text(value, style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
        ],
      ),
    );
  }
}
