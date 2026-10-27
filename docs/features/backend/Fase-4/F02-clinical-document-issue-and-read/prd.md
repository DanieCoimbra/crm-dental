# PRD — F02 Emissão e leitura de documentos clínicos

**Derivação:** PRD global da Fase 4, US-P4-02 a US-P4-05 e US-P4-09; FR-2 a FR-6, FR-9 e FR-13; decisões I1 a I7 e I10. Spec global, seções 2.1, 3, 4.1, 4.2, 4.9, 5, 6 e 7.
**Dependências externas:** F01 (`ProfessionalIdentity`, `IntegrityException`, `ConflictMessages`, `ClinicalDocuments` em `CrmProperties`, regras de rota, tradução de `CR012`), `PatientLookup.requireSchedulable/requireExists/briefs`, `AppointmentLookup`, `UserNames`, `ClinicZones`, `AesGcmCipher`, banco V032–V034 (`core.clinical_documents`, numeração, auditoria, anonimização). Requer o access token da Fase 0.

## 1. Introdução

- **Problema:** o dentista precisa emitir atestado, receita e encaminhamento com número sequencial, conteúdo protegido e prova de integridade, e consultá-los depois.
- **Solução:** entidade imutável `ClinicalDocument` com texto cifrado na aplicação, `sha256` sobre o texto cifrado, emissão transacional, lista sem conteúdo e detalhe que confere o hash antes de decifrar.
- **Metas:**
  1. Emissão p95 ≤ 100 ms e detalhe p95 ≤ 50 ms com 100 mil documentos no banco; lista de 20 itens p95 ≤ 50 ms.
  2. O texto nunca fica em claro no banco, em log ou em exceção (verificado por consulta administrativa e teste de higiene por mutação).
  3. Numeração concorrente (20 repetições): números distintos e sequenciais por clínica e tipo, nenhum 500.
  4. Cobertura ≥ 80% em `ClinicalDocumentService`, `DocumentRules`, `DocumentIntegrity` e `DocumentCipher`.

## 2. User stories

### US-001: Emitir documento
**Descrição:** Como dentista, quero emitir um atestado, receita ou encaminhamento para um paciente.

**Critérios de aceite:**
- [ ] `POST /api/v1/patients/{id}/clinical-documents` (só D) com `{documentType, issuedOn?, restDays?, targetSpecialty?, content?, appointmentId?}` devolve 201, `Location: /api/v1/clinical-documents/{id}` e `DocumentResponse` com `number` atribuído pelo banco.
- [ ] `CERTIFICATE`: `restDays` 1 a 90 obrigatório, `content` opcional, `targetSpecialty` proibida. `PRESCRIPTION`: `content` obrigatório (≤ 4000 caracteres e ≤ 16000 bytes), sem `restDays` nem `targetSpecialty`. `REFERRAL`: `targetSpecialty` 2 a 60 e `content` obrigatórios, sem `restDays`.
- [ ] `issuedOn` ausente = hoje no fuso da clínica; futuro → 422 `ISSUED_ON_IN_FUTURE`; anterior a 2000-01-01 → 400.
- [ ] Paciente inexistente ou de outra clínica → 404; inativo ou anonimizado → 409; `appointmentId` de outra clínica → 404, de outro paciente → 422 `APPOINTMENT_OF_OTHER_PATIENT`.
- [ ] Dentista sem CRO → 409 `PROFESSIONAL_REGISTRY_MISSING` (checagem prévia; o banco repete com `CR012`).
- [ ] Mais de 30 emissões por minuto por usuário → 429 com `Retry-After`.
- [ ] Recepção → 403 antes do banco.

### US-002: Proteção do conteúdo
**Descrição:** Como responsável pela clínica, quero que o texto do documento não seja legível no banco nem alterável sem detecção.

