import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_quill/flutter_quill.dart' as quill;
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:frontend_flutter/features/patients/data/clinical_evolution_model.dart';
import 'package:frontend_flutter/features/patients/data/patients_provider.dart';
import 'package:frontend_flutter/features/patients/data/patients_repository.dart';

class ClinicalEvolutionTab extends ConsumerStatefulWidget {
  final int patientId;

  const ClinicalEvolutionTab({super.key, required this.patientId});

  @override
  ConsumerState<ClinicalEvolutionTab> createState() => _ClinicalEvolutionTabState();
}

class _ClinicalEvolutionTabState extends ConsumerState<ClinicalEvolutionTab> {
  final _formKey = GlobalKey<FormState>();
  late TextEditingController _chiefComplaintController;
  late TextEditingController _diagnosisController;
  late TextEditingController _procedureSummaryController;
  quill.QuillController? _quillController;
  bool _isSaving = false;
  DateTime _attendanceDate = DateTime.now();

  @override
  void initState() {
    super.initState();
    _chiefComplaintController = TextEditingController();
    _diagnosisController = TextEditingController();
    _procedureSummaryController = TextEditingController();
    _quillController = quill.QuillController.basic();
  }

  @override
  void dispose() {
    _chiefComplaintController.dispose();
    _diagnosisController.dispose();
    _procedureSummaryController.dispose();
    _quillController?.dispose();
    super.dispose();
  }

  Future<void> _selectAttendanceDate() async {
    final picked = await showDatePicker(
      context: context,
      initialDate: _attendanceDate,
      firstDate: DateTime(2000),
      lastDate: DateTime.now().add(const Duration(days: 1)),
    );
    if (picked != null) {
      setState(() {
        _attendanceDate = DateTime(
          picked.year,
          picked.month,
          picked.day,
          _attendanceDate.hour,
          _attendanceDate.minute,
        );
      });
    }
  }

