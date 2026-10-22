# Relatório de rastreabilidade — Fase 1 (backend)

Saída da F06 (`contract.md`, seção 3): cada meta e cada história do PRD global da Fase 1 ligada ao teste que a prova, mais o resultado, a cobertura e as medições desta execução.

## 1. Resultado

| Item | Valor |
|---|---|
| Comando | `./mvnw verify` (em `back-java/`), com Docker Desktop ligado |
| Testes unitários e de fatia web (surefire) | **556**, 0 falhas |
| Testes de integração (failsafe, PostgreSQL 16 real via Testcontainers) | **201**, 0 falhas |
| Tempo total do `mvn verify` | **1 min 37 s** com cache e **2 min 16 s** do zero (meta da spec: ≤ 10 min) |
| Schema | `bootstrap` + migrations V001–V013 reais (`../database`), `ddl-auto=validate` |
| Ambiente da medição | Windows 11, Java 21, Docker Desktop; execução local do mantenedor em 2026-10-22 |
| Máquina limpa | **repetido do zero e verde**: cópia nova do projeto (`back-java/` e `database/`, sem `target`), repositório Maven e distribuição do wrapper vazios (690 artefatos baixados) e as imagens `postgres:16` e `testcontainers/ryuk` removidas e baixadas de novo. 556 unitários + 201 ITs, `BUILD SUCCESS` em **2 min 16 s**, na mesma máquina de desenvolvimento (Docker Desktop, Windows 11, Java 21). Foi uma cópia da árvore de trabalho porque a Fase 1 ainda não foi commitada; um clone de repositório só será equivalente depois do commit. |

Os testes da suíte transversal (`crm_data.phase1.*`) somam 91 casos: `Phase1RoleMatrixWebTest` (50), `Phase1ArchitectureTest` (9), `Phase1ForgedRoleIT` (8), `Phase1TenantIsolationIT` (6), `Phase1EncryptionAtRestIT` (5), `Phase1SchemaValidateIT` (4), `Phase1RoleMatrixIT` (3), `Phase1SearchPerformanceIT` (2), `Phase1UploadPerformanceIT` (2), `Phase1LogHygieneIT` (2).

## 2. Metas do PRD global (seção 1)

| # | Meta | Prova |
|---|---|---|
| 1 | 100% dos endpoints com matriz papel × endpoint; recepção 403 nas rotas de prontuário, listagem e download de exames, auditoria e anonimização | `Phase1RoleMatrixWebTest` (16 rotas × {sem token, R, D}, e R/403 sem tocar nenhum service) e `Phase1RoleMatrixIT` (mesma matriz contra o banco real; o 403 com **0 instruções JDBC**) |
| 2 | Filtro Hibernate desligado e `app.role = RECEPTIONIST` forjado: RLS devolve 0 linhas de prontuário e exames | `Phase1ForgedRoleIT` (5 tabelas × papel real, forjado, ausente e inválido); `Phase1TenantIsolationIT` (filtro desligado, JPA e SQL nativo); `ClinicalRecordIT.forgedReceptionistRole...`; `TenantSessionIT` |
| 3 | Nenhum texto clínico em claro no banco nem nos logs | `Phase1EncryptionAtRestIT` (formato Base64 de toda linha; busca do texto em **todas** as tabelas do schema); `Phase1LogHygieneIT` (varredura de logs, causas e respostas 4xx/5xx). A higiene de log foi validada por mutação: um log do CPF injetado no `PatientService` derruba o teste. |
| 4 | Busca p95 ≤ 100 ms (10 mil pacientes, 4 concorrentes) | `Phase1SearchPerformanceIT` e `PatientsIT.searchP95...` (medidas na seção 5) |
| 5 | `ddl-auto=validate` contra V001–V013; `mvn verify` verde | `Phase1SchemaValidateIT` e o próprio contexto de todos os ITs; resultado da seção 1 |
| 6 | Cobertura ≥ 80% nos services de `patient` e `record` | JaCoCo, seção 4: 97,6% das linhas |

