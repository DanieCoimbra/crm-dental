import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_quill/flutter_quill.dart' as quill;
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:frontend_flutter/features/patients/data/patients_provider.dart';
import 'package:frontend_flutter/features/patients/data/patients_repository.dart';
import 'package:frontend_flutter/shared/widgets/require_role.dart';
import 'package:frontend_flutter/features/patients/presentation/widgets/patient_summary_tab.dart';
import 'package:frontend_flutter/features/patients/presentation/widgets/patient_files_tab.dart';

class PatientEmrScreen extends ConsumerStatefulWidget {
  final int patientId;
  const PatientEmrScreen({super.key, required this.patientId});

  @override
  ConsumerState<PatientEmrScreen> createState() => _PatientEmrScreenState();
}

class _PatientEmrScreenState extends ConsumerState<PatientEmrScreen> {
  // Evolução
  quill.QuillController? _controller;
  bool _isSaving = false;


  void _initEditor(String? initialData) {
    if (_controller != null) return;
    
    if (initialData != null && initialData.isNotEmpty) {
      try {
        final doc = quill.Document.fromJson(jsonDecode(initialData));
        _controller = quill.QuillController(
          document: doc,
          selection: const TextSelection.collapsed(offset: 0),
        );
      } catch (e) {
        // Fallback for plain text if jsonDecode fails
        _controller = quill.QuillController.basic();
        _controller!.document.insert(0, initialData);
      }
    } else {
      _controller = quill.QuillController.basic();
    }
  }


  Future<void> _saveEmr() async {
    if (_controller == null) return;
    
    setState(() {
      _isSaving = true;
    });

    try {
      final jsonStr = jsonEncode(_controller!.document.toDelta().toJson());
      final repo = ref.read(patientsRepositoryProvider);
      
      await repo.createEvolution(widget.patientId, jsonStr);
      
      if (mounted) {
        _controller!.clear(); // Limpa após salvar
        ref.invalidate(evolutionsProvider(widget.patientId));
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Evolução salva com sucesso! (Criptografado)')),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Erro ao salvar: $e')),
        );
      }
    } finally {
      if (mounted) {
        setState(() {
          _isSaving = false;
        });
      }
    }
  }


  @override
  Widget build(BuildContext context) {
    final patientAsync = ref.watch(patientDetailProvider(widget.patientId));

    return Scaffold(
      appBar: AppBar(
        title: const Text('Prontuário Eletrônico (EMR)'),
      ),
      body: patientAsync.when(
        data: (patient) {
          // Initialize empty for new evolution
          _initEditor(null);

          return DefaultTabController(
            length: 3,
            child: Column(
              children: [
                // Patient Header
                Container(

                  padding: const EdgeInsets.all(24.0),
                  child: Row(
                    children: [
                      CircleAvatar(
                        radius: 32,
                        backgroundColor: Theme.of(context).dividerTheme.color,
                        child: Icon(LucideIcons.user, size: 32, color: Theme.of(context).textTheme.bodyMedium?.color),
                      ),
                      const SizedBox(width: 16),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              patient.name, 
                              style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold, color: Theme.of(context).textTheme.titleLarge?.color),
                              overflow: TextOverflow.ellipsis,
                            ),
                            const SizedBox(height: 4),
                            Text(
                              'CPF: ${patient.cpf ?? 'Não informado'} | Nasc: ${patient.birthDate?.substring(0, 10) ?? 'Não informado'}',
                              style: TextStyle(color: Theme.of(context).textTheme.bodyMedium?.color),
                              overflow: TextOverflow.ellipsis,
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
                TabBar(
                  labelColor: Theme.of(context).colorScheme.primary,
                  unselectedLabelColor: Theme.of(context).textTheme.bodyMedium?.color,
                  indicatorColor: Theme.of(context).colorScheme.primary,
                  tabs: const [
                    Tab(text: 'Resumo'),
                    Tab(text: 'Evolução Clínica'),
                    Tab(text: 'Arquivos'),
                  ],
                ),
                const Divider(height: 1),
                // TabBarView
                Expanded(
                  child: TabBarView(
                    children: [
                      // TAB 1: Resumo
                      patientAsync.when(
                        data: (patient) => PatientSummaryTab(patient: patient),
                        loading: () => const Center(child: CircularProgressIndicator()),
                        error: (e, _) => Center(child: Text('Erro: $e')),
                      ),
                      
                      // TAB 2: Evolução Clínica
                      RequireRole(
                        allowedRoles: const ['admin', 'owner', 'doctor'],
                        fallback: Center(
                          child: Column(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              Icon(LucideIcons.lock, size: 64, color: Colors.red.shade300),
                              const SizedBox(height: 16),
                              const Text('Acesso Restrito', style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold)),
                              const SizedBox(height: 8),
                              const Text('Apenas médicos possuem acesso à Evolução Clínica (LGPD).'),
                            ],
                          ),
                        ),
                        child: Padding(
                          padding: const EdgeInsets.all(16.0),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: [
                              Row(
                                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                                children: [
                                  const Text('Nova Evolução', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                                  OutlinedButton.icon(
                                    onPressed: () {
                                      showDialog(
                                        context: context,
                                        builder: (context) => _EvolutionsHistoryDialog(patientId: widget.patientId),
                                      );
                                    },
                                    icon: const Icon(LucideIcons.history),
                                    label: const Text('Ver Histórico'),
                                  ),
                                ],
                              ),
                              const SizedBox(height: 8),
                              quill.QuillSimpleToolbar(
                                controller: _controller!,
                              ),
                              Expanded(
                                child: Container(
                                  padding: const EdgeInsets.all(16.0),
                                  color: Theme.of(context).scaffoldBackgroundColor,
                                  child: Card(
                                    elevation: 0,
                                    shape: RoundedRectangleBorder(
                                      borderRadius: BorderRadius.circular(12),
                                      side: BorderSide(color: Theme.of(context).dividerTheme.color ?? Colors.grey.shade200),
                                    ),
                                    child: Padding(
                                      padding: const EdgeInsets.all(16.0),
                                      child: quill.QuillEditor.basic(
                                        controller: _controller!,
                                      ),
                                    ),
                                  ),
                                ),
                              ),
                              const SizedBox(height: 16),
                              ElevatedButton.icon(
                                onPressed: _isSaving ? null : _saveEmr,
                                icon: _isSaving ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2)) : const Icon(LucideIcons.save),
                                label: const Text('Salvar Evolução'),
                              ),
                            ],
                          ),
                        ),
                      ),

                      // TAB 3: Arquivos
                      PatientFilesTab(patientId: widget.patientId),
                    ],
                  ),
                ),
              ],
            ),
          );
        },
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (err, stack) => Center(child: Text('Erro ao carregar: $err')),
      ),
    );
  }
}

