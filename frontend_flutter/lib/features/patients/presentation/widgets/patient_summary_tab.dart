import 'package:flutter/material.dart';

class PatientSummaryTab extends StatelessWidget {
  final String? patientId;
  final dynamic patient;

  const PatientSummaryTab({
    super.key,
    this.patientId,
    required this.patient,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(16.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Resumo do Paciente', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 12),
          ListTile(
            title: const Text('Nome'),
            subtitle: Text((patient is Map ? patient['name'] : patient?.name) ?? 'N/A'),
          ),
          ListTile(
            title: const Text('E-mail'),
            subtitle: Text((patient is Map ? patient['email'] : patient?.email) ?? 'N/A'),
          ),
          ListTile(
            title: const Text('Telefone'),
            subtitle: Text((patient is Map ? patient['phone'] : patient?.phone) ?? 'N/A'),
          ),
        ],
      ),
    );
  }
}
