
import 'package:intl/intl.dart';
import 'package:pdf/pdf.dart';
import 'package:pdf/widgets.dart' as pw;
import 'package:printing/printing.dart';
import 'package:frontend_flutter/features/financial/data/financial_model.dart';

class ReceiptGenerator {
  static Future<void> generateReceipt({
    required ClinicTransaction transaction,
    required ClinicInstallment installment,
    String clinicName = 'Dental Clinic CRM',
    String clinicAddress = 'Endereço da Clínica não informado',
    String clinicPhone = 'Telefone não informado',
  }) async {
    final pdf = pw.Document();
    final formatCurrency = NumberFormat.currency(locale: 'pt_BR', symbol: 'R\$');

    pdf.addPage(
      pw.Page(
        pageFormat: PdfPageFormat.a4,
        build: (pw.Context context) {
          return pw.Container(
            padding: const pw.EdgeInsets.all(24),
            child: pw.Column(
              crossAxisAlignment: pw.CrossAxisAlignment.start,
              children: [
                pw.Header(
                  level: 0,
                  child: pw.Row(
                    mainAxisAlignment: pw.MainAxisAlignment.spaceBetween,
                    children: [
                      pw.Column(
                        crossAxisAlignment: pw.CrossAxisAlignment.start,
                        children: [
                          pw.Text(clinicName, style: pw.TextStyle(fontSize: 24, fontWeight: pw.FontWeight.bold)),
                          pw.SizedBox(height: 4),
                          pw.Text(clinicAddress, style: const pw.TextStyle(fontSize: 12)),
                          pw.Text(clinicPhone, style: const pw.TextStyle(fontSize: 12)),
                        ],
                      ),
                      pw.Text(
                        transaction.type == 'income' ? 'RECIBO' : 'COMPROVANTE',
                        style: pw.TextStyle(
                          fontSize: 24,
                          fontWeight: pw.FontWeight.bold,
                          color: PdfColors.grey700,
                        ),
                      ),
                    ],
                  ),
                ),
                pw.SizedBox(height: 32),
                pw.Text(
                  'Declaramos para os devidos fins que recebemos de (ou pagamos a) referente à transação abaixo:',
                  style: const pw.TextStyle(fontSize: 14),
                ),
                pw.SizedBox(height: 24),
                pw.Container(
                  padding: const pw.EdgeInsets.all(16),
                  decoration: pw.BoxDecoration(
                    border: pw.Border.all(color: PdfColors.grey400),
                    borderRadius: const pw.BorderRadius.all(pw.Radius.circular(8)),
                  ),
                  child: pw.Column(
                    crossAxisAlignment: pw.CrossAxisAlignment.start,
                    children: [
                      _buildDetailRow('Descrição:', transaction.description),
                      _buildDetailRow('Categoria:', transaction.category),
                      _buildDetailRow('Forma de Pagamento:', transaction.paymentMethod),
                      _buildDetailRow('Parcela:', '${installment.number} de ${installment.totalNumber}'),
                      _buildDetailRow('Data de Vencimento:', DateFormat('dd/MM/yyyy').format(installment.dueDate)),
                      pw.Divider(),
                      pw.Row(
                        mainAxisAlignment: pw.MainAxisAlignment.spaceBetween,
                        children: [
                          pw.Text('VALOR DA PARCELA', style: pw.TextStyle(fontWeight: pw.FontWeight.bold, fontSize: 16)),
                          pw.Text(
                            formatCurrency.format(installment.amount),
                            style: pw.TextStyle(fontWeight: pw.FontWeight.bold, fontSize: 16),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
                pw.SizedBox(height: 48),
                pw.Center(
                  child: pw.Column(
                    children: [
                      pw.Container(width: 250, height: 1, color: PdfColors.black),
                      pw.SizedBox(height: 8),
                      pw.Text(clinicName),
                      pw.Text('Assinatura do Responsável', style: const pw.TextStyle(fontSize: 10, color: PdfColors.grey700)),
                    ],
                  ),
                ),
                pw.Spacer(),
                pw.Align(
                  alignment: pw.Alignment.bottomRight,
                  child: pw.Text(
                    'Gerado em ${DateFormat('dd/MM/yyyy HH:mm').format(DateTime.now())} pelo sistema Dental Clinic CRM',
                    style: const pw.TextStyle(fontSize: 10, color: PdfColors.grey600),
                  ),
                ),
              ],
            ),
          );
        },
      ),
    );

    // Call printing to show layout / print dialog
    await Printing.layoutPdf(
      onLayout: (PdfPageFormat format) async => pdf.save(),
      name: 'recibo_${transaction.id}_parcela_${installment.number}.pdf',
    );
  }

  static pw.Widget _buildDetailRow(String label, String value) {
    return pw.Padding(
      padding: const pw.EdgeInsets.symmetric(vertical: 4),
      child: pw.Row(
        mainAxisAlignment: pw.MainAxisAlignment.spaceBetween,
        children: [
          pw.Text(label, style: pw.TextStyle(fontWeight: pw.FontWeight.bold)),
          pw.Text(value),
        ],
      ),
    );
  }
}