class _EvolutionsHistoryDialog extends ConsumerWidget {
  final int patientId;
  const _EvolutionsHistoryDialog({required this.patientId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final evolutionsAsync = ref.watch(evolutionsProvider(patientId));

    return Dialog(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Container(
        width: 800,
        height: 600,
        padding: const EdgeInsets.all(24.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                const Text('Histórico de Evoluções', style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
                Semantics(
                  label: 'Fechar histórico de evoluções',
                  button: true,
                  child: IconButton(
                    icon: const Icon(LucideIcons.x),
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                ),
              ],
            ),
            const Divider(),
            const SizedBox(height: 16),
            Expanded(
              child: evolutionsAsync.when(
                data: (evolutions) {
                  if (evolutions.isEmpty) {
                    return const Center(child: Text('Nenhuma evolução encontrada.'));
                  }
                  return ListView.builder(
                    itemCount: evolutions.length,
                    itemBuilder: (context, index) {
                      final ev = evolutions[index];
                      quill.QuillController? readOnlyController;
                      try {
                        final doc = quill.Document.fromJson(jsonDecode(ev.contentHtml));
                        readOnlyController = quill.QuillController(
                          document: doc,
                          selection: const TextSelection.collapsed(offset: 0),
                          readOnly: true,
                        );
                      } catch (_) {}

                      return Card(
                        margin: const EdgeInsets.only(bottom: 16),
                        child: Padding(
                          padding: const EdgeInsets.all(16.0),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Row(
                                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                                children: [
                                  Text(
                                    '${ev.createdAt.day.toString().padLeft(2, '0')}/${ev.createdAt.month.toString().padLeft(2, '0')}/${ev.createdAt.year}',
                                    style: TextStyle(fontWeight: FontWeight.bold, color: Theme.of(context).colorScheme.primary),
                                  ),
                                  Text(ev.userName ?? 'Médico', style: TextStyle(color: Theme.of(context).textTheme.bodySmall?.color)),
                                ],
                              ),
                              const Divider(),
                              if (readOnlyController != null)
                                quill.QuillEditor.basic(
                                  controller: readOnlyController,
                                )
                              else
                                SelectableText(ev.contentHtml),
                            ],
                          ),
                        ),
                      );
                    },
                  );
                },
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (e, _) => Text('Erro: $e'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

