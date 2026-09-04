import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:frontend_flutter/features/patients/data/odontogram_model.dart';
import 'package:frontend_flutter/features/patients/data/patients_provider.dart';
import 'package:frontend_flutter/features/patients/data/patients_repository.dart';

class OdontogramTab extends ConsumerStatefulWidget {
  final int patientId;

  const OdontogramTab({super.key, required this.patientId});

  @override
  ConsumerState<OdontogramTab> createState() => _OdontogramTabState();
}

class _OdontogramTabState extends ConsumerState<OdontogramTab> {
  bool _isDeciduous = false; // false = Adulto (32), true = Decíduo (20)
  int? _selectedToothHistory;
  bool _showHistoryDrawer = false;

  // FDI Quadrants Adult (11-48)
  final List<int> _quadrant1 = [18, 17, 16, 15, 14, 13, 12, 11];
  final List<int> _quadrant2 = [21, 22, 23, 24, 25, 26, 27, 28];
  final List<int> _quadrant4 = [48, 47, 46, 45, 44, 43, 42, 41];
  final List<int> _quadrant3 = [31, 32, 33, 34, 35, 36, 37, 38];

  // FDI Quadrants Deciduous (51-85)
  final List<int> _quadrant5 = [55, 54, 53, 52, 51];
  final List<int> _quadrant6 = [61, 62, 63, 64, 65];
  final List<int> _quadrant8 = [85, 84, 83, 82, 81];
  final List<int> _quadrant7 = [71, 72, 73, 74, 75];