## 3. Histórias (US-P1-01 a US-P1-11)

| História | Testes que a provam |
|---|---|
| **US-P1-01** Cadastrar paciente | `PatientsIT` (cadastro por D e R, normalização, CPF duplicado 409 sem o CPF, mesmo CPF em outra clínica, CPF inválido 400); `PatientControllerWebTest` (todos os erros de validação de uma vez, campos injetados ignorados); `PatientRulesTest`; `PatientServiceTest`; `Phase1RoleMatrix*` (`POST /patients`) |
| **US-P1-02** Buscar e listar | `PatientsIT` (nome sem caixa e sem ignorar acento, curingas como texto, CPF com e sem máscara, `q` curto 400, `size` 51, status, ordem); `Phase1SearchPerformanceIT`; `Phase1TenantIsolationIT` |
| **US-P1-03** Ver e editar | `PatientsIT` (versão otimista, 409 por CPF de outro, anonimizado 409, outra clínica 404); `PatientServiceTest` |
| **US-P1-04** Inativar e reativar | `PatientsIT.deactivateReactivateAndIdempotency`; `PatientControllerWebTest` (só D, sem `DELETE`); `ClinicalRecordIT.inactivePatientStillAcceptsRecords` |
| **US-P1-05** Histórico cifrado | `ClinicalRecordIT` (201/200/409 por versão, cifra em repouso, decifra adulterada → 500, log limpo); `Phase1EncryptionAtRestIT`; `MedicalHistoryServiceTest`; `RecordControllersWebTest`; `EncryptedFieldIT` (Fase 0) |
| **US-P1-06** Notas | `ClinicalRecordIT` (ordem, tolerância de 5 min, nota de outro paciente/clínica 404, anonimizado 409); `ClinicalNoteServiceTest`; `RecordControllersWebTest` |
| **US-P1-07** Anexar exame | `PatientFilesIT` (D e R, sem `SELECT`/`RETURNING`, tipo falsificado, 413, compensação sem órfão, 429); `PatientFilesHttpIT` (Tomcat real: 413 e limite antes do parse do multipart); `FileSignatureTest`, `FileNamesTest`, `PatientFileServiceTest`; `Phase1UploadPerformanceIT`; `Phase1ForgedRoleIT` |
| **US-P1-08** Listar, baixar, remover | `PatientFilesIT` (lista sem `storageKey`, URL de 60 s e `no-store`, 404 para removido/outro paciente/outra clínica, remoção lógica mantém o objeto, recepção 403); `SupabaseFileStorageTest` e `SupabaseFileStorageStreamingTest` |
| **US-P1-09** Anonimizar | `AnonymizationAndAuditIT` (erros da função, anonimização completa coluna a coluna, `PENDING`, concorrência, corrida com upload); `PatientAnonymizationServiceTest` |
| **US-P1-10** Auditoria | `AnonymizationAndAuditIT` (paginação, filtro por registro, `userName`, sem valores, não cruza clínicas); `AuditQueryServiceTest`; `AuditLogControllerWebTest`; `AuditTrailReadOnlyTest` |
| **US-P1-11** Papel e tenant na sessão | `TenantSessionIT` (3 variáveis numa instrução, pool de 1 conexão sem vazamento); `TenantPoolIT`; `Phase1ForgedRoleIT` (papel ausente/inválido, usuário ausente); `Phase1ArchitectureTest` (só o dialeto escreve `app.*`); `GlobalExceptionHandlerTest` |

## 4. Cobertura (JaCoCo 0.8.13, unitários + ITs)

Medida por linha de comando, sem alterar o `pom.xml` (não há gate automático). O `LoginPerformanceIT` fica fora dessa execução porque o agente de cobertura o deixa lento.

