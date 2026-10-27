# Rastreabilidade — Fase 4 (backend)

Cláusula do `contract.md` → teste que a prova. Todos os ITs rodam contra PostgreSQL 16 real (Testcontainers, V001–V035). `U` = unitário/web; `IT` = integração.

## F01 — CRO e plataforma
| Cláusula | Prova |
|---|---|
| D grava, troca e apaga o CRO (200); `GET /me` reflete; R vê `null` | `ProfessionalRegistryIT` (dentistRegisters…, meReturnsTheCro…) |
| R → 403, anônimo → 401 na rota nova | `ProfessionalRegistryIT.receptionAndAnonymous…`, `Phase4MatrixIT` |
| CRO sem UF, UF minúscula/inválida, letra, 16 dígitos → 400 | `ProfessionalRegistryIT.invalidRegistry…`, `ProfileServiceTest` (U) |
| `version` defasada → 409 | `ProfessionalRegistryIT.staleVersion…`, `ProfileServiceTest` |
| `PUT /me` não altera o CRO | `ProfessionalRegistryIT.updatingTheProfile…`, `ProfileServiceTest` |
| `ProfessionalIdentity` (`current`, `of`, outra clínica vazio) | `ProfessionalIdentityServiceTest` |
| `AuditAction` `VOID`/`DOWNLOAD`; `clinical_documents` aceito | `AuditTrailReadOnlyTest`, `Phase4SchemaValidateIT`, `ClinicalDocumentsIT.voidMarks…` |
| `CR012` → 409; `IntegrityException` → 500 `integrity`; `23514` residual → 500 | `GlobalExceptionHandlerTest` |
| Propriedades 1–600, padrão 30 | `StartupConfigValidatorTest` |
| Regras de rota; arquitetura | `Phase4MatrixIT`, `Phase4ArchitectureTest` |

## F02 — Emissão e leitura
| Cláusula | Prova |
|---|---|
| Três tipos, números sequenciais por tipo, `Location`, `no-store` | `ClinicalDocumentsIT` (dentistIssues…, locationPoints…) |
| Combinações por tipo, limites de texto, `issuedOn` | `DocumentRulesTest` (U), `ClinicalDocumentsIT.invalidCombinations…`, `futureIssueDate…` |
| CRO ausente, paciente inativo/outra clínica, consulta inexistente | `ClinicalDocumentsIT` (dentistWithoutCro…, inactivePatient…, anotherClinic…) |
| Texto cifrado no banco; selo; adulteração → 500 `integrity` | `ClinicalDocumentsIT.theTextIsEncrypted…`, `tamperedContent…`; `DocumentCipherTest`, `DocumentIntegrityTest` |
| Lista sem `content`, ordem, filtro, `size` ≤ 50, sem N+1 | `ClinicalDocumentsIT.listHasNoContent…` |
| Campos forjados ignorados | `ClinicalDocumentsIT.forgedFields…` |
| Anonimização preserva a linha e o número | `ClinicalDocumentsIT.anonymizingThePatient…` |
| 31ª emissão → 429 | `ClinicalDocumentRateLimiterTest` (U) |
| Numeração concorrente (20 repetições) | `Phase4ConcurrencyIT.simultaneousIssues…` |
| Higiene de log | `Phase4HygieneIT` (com mutação) |
| `ddl-auto=validate` | `Phase4SchemaValidateIT` |

## F03 — Anulação
| Cláusula | Prova |
|---|---|
| 200 `VOIDED`, motivo aparado, auditoria `VOID` | `ClinicalDocumentsIT.voidMarks…` |
| Já anulado/anonimizado, versão velha → 409; motivo > 200 → 400 | `ClinicalDocumentsIT.voidMarks…`, `voidWithStaleVersion…`, `anonymizing…` |
| Duas anulações simultâneas: uma 200, uma 409, uma linha `VOID` | `Phase4ConcurrencyIT.simultaneousVoids…` |
| R → 403; outra clínica → 404 | `ClinicalDocumentsIT.receptionAndAnonymous…`, `anotherClinicSeesNothing` |

## F04 — PDF
| Cláusula | Prova |
|---|---|
| Cabeçalhos exatos, `%PDF`, nome do arquivo por tipo | `ClinicalDocumentPdfIT.dentistGetsAnInlinePdf…`, `prescriptionAndReferral…` |
| Texto: frase padrão, CRO, sem CPF/nascimento, metadados sem paciente | `DocumentPdfRendererTest` (U) |
| "ANULADO" em todas as páginas | `DocumentPdfRendererTest.voidedDocument…`, `ClinicalDocumentPdfIT.voidedDocumentPdf…` |
| Um `DOWNLOAD` por PDF; falha/4 MB desfaz | `ClinicalDocumentPdfIT` (everyDeliveredPdf…, renderingFailure…, pdfAboveFourMegabytes…), `DocumentPdfServiceTest` |
| Anonimizado → 409; R → 403; outra clínica → 404 | `ClinicalDocumentPdfIT` |
| 31º PDF → 429 | `ClinicalDocumentPdfIT.thirtyFirstPdf…` |
| PDFs simultâneos, cada um com o seu `DOWNLOAD` | `Phase4ConcurrencyIT.simultaneousPdfs…` |
| Pool com 3 PDFs lentos | `Phase4PoolIT` |

## F05 — Dashboard
| Cláusula | Prova |
|---|---|
| K1–K5 contra contagens independentes | `DashboardIT.allIndicatorsMatch…` |
| Recepção sem `monthlyRevenue` e sem consulta de faturamento | `DashboardIT.receptionGetsEverything…`, `DashboardServiceTest` |
| `percent` 1 casa `HALF_UP`, `null` sem atendidos | `DashboardServiceTest` |
| Janelas no fuso, virada de mês/ano, segunda–domingo | `DashboardWindowsTest`, `DashboardIT.weekOfSelects…` |
| `weekOf` inválido → 400; fora do intervalo → 422 | `DashboardIT.invalidOrOutOfRange…`, `DashboardServiceTest` |
| Clínica vazia; outra clínica não interfere | `DashboardIT` (emptyClinic…, anotherClinicData…) |

## Transversais (F06)
| Item | Prova |
|---|---|
| Matriz papel × endpoint | `Phase4MatrixIT` |
| RLS sozinha, papel forjado/ausente, `DELETE`/`TRUNCATE`/`UPDATE` negados | `Phase4RlsIT` |
| Desempenho com massa | `Phase4PerformanceIT` |
| Banco real | `smoke_phase4.py` (pendente de confirmação do usuário) |
