import 'package:flutter/material.dart';
import 'package:frontend_flutter/features/schedule/data/waitlist_model.dart';
import 'package:frontend_flutter/features/schedule/presentation/widgets/appointment_form_dialog.dart';
import 'package:intl/intl.dart';

class SmartBookingDialog extends StatelessWidget {
  final List<Waitlist> matches;
  final DateTime availableTime;
  final int doctorId;

  const SmartBookingDialog({
    super.key,
    required this.matches,
    required this.availableTime,
    required this.doctorId,
  });

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Row(
        children: [
          Icon(Icons.bolt, color: Colors.amber),
          SizedBox(width: 8),
          Text('Encaixe Inteligente'),
        ],
      ),
      content: SizedBox(
        width: 500,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Um horário foi liberado em ${DateFormat('dd/MM/yyyy HH:mm').format(availableTime)}.',
              style: const TextStyle(fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 16),
            const Text('Pacientes compatíveis na fila de espera:'),
            const SizedBox(height: 8),
            Flexible(
              child: ListView.builder(
                shrinkWrap: true,
                itemCount: matches.length,
                itemBuilder: (context, index) {
                  final waitlist = matches[index];
                  return ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text(waitlist.patient?.name ?? 'Paciente Desconhecido'),
                    subtitle: Text('Urgência: ${waitlist.urgencyLevel} | Notas: ${waitlist.notes}'),
                    trailing: ElevatedButton(
                      onPressed: () {
                        Navigator.pop(context); // Fecha o modal de sugestão
                        // Abre o formulário pré-preenchido
                        showDialog(
                          context: context,
                          builder: (_) => AppointmentFormDialog(
                            initialPatientId: waitlist.patientId,
                            initialDate: availableTime,
                            initialNotes: 'Encaixe via Fila de Espera - Urgência: ${waitlist.urgencyLevel}\n${waitlist.notes}',
                          ),
                        );
                      },
                      child: const Text('Encaixar'),
                    ),
                  );
                },
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Ignorar'),
        ),
      ],
    );
  }
}
