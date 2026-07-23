import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:file_picker/file_picker.dart';
import 'package:frontend_flutter/features/patients/data/patients_provider.dart';
import 'package:frontend_flutter/features/patients/data/patients_repository.dart';

class PatientFilesTab extends ConsumerWidget {
  final int patientId;

  const PatientFilesTab({super.key, required this.patientId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final filesAsync = ref.watch(patientFilesProvider(patientId));

    return Padding(
      padding: const EdgeInsets.all(16.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              const Text('Arquivos e Exames', style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
              ElevatedButton.icon(
                onPressed: () async {
                  FilePickerResult? result = await FilePicker.pickFiles(
                    type: FileType.custom,
                    allowedExtensions: ['jpg', 'jpeg', 'png', 'pdf'],
                    withData: true,
                  );
                  if (result != null && result.files.isNotEmpty) {
                    try {
                      await ref.read(patientsRepositoryProvider).uploadPatientFile(
                        patientId, 
                        result.files.single, 
                        'Geral',
                      );
                      ref.invalidate(patientFilesProvider(patientId));
                      if (context.mounted) {
                        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Arquivo enviado com sucesso!')));
                      }
                    } catch (e) {
                      if (context.mounted) {
                        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Erro: $e')));
                      }
                    }
                  }
                },
                icon: const Icon(Icons.upload_file),
                label: const Text('Fazer Upload'),
              ),
            ],
          ),
          const SizedBox(height: 16),
          Expanded(
            child: filesAsync.when(
              data: (files) {
                if (files.isEmpty) {
                  return const Center(child: Text('Nenhum arquivo encontrado.'));
                }
                return GridView.builder(
                  gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                    crossAxisCount: 4,
                    crossAxisSpacing: 16,
                    mainAxisSpacing: 16,
                  ),
                  itemCount: files.length,
                  itemBuilder: (context, index) {
                    final file = files[index];
                    return Card(
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Icon(Icons.insert_drive_file, size: 48, color: Colors.blue.shade300),
                          const SizedBox(height: 8),
                          Padding(
                            padding: const EdgeInsets.symmetric(horizontal: 8.0),
                            child: Text(file.fileName, textAlign: TextAlign.center, maxLines: 2, overflow: TextOverflow.ellipsis),
                          ),
                          const SizedBox(height: 8),
                          Row(
                            mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                            children: [
                              TextButton(
                                onPressed: () async {
                                  try {
                                    await ref.read(patientsRepositoryProvider).openPatientFile(file.supabaseUrl);
                                  } catch (e) {
                                    if (context.mounted) {
                                      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Erro: $e')));
                                    }
                                  }
                                },
                                child: const Text('Visualizar'),
                              ),
                              TextButton(
                                onPressed: () async {
                                   try {
                                     await ref.read(patientsRepositoryProvider).deletePatientFile(file.id);
                                     ref.invalidate(patientFilesProvider(patientId));
                                   } catch (e) {
                                     if (context.mounted) {
                                       ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Erro ao excluir: $e')));
                                     }
                                   }
                                },
                                child: const Text('Excluir', style: TextStyle(color: Colors.red)),
                              )
                            ],
                          )
                        ],
                      ),
                    );
                  },
                );
              },
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (e, _) => Center(child: Text('Erro: $e')),
            ),
          ),
        ],
      ),
    );
  }
}
