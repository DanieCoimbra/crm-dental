import 'package:flutter/material.dart';

class PatientFilesTab extends StatelessWidget {
  final dynamic patientId;

  const PatientFilesTab({super.key, required this.patientId});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(16.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Arquivos e Exames do Paciente ID: $patientId', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 16),
          const Center(child: Text('Nenhum arquivo anexo.')),
        ],
      ),
    );
  }
}
