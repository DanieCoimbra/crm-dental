# Spec — F04 PDF do documento clínico

## 1. Technical Overview
- **Feature:** geração do PDF sob demanda e registro de `DOWNLOAD`.
- **Tech Stack:** OpenPDF 2.4.0 (já no `pom.xml`), Spring Web MVC, JPA com consulta nativa, Bucket4j. Nenhuma dependência nova.
- **Architecture Approach:** `DocumentPdfService` orquestra; `DocumentPdfRenderer` é regra pura; resposta binária inline sem Storage.

## 2. Data Models & Schema
- **Banco:** nenhuma mudança; usa `core.record_document_download(uuid)` (V033), que retorna `void` e insere `DOWNLOAD` em `audit_log`.
- `DocumentPdfModel` (record imutável): `clinicName`, `clinicAddress`, `clinicPhone`, `type`, `number`, `issuedOn`, `patientName`, `restDays`, `targetSpecialty`, `content`, `voidedOn` (nulo se ativo), `dentistName`, `dentistCro`, `dentistCroState`.
- Estado em memória: balde de PDF do `ClinicalDocumentRateLimiter`.

## 3. Component Architecture
- `DocumentPdfService.pdf(UUID id)` → `PdfFile(bytes, filename)`.
- `DocumentDownloadRecorder.record(UUID id)`.
- `DocumentPdfRenderer.render(DocumentPdfModel)`: A4, margens 40/40/40/56, Helvetica `CP1252`; sem Spring, banco ou relógio.
- `ClinicalDocumentRateLimiter` (+ balde `pdfs`) e `ClinicalDocumentRateLimitInterceptor` (+ rota `GET /clinical-documents/*/pdf`).
- `ClinicalDocumentItemController`: `GET /clinical-documents/{id}/pdf`.
- Layout (ASCII da spec global 4.5): cabeçalho da clínica, título centrado, linha "Paciente / Emissão", corpo por tipo, assinatura com nome e CRO, rodapé de página; "ANULADO" diagonal (cinza claro, 80 pt, 45°) sob o texto quando `VOIDED`.

## 4. Core Logic & Algorithms
1. Interceptor: balde de PDF (30/min) → 429.
2. `@Transactional`: `findById` → 404; `anonymizedAt != null` → 409 `DOCUMENT_ANONYMIZED`; `DocumentIntegrity.verify` → 500 `integrity`.
3. `DocumentDownloadRecorder.record(id)`: `P0002` → 404, `CR003` → 409, `42501`/`28000` → 500.
4. `DocumentCipher.decrypt`; `ClinicIdentity.current()`; `ProfessionalIdentity.of(doc.createdBy)`; `PatientLookup.briefs(patientId).name`.
5. `DocumentPdfRenderer.render(model)`.
6. `bytes.length > 4 * 1024 * 1024` → 422 `PDF_TOO_LARGE` e **rollback** (o `DOWNLOAD` some).
7. Responde 200 com `application/pdf`, `inline; filename="<tipo>-<n>.pdf"`, `no-store`, `nosniff`. Qualquer exceção antes do corpo reverte a transação.
- `<N> dia(s)`: "1 dia" / "N dias"; datas `dd/MM/yyyy`; metadados: título "<Tipo> nº <n>", autor/criador "CRM Clinica"; sem nome de paciente.

## 5. Error Handling & Edge Cases
- Recepção → 403 (`SecurityConfig`); outra clínica → 404; anonimizado → 409; `integrity` → 500.
- PDF > 4 MB → 422 com rollback; falha do OpenPDF → 500 genérico com rollback.
- Dentista sem CRO no PDF (CRO apagado depois da emissão): linha "CRO" omitida, nome mantido.
- Caracteres fora de `CP1252` omitidos; quebras de linha da receita preservadas; palavra sem espaço maior que a largura é quebrada.
- 31º PDF no minuto → 429 com `Retry-After`.

## 6. Security & Performance
- `no-store`, `inline`, `nosniff`; nome de arquivo sem dado pessoal; corpo sem CPF e sem data de nascimento.
- Nada do PDF, do texto ou do nome do paciente em log, exceção ou métrica.
- Metas: p95 ≤ 300 ms (render + `record_document_download`) com 4000 caracteres; 1 conexão do pool por poucos ms.
- Teste de pool: 3 PDFs simultâneos não degradam uma leitura (p95 estável).
