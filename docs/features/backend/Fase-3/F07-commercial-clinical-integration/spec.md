# Spec — F07 Integração com o núcleo clínico

Complementa `docs/global/backend/Fase-3/spec.md` (seções 4.12, 4.14, 5.1 e 5.2).

## 1. Visão técnica
- **Feature:** F07 Anonimização e auditoria da fase.
- **Stack:** Spring (injeção de `List<AnonymizationContributor>`), `TransactionTemplate` (já usado), Spring Data JPA, `FileStorage`.
- **Abordagem:** mudanças pontuais em `patient` e `record`; implementação da SPI em `commercial`. Sem migrations: usa V028 e V029.

## 2. Dados
- **Banco:** nenhuma mudança. `core.anonymize_patient` (V029) já zera `budgets.notes`, marca `budget_documents.deleted_at` e lança `CR006`; `audit_log.table_name` (V028) já aceita as quatro tabelas.
- **Repositório novo:** `BudgetDocumentRepository.findStorageKeysByPatientId(patientId)` (todas as chaves, inclusive de removidos) e `BudgetRepository.countOpenByPatientId(patientId)` (`status = DRAFT OR status = SENT`).
- **Estado em memória:** nenhum.

## 3. Componentes
| Componente | Responsabilidade |
|---|---|
| `AnonymizationContributor` (F01, `patient.service`) | SPI: `objectKeys(patientId)`, `openItems(patientId)` |
| `BudgetAnonymizationContributor` (`commercial.service`) | Implementação: chaves dos PDFs e contagem de orçamentos `DRAFT`/`SENT`; `@Transactional(readOnly = true)` (participa da transação do chamador) |
| `PatientAnonymizationService` (alterado) | Fase de banco: função → chaves dos exames + dos contribuintes → `openItems`; fase de Storage: `purgeObjects`; tradução de `CR006` |
| `PatientEvents.Anonymized` (alterado) | Contagem total de objetos |
| `AnonymizationResponse` (alterado) | `+ openBudgets` |
| `PatientController` (alterado) | Monta a resposta com `openBudgets` |
| `AuditQueryService` (alterado) | `TABLES` ampliada |
| `StorageKeys` (F01) | `require` aceita as duas formas (usada por `deleteAll`) |

## 4. Lógica
**Anonimizar:** (1) transação do banco (`TransactionTemplate`): `SELECT core.anonymize_patient(:id)`; em seguida, **na mesma transação**, `keys = files.storageKeysOf(patientId) ∪ ⋃ contribuintes.objectKeys(patientId)` e `open = Σ contribuintes.openItems(patientId)`; (2) commit; (3) `files.purgeObjects(keys)`: `StorageException` ou outra exceção → `storageCleanup = PENDING` (evento `StorageCleanupPending`), senão `COMPLETED`; (4) evento `Anonymized(clinicId, patientId, keys.size(), cleanup)`; (5) resposta `AnonymizationResponse(anonymizedAt, cleanup, open)`.

**Tradução:** `translate(RuntimeException)` ganha `CR006` → `BusinessException(CONFLICT, "Quite ou cancele as parcelas em aberto antes de anonimizar.")`; `CR001`–`CR004`, `P0002` e `42501` não mudam. A precedência é do banco.

**Contribuinte:** `objectKeys` devolve as `storage_key` de **todos** os documentos do paciente, já com `deleted_at` (a função do banco acabou de marcá-los); `openItems` conta orçamentos `DRAFT`/`SENT` (aviso).

**Auditoria:** `TABLES = {patients, medical_histories, clinical_notes, patient_files, appointments, budgets, installments, payments, budget_documents}`; filtro `recordId` exige `tableName`; nome fora da lista → 400.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Parcela `OPEN` do paciente | 409 (`CR006`); nada alterado |
| Orçamento `SENT` sem parcelas | Passa; `openBudgets` informa |
| Parcelas só `PAID`/`CANCELED` | Passa; financeiro intacto |
| Storage fora na limpeza | Anonimização mantida; `storageCleanup = PENDING` |
| Envio ou upload concorrente à anonimização | Falha com `CR003` (espera a trava do paciente); a chave listada depois da função cobre o resto |
| `tableName=procedure_types` ou `budget_items` | 400 "tableName desconhecido." |
| `recordId` sem `tableName` | 400 (já existente) |
| Auditoria sem `app.user_id` (`28000`) | Operação inteira reverte; 500 sem detalhes |
| Paciente de outra clínica | 404 |
| Recepção | 403 (rota só D, já existente) |

## 6. Segurança e desempenho
- Só D anonimiza e consulta auditoria (rotas já restritas); a auditoria nunca expõe valor, nome nem observação.
- Log e eventos só com ids e contagens; chaves de Storage nunca em log nem em mensagem de erro.
- A anonimização continua em duas fases (banco e Storage); com 50 orçamentos e dezenas de PDFs a lista de chaves cabe em um lote de exclusão (`DELETE_BATCH = 100`); sem N+1 (uma consulta por contribuinte).