**Critérios de aceite:**
- [ ] O banco guarda `content` cifrado (AES-256-GCM, envelope versionado) e `sha256` calculado sobre `v1|TYPE|patient_id|issued_on|rest_days|target_specialty|content(cifrado)`.
- [ ] Ao abrir, o hash é recomputado e comparado em tempo constante; divergência → 500 `integrity`, log com `id` e sem texto.
- [ ] Atestado sem complemento grava a cifra de `""` (40 caracteres), atendendo a constraint do banco.

### US-003: Listar e abrir
**Descrição:** Como dentista, quero ver o histórico de documentos do paciente e abrir um deles.

**Critérios de aceite:**
- [ ] `GET /patients/{id}/clinical-documents?page&size&type` (só D): `size` 1 a 50 (padrão 20), ordem `createdAt DESC, id DESC`, filtro opcional `type`; itens **sem** `content`, `voidReason` e `version`; nome do emissor em lote (sem N+1).
- [ ] `GET /clinical-documents/{id}` (só D) devolve o `DocumentResponse` com o texto em claro; documento de outra clínica ou inexistente → 404.
- [ ] Nunca saem `clinicId`, `sha256`, `createdBy` (id) nem o texto cifrado.

### US-004: Documentos e anonimização do paciente
**Descrição:** Como dentista, quero que a anonimização do paciente também apague o conteúdo clínico.

**Critérios de aceite:**
- [ ] Depois de `POST /patients/{id}/anonymize`, o detalhe responde `state: ANONYMIZED`, `anonymized: true`, com `content`, `restDays`, `targetSpecialty` e `voidReason` nulos; a linha e o número permanecem.
- [ ] A lista continua mostrando os documentos do paciente anonimizado (sem conteúdo).

## 3. Requisitos funcionais

- **FR-1:** Entidade `ClinicalDocument` (`@DynamicUpdate`, colunas do banco `insertable=false`, `content` cifrado sem `@Convert`), `DocumentType`, `DocumentState` e `ClinicalDocumentRepository` com projeção `DocumentSummaryRow`.
- **FR-2:** `DocumentCipher`, `DocumentIntegrity`, `DocumentRules` conforme a spec.
- **FR-3:** `ClinicalDocumentService` (`create`, `list`, `get`) com `@PreAuthorize("hasRole('DENTIST_ADMIN')")` e `saveAndFlush` + `refresh`.
- **FR-4:** `ClinicalDocumentController` e `ClinicalDocumentItemController`; DTOs `DocumentRequests` (`@ValidDocument`), `DocumentResponse`, `DocumentSummaryResponse`.
- **FR-5:** `ClinicalDocumentRateLimiter` (balde de emissão) e `ClinicalDocumentRateLimitInterceptor` na rota de emissão.
- **FR-6:** Eventos de aplicação `ISSUED` com ids apenas e `DocumentAuditLogger` (INFO, sem dado pessoal).
- **FR-7:** `Phase4SchemaValidateIT` ganha `ClinicalDocument`.

## 4. Fora do escopo

- Anulação (F03), PDF e `DOWNLOAD` (F04); edição de documento emitido (não existe: é imutável).
- Modelos/templates, assinatura digital, receita controlada e integração com farmácia.
- Busca textual por conteúdo e exclusão de documento.

## 5. Considerações técnicas e riscos

- A cifra é explícita no service porque o hash cobre o texto cifrado; o `EncryptedStringConverter` cifraria depois do cálculo.
- `number` vem do banco (`pg_advisory_xact_lock` + `max()+1`): reler com `refresh` e nunca calcular no Java. Atrás do pooler 6543 o lock `cdoc:` só se prova com o backend (pendência herdada do banco, fechada em F06).
- `CR003`/`CR005` do banco são a segunda barreira da checagem de paciente; a primeira devolve 404/409 sem abrir caminho para `23514`.
- A emissão não é idempotente: duas requisições iguais criam dois documentos (decisão do produto; a UI deve desabilitar o botão).