  @override
  Widget build(BuildContext context) {
    final teethStatusAsync = ref.watch(teethStatusProvider(widget.patientId));
    final teethHistoryAsync = ref.watch(teethHistoryProvider(widget.patientId));
    final theme = Theme.of(context);

    return Scaffold(
      backgroundColor: theme.scaffoldBackgroundColor,
      endDrawer: _showHistoryDrawer
          ? _buildHistoryDrawer(context, teethHistoryAsync)
          : null,
      body: Row(
        children: [
          // Main Odontogram Canvas
          Expanded(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(20.0),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  // Header Bar: Toggle Adult/Deciduous & Legend & History button
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      // Toggle Button
                      SegmentedButton<bool>(
                        segments: const [
                          ButtonSegment<bool>(
                            value: false,
                            label: Text('Dentes Adultos (32 FDI)'),
                            icon: Icon(LucideIcons.smile),
                          ),
                          ButtonSegment<bool>(
                            value: true,
                            label: Text('Dentes Decíduos / Leite (20 FDI)'),
                            icon: Icon(LucideIcons.baby),
                          ),
                        ],
                        selected: {_isDeciduous},
                        onSelectionChanged: (val) {
                          setState(() {
                            _isDeciduous = val.first;
                          });
                        },
                      ),

                      // History drawer trigger
                      ElevatedButton.icon(
                        onPressed: () {
                          setState(() {
                            _showHistoryDrawer = true;
                          });
                          Scaffold.of(context).openEndDrawer();
                        },
                        icon: const Icon(LucideIcons.history),
                        label: const Text('Linha do Tempo / Histórico'),
                      ),
                    ],
                  ),
                  const SizedBox(height: 20),

                  // Clinical Color Legend Bar
                  _buildColorLegend(theme),
                  const SizedBox(height: 24),

                  // Teeth Grid Representation
                  teethStatusAsync.when(
                    data: (teethList) {
                      final Map<int, List<ToothStatus>> teethMap = {};
                      for (final t in teethList) {
                        teethMap.putIfAbsent(t.toothNumber, () => []).add(t);
                      }

                      return Card(
                        elevation: 0,
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(16),
                          side: BorderSide(color: theme.dividerColor),
                        ),
                        child: Padding(
                          padding: const EdgeInsets.all(24.0),
                          child: Column(
                            children: [
                              Text(
                                _isDeciduous ? 'Arcada Decídua (Leite)' : 'Arcada Permanente (Adulto)',
                                style: theme.textTheme.titleMedium?.copyWith(
                                  fontWeight: FontWeight.bold,
                                  color: theme.colorScheme.primary,
                                ),
                              ),
                              const SizedBox(height: 20),

                              // Arcada Superior (Upper Arch)
                              Row(
                                mainAxisAlignment: MainAxisAlignment.center,
                                children: [
                                  _buildToothGroup(
                                    _isDeciduous ? _quadrant5 : _quadrant1,
                                    teethMap,
                                    isUpper: true,
                                  ),
                                  Container(
                                    width: 2,
                                    height: 70,
                                    margin: const EdgeInsets.symmetric(horizontal: 16),
                                    color: theme.colorScheme.primary.withValues(alpha: 0.4),
                                  ),
                                  _buildToothGroup(
                                    _isDeciduous ? _quadrant6 : _quadrant2,
                                    teethMap,
                                    isUpper: true,
                                  ),
                                ],
                              ),

                              const Padding(
                                padding: EdgeInsets.symmetric(vertical: 20.0),
                                child: Divider(thickness: 1.5),
                              ),

                              // Arcada Inferior (Lower Arch)
                              Row(
                                mainAxisAlignment: MainAxisAlignment.center,
                                children: [
                                  _buildToothGroup(
                                    _isDeciduous ? _quadrant8 : _quadrant4,
                                    teethMap,
                                    isUpper: false,
                                  ),
                                  Container(
                                    width: 2,
                                    height: 70,
                                    margin: const EdgeInsets.symmetric(horizontal: 16),
                                    color: theme.colorScheme.primary.withValues(alpha: 0.4),
                                  ),
                                  _buildToothGroup(
                                    _isDeciduous ? _quadrant7 : _quadrant3,
                                    teethMap,
                                    isUpper: false,
                                  ),
                                ],
                              ),
                            ],
                          ),
                        ),
                      );
                    },
                    loading: () => const Center(child: CircularProgressIndicator()),
                    error: (err, _) => Center(child: Text('Erro ao carregar odontograma: $err')),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  // Clinical Legend Widget
  Widget _buildColorLegend(ThemeData theme) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: theme.cardColor,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: theme.dividerColor),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'Legenda de Condições Clínicas:',
            style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 16,
            runSpacing: 10,
            children: ToothCondition.values.map((cond) {
              return Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Container(
                    width: 14,
                    height: 14,
                    decoration: BoxDecoration(
                      color: cond.color,
                      shape: BoxShape.circle,
                      border: Border.all(color: Colors.black26),
                    ),
                  ),
                  const SizedBox(width: 6),
                  Text(
                    cond.label,
                    style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w500),
                  ),
                ],
              );
            }).toList(),
          ),
        ],
      ),
    );
  }

  // Tooth Group Widget for a quadrant
  Widget _buildToothGroup(List<int> teethNumbers, Map<int, List<ToothStatus>> teethMap, {required bool isUpper}) {
    return Wrap(
      spacing: 8,
      children: teethNumbers.map((toothNum) {
        final statuses = teethMap[toothNum] ?? [];
        return _buildToothCard(toothNum, statuses, isUpper);
      }).toList(),
    );
  }

  // Individual FDI Tooth Widget
  Widget _buildToothCard(int toothNumber, List<ToothStatus> statuses, bool isUpper) {
    ToothCondition generalCondition = ToothCondition.higido;
    final generalStatus = statuses.firstWhere(
      (s) => s.face == ToothFace.geral,
      orElse: () => ToothStatus(patientId: widget.patientId, toothNumber: toothNumber, condition: ToothCondition.higido),
    );
    generalCondition = generalStatus.condition;

    return Column(
      children: [
        if (isUpper) ...[
          Text(
            '$toothNumber',
            style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13),
          ),
          const SizedBox(height: 4),
        ],
        GestureDetector(
          onTap: () => _openToothConditionDialog(toothNumber, statuses),
          child: Container(
            width: 44,
            height: 44,
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(6),
              border: Border.all(
                color: generalCondition == ToothCondition.higido ? Colors.grey.shade400 : generalCondition.color,
                width: generalCondition == ToothCondition.higido ? 1.5 : 2.5,
              ),
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withValues(alpha: 0.04),
                  blurRadius: 4,
                ),
              ],
            ),
            child: CustomPaint(
              painter: _ToothAnatomyPainter(statuses: statuses, generalCondition: generalCondition),
            ),
          ),
        ),
        if (!isUpper) ...[
          const SizedBox(height: 4),
          Text(
            '$toothNumber',
            style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13),
          ),
        ],
      ],
    );
  }

  void _openToothConditionDialog(int toothNumber, List<ToothStatus> currentStatuses) {
    showDialog(
      context: context,
      builder: (ctx) {
        return _ToothConditionDialog(
          patientId: widget.patientId,
          toothNumber: toothNumber,
          currentStatuses: currentStatuses,
        );
      },
    );
  }

  // History Drawer for Tooth Timeline
  Widget _buildHistoryDrawer(BuildContext context, AsyncValue<List<ToothHistory>> historyAsync) {
    final theme = Theme.of(context);
    return Drawer(
      width: 420,
      child: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(20.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Row(
                    children: [
                      const Icon(LucideIcons.history, color: Color(0xFF2563EB)),
                      const SizedBox(width: 10),
                      Text('Histórico Odontograma', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
                    ],
                  ),
                  IconButton(
                    icon: const Icon(LucideIcons.x),
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                ],
              ),
              const Divider(height: 20),

              if (_selectedToothHistory != null) ...[
                Chip(
                  label: Text('Dente $_selectedToothHistory'),
                  onDeleted: () {
                    setState(() {
                      _selectedToothHistory = null;
                    });
                  },
                ),
                const SizedBox(height: 10),
              ],

              Expanded(
                child: historyAsync.when(
                  data: (historyList) {
                    final filtered = _selectedToothHistory != null
                        ? historyList.where((h) => h.toothNumber == _selectedToothHistory).toList()
                        : historyList;

                    if (filtered.isEmpty) {
                      return const Center(
                        child: Text('Nenhum registro histórico gravado.'),
                      );
                    }

                    return ListView.builder(
                      itemCount: filtered.length,
                      itemBuilder: (ctx, idx) {
                        final item = filtered[idx];
                        return Card(
                          margin: const EdgeInsets.only(bottom: 12),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(12),
                            side: BorderSide(color: theme.dividerColor),
                          ),
                          child: Padding(
                            padding: const EdgeInsets.all(12.0),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Row(
                                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                                  children: [
                                    Container(
                                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                                      decoration: BoxDecoration(
                                        color: item.newCondition.color.withValues(alpha: 0.15),
                                        borderRadius: BorderRadius.circular(6),
                                      ),
                                      child: Text(
                                        'Dente ${item.toothNumber} — ${item.face.label}',
                                        style: TextStyle(
                                          fontWeight: FontWeight.bold,
                                          color: item.newCondition.color,
                                        ),
                                      ),
                                    ),
                                    Text(
                                      '${item.createdAt.day.toString().padLeft(2, '0')}/${item.createdAt.month.toString().padLeft(2, '0')}/${item.createdAt.year}',
                                      style: TextStyle(fontSize: 12, color: theme.textTheme.bodySmall?.color),
                                    ),
                                  ],
                                ),
                                const SizedBox(height: 8),
                                Text(
                                  'Condição: ${item.newCondition.label}',
                                  style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13),
                                ),
                                if (item.previousCondition != null)
                                  Text(
                                    'Anterior: ${item.previousCondition!.label}',
                                    style: TextStyle(fontSize: 12, color: theme.textTheme.bodySmall?.color),
                                  ),
                                if (item.notes != null && item.notes!.isNotEmpty) ...[
                                  const SizedBox(height: 6),
                                  Text(
                                    'Obs: ${item.notes}',
                                    style: const TextStyle(fontSize: 12, fontStyle: FontStyle.italic),
                                  ),
                                ],
                                if (item.createdByName != null) ...[
                                  const SizedBox(height: 4),
                                  Text(
                                    'Por: ${item.createdByName}',
                                    style: TextStyle(fontSize: 11, color: theme.textTheme.bodySmall?.color),
                                  ),
                                ],
                              ],
                            ),
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
        ),
      ),
    );
  }
}

class _ToothConditionDialog extends ConsumerStatefulWidget {
  final int patientId;
  final int toothNumber;
  final List<ToothStatus> currentStatuses;

  const _ToothConditionDialog({
    required this.patientId,
    required this.toothNumber,
    required this.currentStatuses,
  });

  @override
  ConsumerState<_ToothConditionDialog> createState() => _ToothConditionDialogState();
}

class _ToothConditionDialogState extends ConsumerState<_ToothConditionDialog> {
  ToothFace _selectedFace = ToothFace.geral;
  ToothCondition _selectedCondition = ToothCondition.higido;
  late TextEditingController _notesController;

  @override
  void initState() {
    super.initState();
    _notesController = TextEditingController();

    final existingGeral = widget.currentStatuses.firstWhere(
      (s) => s.face == ToothFace.geral,
      orElse: () => ToothStatus(
        patientId: widget.patientId,
        toothNumber: widget.toothNumber,
        condition: ToothCondition.higido,
      ),
    );
    _selectedCondition = existingGeral.condition;
  }

  @override
  void dispose() {
    _notesController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      title: Row(
        children: [
          const Icon(LucideIcons.sparkles, color: Color(0xFF2563EB)),
          const SizedBox(width: 10),
          Text('Dente ${widget.toothNumber} — Alterar Condição'),
        ],
      ),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Face do Dente:', style: TextStyle(fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: ToothFace.values.map((f) {
                final isSel = _selectedFace == f;
                return ChoiceChip(
                  label: Text(f.label),
                  selected: isSel,
                  onSelected: (val) {
                    if (val) {
                      setState(() {
                        _selectedFace = f;
                      });
                    }
                  },
                );
              }).toList(),
            ),
            const SizedBox(height: 16),

            const Text('Condição Clínica:', style: TextStyle(fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: ToothCondition.values.map((cond) {
                final isSel = _selectedCondition == cond;
                return FilterChip(
                  selected: isSel,
                  label: Text(cond.label),
                  avatar: Container(
                    width: 12,
                    height: 12,
                    decoration: BoxDecoration(color: cond.color, shape: BoxShape.circle),
                  ),
                  selectedColor: cond.color.withValues(alpha: 0.25),
                  checkmarkColor: cond.color,
                  onSelected: (val) {
                    if (val) {
                      setState(() {
                        _selectedCondition = cond;
                      });
                    }
                  },
                );
              }).toList(),
            ),
            const SizedBox(height: 16),

            TextField(
              controller: _notesController,
              maxLines: 2,
              decoration: const InputDecoration(
                labelText: 'Observações / Detalhes do Procedimento',
                hintText: 'Ex: Resina composta na face Oclusal...',
                border: OutlineInputBorder(),
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Cancelar'),
        ),
        ElevatedButton.icon(
          onPressed: () async {
            final nav = Navigator.of(context);
            final messenger = ScaffoldMessenger.of(context);
            final repo = ref.read(patientsRepositoryProvider);
            await repo.updateToothStatus(
              widget.patientId,
              toothNumber: widget.toothNumber,
              face: _selectedFace.code,
              condition: _selectedCondition.code,
              notes: _notesController.text.trim().isNotEmpty ? _notesController.text.trim() : null,
            );

            ref.invalidate(teethStatusProvider(widget.patientId));
            ref.invalidate(teethHistoryProvider(widget.patientId));

            if (!mounted) return;
            nav.pop();
            messenger.showSnackBar(
              SnackBar(
                content: Text('Dente ${widget.toothNumber} atualizado (${_selectedCondition.label})!'),
                backgroundColor: _selectedCondition.color,
              ),
            );
          },
          icon: const Icon(LucideIcons.save),
          label: const Text('Salvar Alteração'),
        ),
      ],
    );
  }
}