| Classe | Linhas | Branches |
|---|---|---|
| `PatientService` | 100% | 97% |
| `PatientRules` | 100% | 100% |
| `PatientLookupService` | 100% | 100% |
| `PatientAnonymizationService` | 100% | 100% |
| `MedicalHistoryService` | 100% | 100% |
| `ClinicalNoteService` | 100% | 88% |
| `PatientFileService` | 97% | 94% |
| `FileSignature` | 100% | 100% |
| `FileNames` | 93% | 85% |
| `AuditQueryService` | 100% | 100% |
| `UploadRateLimiter` | 81% | 38% |
| **Total dos services de `patient` e `record`** | **97,6%** | **94,0%** |

## 5. Desempenho medido (limite de 100 ms; 5 s para upload)

| Medida | Resultado |
|---|---|
| Busca por nome, 10 mil pacientes, 4 concorrentes | p95 = 41 ms |
| Busca por CPF (com e sem máscara) | p95 = 10 ms |
| Linha do tempo com 500 notas (páginas de 50) | p95 = 36 ms |
| Upload de 10 MB pela API (`LocalFileStorage`) | 60 ms |
| Upload de 10 MB pelo service | 55 ms; maior leitura de 16 KB; o Storage recebe o próprio fluxo, nunca um `byte[]` |

O limite é de 100 ms (5 s no upload); com a variável de ambiente `CI` sobe para 200 ms, e `-Dperf.limit-ms=<ms>` sobrescreve nos dois casos (`support/PerfLimits`, usado por `PatientsIT`, `ClinicalRecordIT` e `Phase1SearchPerformanceIT`). Os valores variam por máquina.

## 6. Desvios e observações

- **Defeitos devolvidos às features donas:** nenhum defeito de produção foi encontrado pela suíte.
- **US-P1-11, "sem `app.user_id` falha com `28000`":** o `UPDATE` falha com `28000` (500); o `INSERT` falha antes, pela RLS, com `42501` (403), porque a política exige `created_by = app.user_id`. O contrato da F01 foi corrigido para dizer isso; os dois casos estão em `Phase1ForgedRoleIT`.
- **US-P1-09, "antes da chamada, lista as `storage_key`":** a implementação lista **depois** da função e na mesma transação (a função trava a linha do paciente, o que fecha a corrida com upload). Divergência registrada na spec global (seção 10, item 1) e provada por `AnonymizationAndAuditIT.concurrentUploadAndAnonymizationNeverLeaveAnOrphanObject`.
- **Mensagem do driver:** com RLS forçada o PostgreSQL já omite a linha `Detail` (que repetiria o CPF); o backend continua não logando a mensagem do driver como defesa em profundidade.
- **`FileStorage` de teste:** em vez de uma implementação em memória, os ITs usam o `LocalFileStorage` em disco, espionado (`@MockitoSpyBean`) para injetar falhas. Equivale ao modo de falha injetável da spec.
- **Sessão do banco e teste de duas conexões:** `Phase1SessionVariablesIT` da spec não foi duplicada; `TenantSessionIT` e `TenantPoolIT` (F01) já cobrem a instrução única, o pool de 1 conexão e o fluxo `TenantRunner`.
- **`LoginPerformanceIT` (Fase 0):** oscila perto do limite de 300 ms em máquina carregada (já marcou 316 ms) e falha com o agente do JaCoCo ligado. Não pertence à Fase 1.

## 7. Pendências fora do código

- **Bucket real do Supabase** (ação do usuário): upload, URL assinada e remoção em lote só foram conferidos contra um servidor HTTP falso. Fazer o checklist B1–B6, B9, B11 do `database/README.md` e uma rodada real.
- **Validação jurídica** do prazo de 20 anos antes de dados reais.
- **Exportação dos dados do titular** (pergunta aberta do PRD da F05).
- **Gate de cobertura automático** (plugin JaCoCo no `pom.xml`): decisão do arquiteto.
