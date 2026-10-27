# PRD — F04 PDF do documento clínico

**Derivação:** PRD global da Fase 4, US-P4-07 e US-P4-08; FR-8 a FR-11; decisões I5, I9 e I11 (teto de 4 MB). Spec global, seções 4.4, 4.5, 5, 6, 7 e 8.
**Dependências externas:** F02 (`ClinicalDocument`, `DocumentCipher`, `DocumentIntegrity`, `ClinicalDocumentRateLimiter`, `ClinicalDocumentWebConfig`), F03 (estado `VOIDED` para a marca), F01 (`ProfessionalIdentity.of`, `ConflictMessages.DOCUMENT_ANONYMIZED`, `AuditAction.DOWNLOAD`, propriedade `pdfs-per-minute`), `ClinicIdentity`, `PatientLookup.briefs`, OpenPDF 2.4.0, banco V033 (`core.record_document_download`). Requer o access token da Fase 0.

## 1. Introdução

- **Problema:** o dentista precisa imprimir o documento, e cada reimpressão é um acesso a dado de saúde que deve ficar registrado.
- **Solução:** `GET /clinical-documents/{id}/pdf` gera o PDF em memória, devolve o binário inline sem passar pelo Storage e grava o evento `DOWNLOAD` **na mesma transação**: se algo falhar, nada é registrado.
- **Metas:**
  1. PDF p95 ≤ 300 ms (renderização + `record_document_download`) com texto de 4000 caracteres.
  2. Falha injetada na geração: zero linhas `DOWNLOAD`.
  3. Texto de 4000 caracteres cabe em ≤ 2 páginas; PDF ≤ 4 MB.
  4. Cobertura ≥ 80% em `DocumentPdfService` e `DocumentPdfRenderer`.

## 2. User stories

### US-001: Imprimir o documento
**Descrição:** Como dentista, quero abrir o PDF do documento para imprimir e entregar ao paciente.

**Critérios de aceite:**
- [ ] `GET /api/v1/clinical-documents/{id}/pdf` (só D) responde 200 com `Content-Type: application/pdf`, `Content-Disposition: inline; filename="atestado-12.pdf"` (`receita-<n>`/`encaminhamento-<n>`), `Cache-Control: no-store` e `X-Content-Type-Options: nosniff`.
- [ ] Recepção → 403; documento de outra clínica ou inexistente → 404; anonimizado → 409 (`DOCUMENT_ANONYMIZED`); `sha256` divergente → 500 `integrity`.
- [ ] Mais de 30 PDFs por minuto por usuário → 429 com `Retry-After`.
- [ ] PDF acima de 4 MB → 422 `PDF_TOO_LARGE`, com rollback do `DOWNLOAD`.

### US-002: Conteúdo e layout
**Descrição:** Como dentista, quero um PDF simples e correto.

**Critérios de aceite:**
- [ ] Cabeçalho com nome, endereço e telefone da clínica (campos nulos omitidos) e título "ATESTADO/RECEITUÁRIO/ENCAMINHAMENTO Nº n".
- [ ] Atestado: frase padrão ("Atesto, para os devidos fins, que <nome> esteve em atendimento odontológico nesta data e necessita de <N> dia(s) de afastamento…") + complemento, se houver. Encaminhamento: "Encaminho o(a) paciente <nome> para avaliação e conduta em <especialidade>." + complemento. Receita: texto livre sob "Prescrição:" preservando quebras de linha.
- [ ] Linha de assinatura, nome do dentista e "CRO número/UF"; rodapé "<Tipo> nº n — página x de y".
- [ ] **Sem CPF e sem data de nascimento**; metadados sem nome de paciente; A4, Helvetica `CP1252`, datas `dd/MM/yyyy`.
- [ ] Documento `VOIDED`: marca diagonal "ANULADO" em todas as páginas e "Anulado em dd/MM/aaaa" abaixo do título.

### US-003: Rastro de acesso
**Descrição:** Como responsável pela clínica, quero saber quem baixou cada documento.

**Critérios de aceite:**
- [ ] Cada PDF entregue grava um `DOWNLOAD` (usuário, documento, instante) visível em `GET /audit-log?tableName=clinical_documents&recordId=<id>`.
- [ ] Erro depois da gravação (renderização, tamanho, resposta) desfaz a linha.
- [ ] Nenhum PDF, texto, nome ou CRO em log.

## 3. Requisitos funcionais

- **FR-1:** `DocumentPdfService.pdf(id)` em `@Transactional` com `@PreAuthorize`: carregar, `verify`, `record`, decifrar, montar modelo, renderizar, checar tamanho.
- **FR-2:** `DocumentPdfRenderer` puro (modelo → `byte[]`) e `DocumentPdfModel` imutável.
- **FR-3:** `DocumentDownloadRecorder` por consulta nativa `select cast(core.record_document_download(:id) as text)`, tradução de `42501`/`P0002`/`CR003`/`28000`.
- **FR-4:** `ClinicalDocumentRateLimiter` ganha o balde de PDF; o interceptor passa a cobrir `GET /clinical-documents/*/pdf`.
- **FR-5:** Controller devolve `ResponseEntity<byte[]>` com os cabeçalhos; nome de arquivo sem dado pessoal.
- **FR-6:** Evento `Document.Downloaded` (ids apenas).

## 4. Fora do escopo

- Armazenar o PDF no Storage; URL assinada; envio por e-mail ou WhatsApp.
- Assinatura digital, QR code de validação, layout configurável e logotipo da clínica.
- Snapshot do cabeçalho na emissão (reimpressão usa o dado atual).
- Impressão em lote.

## 5. Considerações técnicas e riscos

- O PDF fica na memória por poucos ms e a transação segura 1 conexão do pool de 10; o IT de pool prova leitura estável com 3 PDFs simultâneos.
- Reimpressão usa nome da clínica e CRO atuais (risco 7 do PRD global).
- Helvetica `CP1252` omite caracteres fora da codificação (como o orçamento).
- `record_document_download` é `SECURITY DEFINER`: o backend só a chama, nunca insere em `audit_log`.
