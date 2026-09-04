import 'package:intl/intl.dart';
import 'package:pdf/pdf.dart';
import 'package:pdf/widgets.dart' as pw;
import 'package:frontend_flutter/features/patients/data/patient_model.dart';
import 'package:frontend_flutter/features/patients/data/medical_document_model.dart';
import 'package:frontend_flutter/features/settings/data/clinic_model.dart';

class DocumentPdfGenerator {
  static Future<List<int>> generateDocumentPdf({
    required Patient patient,
    required MedicalDocument document,
    Clinic? clinic,
    String? dentistName,
    String? dentistCro,
  }) async {
    final pdf = pw.Document();

    final primaryColor = PdfColor.fromHex('#1E3A8A');
    final darkTextColor = PdfColor.fromHex('#1E293B');
    final lightGreyColor = PdfColor.fromHex('#F8FAFC');

    pdf.addPage(
      pw.Page(
        pageFormat: PdfPageFormat.a4,
        margin: const pw.EdgeInsets.all(36),
        build: (pw.Context context) {
          return pw.Column(
            crossAxisAlignment: pw.CrossAxisAlignment.start,
            children: [
              // Header Clinic Info
              pw.Row(
                mainAxisAlignment: pw.MainAxisAlignment.spaceBetween,
                crossAxisAlignment: pw.CrossAxisAlignment.start,
                children: [
                  pw.Column(
                    crossAxisAlignment: pw.CrossAxisAlignment.start,
                    children: [
                      pw.Text(
                        (clinic?.name != null && clinic!.name.isNotEmpty)
                            ? clinic.name
                            : 'Clínica Odontológica',
                        style: pw.TextStyle(
                          fontSize: 20,
                          fontWeight: pw.FontWeight.bold,
                          color: primaryColor,
                        ),
                      ),
                      pw.SizedBox(height: 4),
                      if (clinic?.cnpj != null && clinic!.cnpj.isNotEmpty)
                        pw.Text('CNPJ: ${clinic.cnpj}',
                            style: const pw.TextStyle(fontSize: 10)),
                      if (clinic?.phone != null && clinic!.phone.isNotEmpty)
                        pw.Text('Telefone: ${clinic.phone}',
                            style: const pw.TextStyle(fontSize: 10)),
                      if (clinic?.email != null && clinic!.email.isNotEmpty)
                        pw.Text('E-mail: ${clinic.email}',
                            style: const pw.TextStyle(fontSize: 10)),
                    ],
                  ),
                  pw.Column(
                    crossAxisAlignment: pw.CrossAxisAlignment.end,
                    children: [
                      pw.Text(
                        document.typeLabel,
                        style: pw.TextStyle(
                          fontSize: 14,
                          fontWeight: pw.FontWeight.bold,
                          color: primaryColor,
                        ),
                      ),
                      pw.SizedBox(height: 4),
                      pw.Text(
                        'Data: ${document.formattedDate}',
                        style: const pw.TextStyle(fontSize: 10),
                      ),
                    ],
                  ),
                ],
              ),
              pw.SizedBox(height: 12),
              pw.Divider(color: primaryColor, thickness: 1.5),
              pw.SizedBox(height: 16),

              // Patient Box
              pw.Container(
                width: double.infinity,
                padding: const pw.EdgeInsets.all(12),
                decoration: pw.BoxDecoration(
                  color: lightGreyColor,
                  borderRadius: const pw.BorderRadius.all(pw.Radius.circular(6)),
                  border: pw.Border.all(color: PdfColor.fromHex('#CBD5E1')),
                ),
                child: pw.Column(
                  crossAxisAlignment: pw.CrossAxisAlignment.start,
                  children: [
                    pw.Text(
                      'DADOS DO PACIENTE',
                      style: pw.TextStyle(
                        fontSize: 10,
                        fontWeight: pw.FontWeight.bold,
                        color: primaryColor,
                      ),
                    ),
                    pw.SizedBox(height: 6),
                    pw.Row(
                      mainAxisAlignment: pw.MainAxisAlignment.spaceBetween,
                      children: [
                        pw.Text(
                          'Nome: ${patient.name}',
                          style: pw.TextStyle(
                            fontSize: 11,
                            fontWeight: pw.FontWeight.bold,
                            color: darkTextColor,
                          ),
                        ),
                        if (patient.cpf != null && patient.cpf!.isNotEmpty)
                          pw.Text(
                            'CPF: ${patient.cpf}',
                            style: const pw.TextStyle(fontSize: 10),
                          ),
                      ],
                    ),
                    if (patient.birthDate != null && patient.birthDate!.isNotEmpty) ...[
                      pw.SizedBox(height: 4),
                      pw.Text(
                        'Data de Nasc.: ${patient.birthDate}',
                        style: const pw.TextStyle(fontSize: 10),
                      ),
                    ],
                  ],
                ),
              ),
              pw.SizedBox(height: 24),

              // Document Title Header Centered
              pw.Center(
                child: pw.Text(
                  document.title.toUpperCase(),
                  style: pw.TextStyle(
                    fontSize: 16,
                    fontWeight: pw.FontWeight.bold,
                    color: primaryColor,
                    decoration: pw.TextDecoration.underline,
                  ),
                ),
              ),
              pw.SizedBox(height: 24),

              // Document Content Body
              pw.Expanded(
                child: pw.Container(
                  width: double.infinity,
                  alignment: pw.Alignment.topLeft,
                  padding: const pw.EdgeInsets.symmetric(horizontal: 8),
                  child: pw.Text(
                    document.content,
                    style: pw.TextStyle(
                      fontSize: 12,
                      color: darkTextColor,
                      lineSpacing: 6,
                    ),
                  ),
                ),
              ),

              pw.SizedBox(height: 30),

              // Signature block
              pw.Center(
                child: pw.Column(
                  children: [
                    pw.Container(
                      width: 240,
                      child: pw.Divider(thickness: 1, color: darkTextColor),
                    ),
                    pw.SizedBox(height: 4),
                    pw.Text(
                      dentistName ?? 'Dr(a). Cirurgião(ã)-Dentista',
                      style: pw.TextStyle(
                        fontSize: 12,
                        fontWeight: pw.FontWeight.bold,
                        color: darkTextColor,
                      ),
                    ),
                    if (dentistCro != null && dentistCro.isNotEmpty)
                      pw.Text(
                        'CRO: $dentistCro',
                        style: const pw.TextStyle(fontSize: 10),
                      )
                    else
                      pw.Text(
                        'Cirurgião(ã)-Dentista',
                        style: const pw.TextStyle(fontSize: 10),
                      ),
                  ],
                ),
              ),
              pw.SizedBox(height: 16),
              pw.Divider(color: PdfColor.fromHex('#E2E8F0')),
              pw.SizedBox(height: 4),
              pw.Row(
                mainAxisAlignment: pw.MainAxisAlignment.spaceBetween,
                children: [
                  pw.Text(
                    'Documento emitido eletronicamente via DentalCRM',
                    style: const pw.TextStyle(fontSize: 8, color: PdfColors.grey600),
                  ),
                  pw.Text(
                    DateFormat('dd/MM/yyyy HH:mm').format(DateTime.now()),
                    style: const pw.TextStyle(fontSize: 8, color: PdfColors.grey600),
                  ),
                ],
              ),
            ],
          );
        },
      ),
    );

    return pdf.save();
  }
}