  Future<void> _submitEvolution() async {
    if (!_formKey.currentState!.validate()) return;

    setState(() {
      _isSaving = true;
    });

    try {
      final quillJson = jsonEncode(_quillController!.document.toDelta().toJson());
      final procedureText = _procedureSummaryController.text.trim();

      final repo = ref.read(patientsRepositoryProvider);
      await repo.createEvolution(
        widget.patientId,
        procedureSummary: procedureText.isNotEmpty ? procedureText : 'Evolução registrada via editor',
        chiefComplaint: _chiefComplaintController.text.trim(),
        diagnosis: _diagnosisController.text.trim(),
        contentHtml: quillJson,
        attendanceDate: _attendanceDate,
      );

      ref.invalidate(evolutionsProvider(widget.patientId));

      if (mounted) {
        _chiefComplaintController.clear();
        _diagnosisController.clear();
        _procedureSummaryController.clear();
        _quillController?.clear();
        setState(() {
          _attendanceDate = DateTime.now();
        });

        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Evolução clínica registrada com sucesso!'),
            backgroundColor: Colors.green,
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Erro ao salvar evolução: $e'),
            backgroundColor: Colors.red,
          ),
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
    final evolutionsAsync = ref.watch(evolutionsProvider(widget.patientId));
    final theme = Theme.of(context);

    return Scaffold(
      backgroundColor: theme.scaffoldBackgroundColor,
      body: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Left Panel: Form to Record New Clinical Evolution
          SizedBox(
            width: 480,
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(20.0),
              child: Form(
                key: _formKey,
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
                            const Icon(LucideIcons.filePlus, color: Color(0xFF2563EB)),
                            const SizedBox(width: 10),
                            Text(
                              'Nova Evolução Clínica',
                              style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
                            ),
                          ],
                        ),
                        const Divider(height: 24),

                        // Attendance Date Picker
                        Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            Text(
                              'Data do Atendimento:',
                              style: TextStyle(fontWeight: FontWeight.w600, color: theme.textTheme.bodyMedium?.color),
                            ),
                            OutlinedButton.icon(
                              onPressed: _selectAttendanceDate,
                              icon: const Icon(LucideIcons.calendar, size: 16),
                              label: Text(
                                '${_attendanceDate.day.toString().padLeft(2, '0')}/${_attendanceDate.month.toString().padLeft(2, '0')}/${_attendanceDate.year}',
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 16),

                        // Queixa Principal
                        TextFormField(
                          controller: _chiefComplaintController,
                          decoration: const InputDecoration(
                            labelText: 'Queixa Principal (Anamnese)',
                            hintText: 'Ex: Dor aguda no dente 16 ao mastigar...',
                            prefixIcon: Icon(LucideIcons.alertCircle),
                            border: OutlineInputBorder(),
                          ),
                        ),
                        const SizedBox(height: 14),

                        // Diagnóstico
                        TextFormField(
                          controller: _diagnosisController,
                          decoration: const InputDecoration(
                            labelText: 'Diagnóstico Odontológico',
                            hintText: 'Ex: Pulpite irreversível no dente 16...',
                            prefixIcon: Icon(LucideIcons.stethoscope),
                            border: OutlineInputBorder(),
                          ),
                        ),
                        const SizedBox(height: 14),

                        // Resumo de Procedimento Realizado
                        TextFormField(
                          controller: _procedureSummaryController,
                          maxLines: 3,
                          decoration: const InputDecoration(
                            labelText: 'Procedimentos Realizados *',
                            hintText: 'Descreva os procedimentos executados na consulta...',
                            alignLabelWithHint: true,
                            border: OutlineInputBorder(),
                          ),
                          validator: (val) {
                            if (val == null || val.trim().isEmpty) {
                              return 'Informe os procedimentos realizados';
                            }
                            return null;
                          },
                        ),
                        const SizedBox(height: 16),

                        // Extended Quill Rich Text Editor
                        Text(
                          'Detalhamento / Anotações Adicionais:',
                          style: TextStyle(fontWeight: FontWeight.w600, color: theme.textTheme.bodyMedium?.color),
                        ),
                        const SizedBox(height: 8),
                        quill.QuillSimpleToolbar(
                          controller: _quillController!,
                        ),
                        Container(
                          height: 140,
                          padding: const EdgeInsets.all(8),
                          decoration: BoxDecoration(
                            border: Border.all(color: theme.dividerColor),
                            borderRadius: BorderRadius.circular(8),
                          ),
                          child: quill.QuillEditor.basic(
                            controller: _quillController!,
                          ),
                        ),
                        const SizedBox(height: 20),

                        // Submit Button
                        SizedBox(
                          width: double.infinity,
                          height: 44,
                          child: ElevatedButton.icon(
                            onPressed: _isSaving ? null : _submitEvolution,
                            icon: _isSaving
                                ? const SizedBox(
                                    width: 18,
                                    height: 18,
                                    child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2),
                                  )
                                : const Icon(LucideIcons.save),
                            label: const Text('Salvar Evolução no Prontuário'),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),

          // Right Panel: Chronological Timeline History
          Expanded(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(20.0),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      const Icon(LucideIcons.history, color: Color(0xFF2563EB)),
                      const SizedBox(width: 10),
                      Text(
                        'Linha do Tempo Cronológica do Prontuário',
                        style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
                      ),
                    ],
                  ),
                  const SizedBox(height: 16),

                  evolutionsAsync.when(
                    data: (evolutions) {
                      if (evolutions.isEmpty) {
                        return Center(
                          child: Padding(
                            padding: const EdgeInsets.all(40.0),
                            child: Column(
                              children: [
                                Icon(LucideIcons.fileText, size: 64, color: theme.disabledColor),
                                const SizedBox(height: 16),
                                Text(
                                  'Nenhuma evolução clínica registrada ainda.',
                                  style: TextStyle(fontSize: 16, color: theme.textTheme.bodyMedium?.color),
                                ),
                                const SizedBox(height: 8),
                                const Text('Preencha o formulário ao lado para registrar o atendimento.'),
                              ],
                            ),
                          ),
                        );
                      }

                      return ListView.builder(
                        shrinkWrap: true,
                        physics: const NeverScrollableScrollPhysics(),
                        itemCount: evolutions.length,
                        itemBuilder: (ctx, idx) {
                          final ev = evolutions[idx];
                          return _buildTimelineCard(context, ev, theme);
                        },
                      );
                    },
                    loading: () => const Center(child: CircularProgressIndicator()),
                    error: (err, _) => Center(child: Text('Erro ao carregar prontuário: $err')),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildTimelineCard(BuildContext context, ClinicalEvolution ev, ThemeData theme) {
    quill.QuillController? readOnlyController;
    if (ev.contentHtml.isNotEmpty) {
      try {
        final doc = quill.Document.fromJson(jsonDecode(ev.contentHtml));
        readOnlyController = quill.QuillController(
          document: doc,
          selection: const TextSelection.collapsed(offset: 0),
          readOnly: true,
        );
      } catch (_) {}
    }

    return Stack(
      children: [
        // Vertical timeline connecting line
        Positioned(
          top: 0,
          bottom: 0,
          left: 18,
          child: Container(
            width: 2,
            color: theme.colorScheme.primary.withValues(alpha: 0.3),
          ),
        ),

        Padding(
          padding: const EdgeInsets.only(left: 40.0, bottom: 20.0),
          child: Card(
            elevation: 0,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(16),
              side: BorderSide(color: theme.dividerColor),
            ),
            child: Padding(
              padding: const EdgeInsets.all(16.0),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // Card Header: Date & Professional Name
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Row(
                        children: [
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                            decoration: BoxDecoration(
                              color: theme.colorScheme.primary.withValues(alpha: 0.15),
                              borderRadius: BorderRadius.circular(8),
                            ),
                            child: Text(
                              '${ev.attendanceDate.day.toString().padLeft(2, '0')}/${ev.attendanceDate.month.toString().padLeft(2, '0')}/${ev.attendanceDate.year}',
                              style: TextStyle(
                                fontWeight: FontWeight.bold,
                                color: theme.colorScheme.primary,
                              ),
                            ),
                          ),
                          const SizedBox(width: 12),
                          Text(
                            ev.userName ?? 'Profissional da Saúde',
                            style: const TextStyle(fontWeight: FontWeight.w600),
                          ),
                        ],
                      ),
                      IconButton(
                        icon: const Icon(LucideIcons.trash2, size: 18, color: Colors.red),
                        tooltip: 'Excluir registro',
                        onPressed: () async {
                          final confirm = await showDialog<bool>(
                            context: context,
                            builder: (ctx) => AlertDialog(
                              title: const Text('Excluir Evolução Clínica'),
                              content: const Text('Deseja excluir este registro de prontuário?'),
                              actions: [
                                TextButton(
                                  onPressed: () => Navigator.of(ctx).pop(false),
                                  child: const Text('Cancelar'),
                                ),
                                ElevatedButton(
                                  style: ElevatedButton.styleFrom(backgroundColor: Colors.red),
                                  onPressed: () => Navigator.of(ctx).pop(true),
                                  child: const Text('Excluir', style: TextStyle(color: Colors.white)),
                                ),
                              ],
                            ),
                          );

                          if (confirm == true) {
                            await ref.read(patientsRepositoryProvider).deleteEvolution(ev.id);
                            ref.invalidate(evolutionsProvider(widget.patientId));
                          }
                        },
                      ),
                    ],
                  ),
                  const Divider(height: 20),

                  // Queixa Principal (if present)
                  if (ev.chiefComplaint != null && ev.chiefComplaint!.isNotEmpty) ...[
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'Queixa Principal: ',
                          style: TextStyle(fontWeight: FontWeight.bold, color: Colors.amber),
                        ),
                        Expanded(child: Text(ev.chiefComplaint!)),
                      ],
                    ),
                    const SizedBox(height: 8),
                  ],

                  // Diagnóstico (if present)
                  if (ev.diagnosis != null && ev.diagnosis!.isNotEmpty) ...[
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'Diagnóstico: ',
                          style: TextStyle(fontWeight: FontWeight.bold, color: Colors.blue),
                        ),
                        Expanded(child: Text(ev.diagnosis!)),
                      ],
                    ),
                    const SizedBox(height: 8),
                  ],

                  // Procedimentos Realizados
                  const Text(
                    'Procedimento Realizado:',
                    style: TextStyle(fontWeight: FontWeight.bold),
                  ),
                  const SizedBox(height: 4),
                  Text(ev.procedureSummary),

                  // Quill Editor Delta view (if present)
                  if (readOnlyController != null) ...[
                    const SizedBox(height: 12),
                    Container(
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: theme.scaffoldBackgroundColor,
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(color: theme.dividerColor),
                      ),
                      child: quill.QuillEditor.basic(
                        controller: readOnlyController,
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ),

        // Timeline Dot Badge
        Positioned(
          top: 24,
          left: 10,
          child: Container(
            width: 18,
            height: 18,
            decoration: BoxDecoration(
              color: theme.colorScheme.primary,
              shape: BoxShape.circle,
              border: Border.all(color: Colors.white, width: 3),
            ),
          ),
        ),
      ],
    );
  }
}