class _ToothAnatomyPainter extends CustomPainter {
  final List<ToothStatus> statuses;
  final ToothCondition generalCondition;

  _ToothAnatomyPainter({required this.statuses, required this.generalCondition});

  @override
  void paint(Canvas canvas, Size size) {
    if (generalCondition == ToothCondition.extraido) {
      final paint = Paint()
        ..color = ToothCondition.extraido.color
        ..strokeWidth = 3
        ..style = PaintingStyle.stroke;
      canvas.drawLine(const Offset(4, 4), Offset(size.width - 4, size.height - 4), paint);
      canvas.drawLine(Offset(size.width - 4, 4), Offset(4, size.height - 4), paint);
      return;
    }

    if (generalCondition == ToothCondition.implante) {
      final bg = Paint()..color = ToothCondition.implante.color.withValues(alpha: 0.3);
      canvas.drawRect(Rect.fromLTWH(0, 0, size.width, size.height), bg);
      final linePaint = Paint()
        ..color = ToothCondition.implante.color
        ..strokeWidth = 2.5;
      canvas.drawLine(Offset(size.width / 2, 4), Offset(size.width / 2, size.height - 4), linePaint);
      canvas.drawLine(Offset(6, size.height / 3), Offset(size.width - 6, size.height / 3), linePaint);
      canvas.drawLine(Offset(6, 2 * size.height / 3), Offset(size.width - 6, 2 * size.height / 3), linePaint);
      return;
    }

    final Map<ToothFace, Color> faceColors = {};
    for (final s in statuses) {
      if (s.face != ToothFace.geral) {
        faceColors[s.face] = s.condition.color;
      }
    }

    final defaultColor = generalCondition != ToothCondition.higido
        ? generalCondition.color.withValues(alpha: 0.3)
        : Colors.grey.shade100;

    final double w = size.width;
    final double h = size.height;
    final double inset = 12.0;

    final pathTop = Path()
      ..moveTo(0, 0)
      ..lineTo(w, 0)
      ..lineTo(w - inset, inset)
      ..lineTo(inset, inset)
      ..close();
    canvas.drawPath(pathTop, Paint()..color = faceColors[ToothFace.vestibular] ?? defaultColor);

    final pathBottom = Path()
      ..moveTo(0, h)
      ..lineTo(w, h)
      ..lineTo(w - inset, h - inset)
      ..lineTo(inset, h - inset)
      ..close();
    canvas.drawPath(pathBottom, Paint()..color = faceColors[ToothFace.lingual] ?? faceColors[ToothFace.palatina] ?? defaultColor);

    final pathLeft = Path()
      ..moveTo(0, 0)
      ..lineTo(0, h)
      ..lineTo(inset, h - inset)
      ..lineTo(inset, inset)
      ..close();
    canvas.drawPath(pathLeft, Paint()..color = faceColors[ToothFace.mesial] ?? defaultColor);

    final pathRight = Path()
      ..moveTo(w, 0)
      ..lineTo(w, h)
      ..lineTo(w - inset, h - inset)
      ..lineTo(w - inset, inset)
      ..close();
    canvas.drawPath(pathRight, Paint()..color = faceColors[ToothFace.distal] ?? defaultColor);

    final pathCenter = Rect.fromLTWH(inset, inset, w - 2 * inset, h - 2 * inset);
    canvas.drawRect(pathCenter, Paint()..color = faceColors[ToothFace.oclusal] ?? faceColors[ToothFace.incisal] ?? defaultColor);

    final strokePaint = Paint()
      ..color = Colors.grey.shade400
      ..strokeWidth = 1
      ..style = PaintingStyle.stroke;

    canvas.drawPath(pathTop, strokePaint);
    canvas.drawPath(pathBottom, strokePaint);
    canvas.drawPath(pathLeft, strokePaint);
    canvas.drawPath(pathRight, strokePaint);
    canvas.drawRect(pathCenter, strokePaint);
  }

  @override
  bool shouldRepaint(covariant _ToothAnatomyPainter oldDelegate) => true;
}
