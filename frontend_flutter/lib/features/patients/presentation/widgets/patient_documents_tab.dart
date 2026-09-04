import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:printing/printing.dart';
import 'package:frontend_flutter/features/patients/data/patient_model.dart';
import 'package:frontend_flutter/features/patients/data/medical_document_model.dart';
import 'package:frontend_flutter/features/patients/data/medical_documents_provider.dart';
import 'package:frontend_flutter/features/patients/data/medical_documents_repository.dart';
import 'package:frontend_flutter/features/patients/utils/document_pdf_generator.dart';
import 'package:frontend_flutter/features/settings/data/settings_provider.dart';
import 'package:frontend_flutter/features/auth/providers/auth_provider.dart';

class PatientDocumentsTab extends ConsumerStatefulWidget {
  final Patient patient;

  const PatientDocumentsTab({
    super.key,
    required this.patient,
  });

  @override
  ConsumerState<PatientDocumentsTab> createState() => _PatientDocumentsTabState();
}

class _PatientDocumentsTabState extends ConsumerState<PatientDocumentsTab> {
  @override
  Widget build(BuildContext context) {
    final docsAsync = ref.watch(patientDocumentsProvider(widget.patient.id));
    final theme = Theme.of(context);

    return Scaffold(
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _showCreateDocumentDialog(context),
        icon: const Icon(LucideIcons.filePlus),
        label: const Text('Emitir Documento'),
      ),
      body: docsAsync.when(
        data: (docs) {
          if (docs.isEmpty) {
            return Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(LucideIcons.fileText, size: 64, color: theme.hintColor),
                  const SizedBox(height: 16),
                  Text(
                    'Nenhum documento emitido para este paciente.',
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                      color: theme.textTheme.titleMedium?.color,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'Emita atestados, receituários e encaminhamentos com geração em PDF.',
                    style: TextStyle(fontSize: 13, color: theme.hintColor),
                  ),
                  const SizedBox(height: 24),
                  ElevatedButton.icon(
                    onPressed: () => _showCreateDocumentDialog(context),
                    icon: const Icon(LucideIcons.plus),
                    label: const Text('Emitir Primeiro Documento'),
                  ),
                ],
              ),
            );
          }

