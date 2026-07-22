
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:file_picker/file_picker.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:frontend_flutter/features/patients/data/patients_repository.dart';
import 'package:frontend_flutter/features/patients/data/patients_provider.dart';

class ImportExportDialog extends ConsumerStatefulWidget {
  const ImportExportDialog({super.key});

  @override
  ConsumerState<ImportExportDialog> createState() => _ImportExportDialogState();
}

class _ImportExportDialogState extends ConsumerState<ImportExportDialog> {
  bool _isImporting = false;
  bool _isExporting = false;
  bool _isDownloadingTemplate = false;
  PlatformFile? _selectedFile;

  Future<void> _handleExport() async {
    setState(() => _isExporting = true);
    try {
      final repo = ref.read(patientsRepositoryProvider);
      await repo.exportPatientsCsv();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Download da exportação iniciado no navegador!')),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Erro ao exportar: $e')),
        );
      }
    } finally {
      if (mounted) setState(() => _isExporting = false);
    }
  }

  Future<void> _handleDownloadTemplate() async {
    setState(() => _isDownloadingTemplate = true);
    try {
      final repo = ref.read(patientsRepositoryProvider);
      await repo.downloadImportTemplate();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Download do modelo iniciado no navegador!')),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Erro ao baixar modelo: $e')),
        );
      }
    } finally {
      if (mounted) setState(() => _isDownloadingTemplate = false);
    }
  }

  Future<void> _handleImport() async {
    if (_selectedFile == null) return;
    
    setState(() => _isImporting = true);
    try {
      final repo = ref.read(patientsRepositoryProvider);
      await repo.importPatientsCsv(_selectedFile!);
      ref.invalidate(patientsListProvider);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Pacientes importados com sucesso!')),
        );
        Navigator.of(context).pop();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Erro ao importar: $e')),
        );
      }
    } finally {
      if (mounted) setState(() => _isImporting = false);
    }
  }

  Future<void> _pickFile() async {
    final result = await FilePicker.pickFiles(
      type: FileType.custom,
      allowedExtensions: ['csv', 'xlsx'],
      withData: true,
    );
    if (result != null) {
      setState(() {
        _selectedFile = result.files.first;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Container(
        width: 600,
        constraints: const BoxConstraints(maxWidth: 600, maxHeight: 800),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Header
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.surfaceVariant.withOpacity(0.5),
                borderRadius: const BorderRadius.vertical(top: Radius.circular(16)),
                border: Border(bottom: BorderSide(color: Theme.of(context).dividerColor)),
              ),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Row(
                    children: [
                      Icon(LucideIcons.sheet, color: Colors.green.shade600, size: 28),
                      const SizedBox(width: 12),
                      const Text(
                        'Importar / Exportar Base',
                        style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
                      ),
                    ],
                  ),
                  IconButton(
                    icon: const Icon(Icons.close),
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                ],
              ),
            ),
            
            Flexible(
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(32),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // Seção 1: Exportar
                    const Text(
                      '1. Exportar (Backup)',
                      style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'Baixe todos os pacientes atuais cadastrados na sua clínica.',
                      style: TextStyle(color: Theme.of(context).textTheme.bodySmall?.color, fontSize: 14),
                    ),
                    const SizedBox(height: 16),
                    OutlinedButton.icon(
                      onPressed: _isExporting ? null : _handleExport,
                      icon: _isExporting 
                          ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                          : const Icon(LucideIcons.download),
                      label: const Text('Baixar Base Atual (.csv)'),
                      style: OutlinedButton.styleFrom(
                        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                      ),
                    ),

                    const Padding(
                      padding: EdgeInsets.symmetric(vertical: 32),
                      child: Divider(),
                    ),

                    // Seção 2: Importar
                    const Text(
                      '2. Importar Pacientes',
                      style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'Faça upload de uma planilha de outro sistema. Nosso sistema vai tentar ler as colunas de Nome, CPF, Telefone e E-mail de forma inteligente.',
                      style: TextStyle(color: Theme.of(context).textTheme.bodySmall?.color, fontSize: 14),
                    ),
                    const SizedBox(height: 16),

                    // Drop area (simulada)
                    InkWell(
                      onTap: _pickFile,
                      borderRadius: BorderRadius.circular(12),
                      child: Container(
                        width: double.infinity,
                        padding: const EdgeInsets.all(32),
                        decoration: BoxDecoration(
                          color: Theme.of(context).colorScheme.surfaceVariant.withOpacity(0.3),
                          borderRadius: BorderRadius.circular(12),
                          border: Border.all(
                            color: _selectedFile != null ? Theme.of(context).colorScheme.primary : Theme.of(context).dividerColor,
                            width: 2,
                            style: BorderStyle.solid,
                          ),
                        ),
                        child: Column(
                          children: [
                            Icon(
                              LucideIcons.uploadCloud, 
                              size: 48, 
                              color: _selectedFile != null ? Theme.of(context).colorScheme.primary : Colors.grey,
                            ),
                            const SizedBox(height: 16),
                            Text(
                              _selectedFile != null ? _selectedFile!.name : 'Clique para selecionar a planilha',
                              style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
                            ),
                            const SizedBox(height: 8),
                            const Text(
                              'Suporta .xlsx e .csv (Máx: 10MB)',
                              style: TextStyle(color: Colors.grey, fontSize: 13),
                            ),
                          ],
                        ),
                      ),
                    ),

                    if (_selectedFile != null) ...[
                      const SizedBox(height: 16),
                      SizedBox(
                        width: double.infinity,
                        child: ElevatedButton.icon(
                          onPressed: _isImporting ? null : _handleImport,
                          icon: _isImporting
                              ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                              : const Icon(LucideIcons.play),
                          label: Text(_isImporting ? 'Processando...' : 'Iniciar Importação'),
                          style: ElevatedButton.styleFrom(
                            padding: const EdgeInsets.symmetric(vertical: 16),
                            backgroundColor: Theme.of(context).colorScheme.primary,
                            foregroundColor: Theme.of(context).colorScheme.onPrimary,
                            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                          ),
                        ),
                      ),
                    ],

                    const SizedBox(height: 24),
                    Center(
                      child: TextButton(
                        onPressed: _isDownloadingTemplate ? null : _handleDownloadTemplate,
                        child: _isDownloadingTemplate
                            ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                            : const Text('Planilha muito confusa? Baixe o Modelo Padrão vazio', style: TextStyle(decoration: TextDecoration.underline)),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