          return SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      'Documentos Odontológicos Emitidos (${docs.length})',
                      style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.bold,
                        color: theme.textTheme.titleLarge?.color,
                      ),
                    ),
                    ElevatedButton.icon(
                      onPressed: () => _showCreateDocumentDialog(context),
                      icon: const Icon(LucideIcons.plus, size: 16),
                      label: const Text('Novo Documento'),
                    ),
                  ],
                ),
                const SizedBox(height: 16),
                ListView.separated(
                  shrinkWrap: true,
                  physics: const NeverScrollableScrollPhysics(),
                  itemCount: docs.length,
                  separatorBuilder: (context, index) => const SizedBox(height: 12),
                  itemBuilder: (context, index) {
                    final doc = docs[index];
                    return _buildDocumentCard(context, doc);
                  },
                ),
              ],
            ),
          );
        },
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (err, stack) => Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(LucideIcons.alertTriangle, size: 48, color: Colors.red),
              const SizedBox(height: 12),
              Text('Erro ao carregar documentos: $err'),
              const SizedBox(height: 16),
              ElevatedButton.icon(
                onPressed: () => ref.invalidate(patientDocumentsProvider(widget.patient.id)),
                icon: const Icon(LucideIcons.refreshCw),
                label: const Text('Tentar Novamente'),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildDocumentCard(BuildContext context, MedicalDocument doc) {
    final theme = Theme.of(context);
    IconData icon;
    Color iconBg;

    switch (doc.type.toUpperCase()) {
      case 'ATESTADO':
        icon = LucideIcons.fileCheck;
        iconBg = const Color(0xFF2563EB);
        break;
      case 'RECEITA':
        icon = LucideIcons.pill;
        iconBg = const Color(0xFF10B981);
        break;
      case 'ENCAMINHAMENTO':
        icon = LucideIcons.fileSymlink;
        iconBg = const Color(0xFF8B5CF6);
        break;
      default:
        icon = LucideIcons.fileText;
        iconBg = const Color(0xFF64748B);
    }

    return Card(
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: theme.dividerColor),
      ),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: iconBg.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(10),
              ),
              child: Icon(icon, color: iconBg, size: 24),
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Text(
                        doc.title,
                        style: TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.bold,
                          color: theme.textTheme.titleMedium?.color,
                        ),
                      ),
                      const SizedBox(width: 8),
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                        decoration: BoxDecoration(
                          color: iconBg.withValues(alpha: 0.1),
                          borderRadius: BorderRadius.circular(6),
                          border: Border.all(color: iconBg.withValues(alpha: 0.3)),
                        ),
                        child: Text(
                          doc.typeLabel,
                          style: TextStyle(
                            fontSize: 11,
                            fontWeight: FontWeight.bold,
                            color: iconBg,
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 4),
                  Text(
                    'Emitido em: ${doc.formattedDate}',
                    style: TextStyle(fontSize: 12, color: theme.hintColor),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    doc.content,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 13,
                      color: theme.textTheme.bodyMedium?.color,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 12),
            OutlinedButton.icon(
              onPressed: () => _openPdfPreview(context, doc),
              icon: const Icon(LucideIcons.printer, size: 16),
              label: const Text('Visualizar / Imprimir PDF'),
            ),
            const SizedBox(width: 8),
            IconButton(
              onPressed: () => _confirmDeleteDocument(doc),
              icon: const Icon(LucideIcons.trash2, size: 18, color: Colors.red),
              tooltip: 'Excluir Documento',
            ),
          ],
        ),
      ),
    );
  }

  void _openPdfPreview(BuildContext context, MedicalDocument doc) {
    showDialog(
      context: context,
      builder: (dialogCtx) => Dialog(
        insetPadding: const EdgeInsets.all(24),
        child: SizedBox(
          width: 800,
          height: 700,
          child: Scaffold(
            appBar: AppBar(
              title: Text('Visualizador PDF — ${doc.title}'),
              actions: [
                IconButton(
                  icon: const Icon(LucideIcons.x),
                  onPressed: () => Navigator.of(dialogCtx).pop(),
                ),
              ],
            ),
            body: PdfPreview(
              build: (format) async {
                final clinic = ref.read(myClinicProvider).value;
                final user = ref.read(currentUserProvider).value;
                final pdfBytes = await DocumentPdfGenerator.generateDocumentPdf(
                  patient: widget.patient,
                  document: doc,
                  clinic: clinic,
                  dentistName: user?.name,
                );
                return Uint8List.fromList(pdfBytes);
              },
              allowPrinting: true,
              allowSharing: true,
              canChangeOrientation: false,
              canChangePageFormat: false,
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _confirmDeleteDocument(MedicalDocument doc) async {
    final confirm = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Excluir Documento'),
        content: Text('Tem certeza que deseja excluir "${doc.title}"?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('Cancelar'),
          ),
          ElevatedButton(
            style: ElevatedButton.styleFrom(backgroundColor: Colors.red),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('Excluir'),
          ),
        ],
      ),
    );

    if (confirm == true) {
      try {
        final repo = ref.read(medicalDocumentsRepositoryProvider);
        await repo.deleteDocument(doc.id);
        ref.invalidate(patientDocumentsProvider(widget.patient.id));
        if (!mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Documento excluído com sucesso.')),
        );
      } catch (e) {
        if (!mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Erro ao excluir documento: $e'), backgroundColor: Colors.red),
        );
      }
    }
  }

  void _showCreateDocumentDialog(BuildContext context) {
    showDialog(
      context: context,
      builder: (ctx) => CreateDocumentDialog(
        patient: widget.patient,
        onDocumentCreated: (doc) {
          ref.invalidate(patientDocumentsProvider(widget.patient.id));
          _openPdfPreview(context, doc);
        },
      ),
    );
  }
}

class CreateDocumentDialog extends ConsumerStatefulWidget {
  final Patient patient;
  final Function(MedicalDocument) onDocumentCreated;

  const CreateDocumentDialog({
    super.key,
    required this.patient,
    required this.onDocumentCreated,
  });

  @override
  ConsumerState<CreateDocumentDialog> createState() => _CreateDocumentDialogState();
}

class _CreateDocumentDialogState extends ConsumerState<CreateDocumentDialog>
    with SingleTickerProviderStateMixin {
  late TabController _tabController;
  bool _isLoading = false;

  // Atestado fields
  final _atestadoDaysController = TextEditingController(text: '1');
  final _atestadoCidController = TextEditingController();
  final _atestadoReasonController = TextEditingController(
    text: 'Atesto para os devidos fins que o(a) paciente acima identificado(a) esteve sob meus cuidados odontológicos nesta data, necessitando de afastamento de suas atividades habituais.',
  );

  // Receituario fields
  final _receitaTitleController = TextEditingController(text: 'Receituário Odontológico');
  final _receitaMedicationsController = TextEditingController();
  final _receitaInstructionsController = TextEditingController();

  // Encaminhamento fields
  String _encaminhamentoSpecialty = 'Endodontia';
  final _encaminhamentoReasonController = TextEditingController();

  final List<String> _specialties = [
    'Endodontia',
    'Cirurgia Bucomaxilofacial',
    'Periodontia',
    'Ortodontia',
    'Odontopediatria',
    'Prótese Dentária',
    'Implantodontia',
    'Radiologia Odontológica',
    'Outra',
  ];

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 3, vsync: this);
  }

  @override
  void dispose() {
    _tabController.dispose();
    _atestadoDaysController.dispose();
    _atestadoCidController.dispose();
    _atestadoReasonController.dispose();
    _receitaTitleController.dispose();
    _receitaMedicationsController.dispose();
    _receitaInstructionsController.dispose();
    _encaminhamentoReasonController.dispose();
    super.dispose();
  }

  void _addQuickMedication(String med) {
    if (_receitaMedicationsController.text.isEmpty) {
      _receitaMedicationsController.text = med;
    } else {
      _receitaMedicationsController.text += '\n\n$med';
    }
  }

  Future<void> _submitAtestado() async {
    final daysStr = _atestadoDaysController.text.trim();
    final cid = _atestadoCidController.text.trim();
    final reason = _atestadoReasonController.text.trim();

    final days = int.tryParse(daysStr) ?? 1;

    final StringBuffer contentBuf = StringBuffer();
    contentBuf.writeln('Atesto para os devidos fins de direito que o(a) paciente ${widget.patient.name} esteve sob tratamento odontológico nesta data.');
    contentBuf.writeln('Necessita de $days dia(s) de repouso e afastamento das atividades laborais/escolares.');
    if (cid.isNotEmpty) {
      contentBuf.writeln('Diagnóstico (CID-10): $cid');
    }
    if (reason.isNotEmpty && !contentBuf.toString().contains(reason)) {
      contentBuf.writeln('\nObservações Clínicas:\n$reason');
    }

    await _saveAndClose(
      type: 'ATESTADO',
      title: 'Atestado Odontológico ($days dia${days > 1 ? 's' : ''})',
      content: contentBuf.toString(),
    );
  }

  Future<void> _submitReceita() async {
    final title = _receitaTitleController.text.trim();
    final meds = _receitaMedicationsController.text.trim();
    final instructions = _receitaInstructionsController.text.trim();

    if (meds.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Por favor, informe os medicamentos prescritos.')),
      );
      return;
    }

    final StringBuffer contentBuf = StringBuffer();
    contentBuf.writeln('USO INTERNO / PRESCRIÇÃO:\n');
    contentBuf.writeln(meds);
    if (instructions.isNotEmpty) {
      contentBuf.writeln('\nORIENTAÇÕES GERAIS AO PACIENTE:\n$instructions');
    }

    await _saveAndClose(
      type: 'RECEITA',
      title: title.isNotEmpty ? title : 'Receituário Médico',
      content: contentBuf.toString(),
    );
  }

  Future<void> _submitEncaminhamento() async {
    final reason = _encaminhamentoReasonController.text.trim();
    if (reason.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Por favor, descreva o motivo do encaminhamento.')),
      );
      return;
    }

    final StringBuffer contentBuf = StringBuffer();
    contentBuf.writeln('Ao(À) Ilmo(a). Dr(a). Especialista em $_encaminhamentoSpecialty,\n');
    contentBuf.writeln('Solicito a avaliação e conduta para o(a) paciente ${widget.patient.name}.\n');
    contentBuf.writeln('QUADRO CLÍNICO & MOTIVO DO ENCAMINHAMENTO:\n$reason');

    await _saveAndClose(
      type: 'ENCAMINHAMENTO',
      title: 'Encaminhamento - $_encaminhamentoSpecialty',
      content: contentBuf.toString(),
    );
  }

  Future<void> _saveAndClose({
    required String type,
    required String title,
    required String content,
  }) async {
    setState(() => _isLoading = true);
    try {
      final repo = ref.read(medicalDocumentsRepositoryProvider);
      final doc = await repo.createDocument(
        widget.patient.id,
        type: type,
        title: title,
        content: content,
      );

      if (mounted) {
        Navigator.of(context).pop();
        widget.onDocumentCreated(doc);
      }
    } catch (e) {
      if (mounted) {
        setState(() => _isLoading = false);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Erro ao salvar documento: $e'), backgroundColor: Colors.red),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Dialog(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: SizedBox(
        width: 680,
        height: 620,
        child: Column(
          children: [
            // Dialog Header
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
              decoration: BoxDecoration(
                color: theme.cardColor,
                borderRadius: const BorderRadius.vertical(top: Radius.circular(16)),
                border: Border(bottom: BorderSide(color: theme.dividerColor)),
              ),
              child: Row(
                children: [
                  const Icon(LucideIcons.filePlus, color: Color(0xFF2563EB)),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      'Emitir Documento Odontológico — ${widget.patient.name}',
                      style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  IconButton(
                    icon: const Icon(LucideIcons.x),
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                ],
              ),
            ),

            // Tab Bar
            TabBar(
              controller: _tabController,
              labelColor: theme.colorScheme.primary,
              unselectedLabelColor: theme.textTheme.bodyMedium?.color,
              tabs: const [
                Tab(icon: Icon(LucideIcons.fileCheck, size: 16), text: 'Atestado'),
                Tab(icon: Icon(LucideIcons.pill, size: 16), text: 'Receituário'),
                Tab(icon: Icon(LucideIcons.fileSymlink, size: 16), text: 'Encaminhamento'),
              ],
            ),
            const Divider(height: 1),

            // Tab Content
            Expanded(
              child: TabBarView(
                controller: _tabController,
                children: [
                  // Tab 1: Atestado
                  _buildAtestadoTab(theme),
                  // Tab 2: Receituário
                  _buildReceituarioTab(theme),
                  // Tab 3: Encaminhamento
                  _buildEncaminhamentoTab(theme),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildAtestadoTab(ThemeData theme) {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _atestadoDaysController,
                  keyboardType: TextInputType.number,
                  decoration: const InputDecoration(
                    labelText: 'Dias de Afastamento *',
                    hintText: 'Ex: 1, 2, 3',
                    border: OutlineInputBorder(),
                    prefixIcon: Icon(LucideIcons.calendar),
                  ),
                ),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: TextField(
                  controller: _atestadoCidController,
                  decoration: const InputDecoration(
                    labelText: 'CID (Opcional)',
                    hintText: 'Ex: K02.1 (Cárie de dentina)',
                    border: OutlineInputBorder(),
                    prefixIcon: Icon(LucideIcons.stethoscope),
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          TextField(
            controller: _atestadoReasonController,
            maxLines: 6,
            decoration: const InputDecoration(
              labelText: 'Texto / Motivo do Atestado',
              border: OutlineInputBorder(),
              alignLabelWithHint: true,
            ),
          ),
          const SizedBox(height: 24),
          SizedBox(
            width: double.infinity,
            height: 48,
            child: ElevatedButton.icon(
              onPressed: _isLoading ? null : _submitAtestado,
              icon: _isLoading
                  ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(LucideIcons.check),
              label: const Text('Emitir e Gerar PDF do Atestado'),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildReceituarioTab(ThemeData theme) {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          TextField(
            controller: _receitaTitleController,
            decoration: const InputDecoration(
              labelText: 'Título da Receita',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 12),
          Text(
            'Prescrições Rápidas (clique para adicionar):',
            style: TextStyle(fontSize: 12, fontWeight: FontWeight.bold, color: theme.hintColor),
          ),
          const SizedBox(height: 6),
          Wrap(
            spacing: 8,
            runSpacing: 6,
            children: [
              ActionChip(
                label: const Text('Amoxicilina 500mg'),
                onPressed: () => _addQuickMedication('1. Amoxicilina 500mg ------ 1 caixa\n   Tomar 1 cápsula via oral a cada 8 horas por 7 dias.'),
              ),
              ActionChip(
                label: const Text('Dipirona 500mg'),
                onPressed: () => _addQuickMedication('2. Dipirona Sódica 500mg --- 1 frasco/caixa\n   Tomar 1 comprimido (ou 35 gotas) de 6 em 6 horas se houver dor.'),
              ),
              ActionChip(
                label: const Text('Ibuprofeno 600mg'),
                onPressed: () => _addQuickMedication('3. Ibuprofeno 600mg --------- 1 caixa\n   Tomar 1 comprimido a cada 8 horas por 3 dias em caso de dor ou inchaço.'),
              ),
              ActionChip(
                label: const Text('Dexametasona 4mg'),
                onPressed: () => _addQuickMedication('4. Dexametasona 4mg --------- 1 caixa\n   Tomar 1 comprimido 1 hora antes do procedimento cirúrgico.'),
              ),
            ],
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _receitaMedicationsController,
            maxLines: 5,
            decoration: const InputDecoration(
              labelText: 'Medicamentos & Posologia *',
              hintText: 'Digite ou selecione acima...',
              border: OutlineInputBorder(),
              alignLabelWithHint: true,
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _receitaInstructionsController,
            maxLines: 2,
            decoration: const InputDecoration(
              labelText: 'Orientações Gerais ao Paciente (Opcional)',
              hintText: 'Ex: Evitar alimentos quentes e rígidos nas primeiras 24 horas.',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 20),
          SizedBox(
            width: double.infinity,
            height: 48,
            child: ElevatedButton.icon(
              onPressed: _isLoading ? null : _submitReceita,
              icon: _isLoading
                  ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(LucideIcons.check),
              label: const Text('Emitir e Gerar PDF do Receituário'),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildEncaminhamentoTab(ThemeData theme) {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          DropdownButtonFormField<String>(
            initialValue: _encaminhamentoSpecialty,
            decoration: const InputDecoration(
              labelText: 'Especialidade Destino *',
              border: OutlineInputBorder(),
              prefixIcon: Icon(LucideIcons.userCheck),
            ),
            items: _specialties.map((s) => DropdownMenuItem(value: s, child: Text(s))).toList(),
            onChanged: (val) {
              if (val != null) setState(() => _encaminhamentoSpecialty = val);
            },
          ),
          const SizedBox(height: 16),
          TextField(
            controller: _encaminhamentoReasonController,
            maxLines: 6,
            decoration: const InputDecoration(
              labelText: 'Descrição Clínica / Motivo do Encaminhamento *',
              hintText: 'Descreva a necessidade do tratamento especializado, exames complementares solicitados e observações relativas ao dente/região...',
              border: OutlineInputBorder(),
              alignLabelWithHint: true,
            ),
          ),
          const SizedBox(height: 24),
          SizedBox(
            width: double.infinity,
            height: 48,
            child: ElevatedButton.icon(
              onPressed: _isLoading ? null : _submitEncaminhamento,
              icon: _isLoading
                  ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(LucideIcons.check),
              label: const Text('Emitir e Gerar PDF do Encaminhamento'),
            ),
          ),
        ],
      ),
    );
  }
}
