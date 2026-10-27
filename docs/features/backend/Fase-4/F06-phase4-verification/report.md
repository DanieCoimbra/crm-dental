# Relatório de verificação — Fase 4 (backend)

**Data:** 2026-10-27 · **Escopo:** F01 a F05 · **Ambiente:** Testcontainers PostgreSQL 16 com V001–V035 reais. **Status:** em desenvolvimento até o veredito do QA.

## 1. Resultado da suíte (`./mvnw verify`)
- Unitários e web: **1.399** testes, 0 falhas.
- Integração (`*IT`): **496** testes, 0 falhas. `BUILD SUCCESS`.
- Novos nesta fase: unitários (`ProfileServiceTest`, `ProfessionalIdentityServiceTest`, `DocumentRulesTest`, `DocumentIntegrityTest`, `DocumentCipherTest`, `ClinicalDocumentRateLimiterTest`, `DocumentPdfRendererTest`, `DocumentPdfServiceTest`, `DashboardWindowsTest`, `DashboardServiceTest`, `Phase4ArchitectureTest`, ampliações de `StartupConfigValidatorTest` e `GlobalExceptionHandlerTest`) e ITs (`ProfessionalRegistryIT`, `ClinicalDocumentsIT`, `ClinicalDocumentPdfIT`, `DashboardIT`, `Phase4SchemaValidateIT`, `Phase4MatrixIT`, `Phase4RlsIT`, `Phase4ConcurrencyIT`, `Phase4HygieneIT`, `Phase4PerformanceIT`, `Phase4PoolIT`).
- **Teste 143 do banco (`ddl-auto=validate` da Fase 4): fechado** por `Phase4SchemaValidateIT` e por todos os ITs (as entidades `User` e `ClinicalDocument` sobem contra o schema real).

## 2. Desempenho (p95, massa: 100 mil agendamentos, 20 mil pacientes, 100 mil documentos, RLS ativa)
| Operação | Meta | Medido |
|---|---|---|
| `GET /dashboard` (4 concorrentes) | ≤ 150 ms | 61 ms |
| Emissão | ≤ 100 ms | 26 ms |
| Detalhe | ≤ 50 ms | 17 ms |
| PDF (render + `DOWNLOAD`) | ≤ 300 ms | 20 ms |
| Lista (20 itens) | ≤ 50 ms | 14 ms |
| Leitura com 3 PDFs lentos (700 ms) | ≤ 200 ms | 22 ms, 0 falhas por falta de conexão |

Limite: a massa de orçamentos aprovados (1,5 mil) não foi semeada em escala; o faturamento mensal foi provado por `DashboardIT` e medido no banco na Fase 3.

## 3. Garantias provadas
- **Concorrência (20 repetições):** emissões simultâneas → números 1 e 2 sem erro; anulações simultâneas → uma 200, uma 409, uma linha `VOID`; PDFs simultâneos → todos 200, um `DOWNLOAD` cada. Lock `cdoc:` exercido no PostgreSQL real do harness.
- **Atomicidade do PDF:** falha de renderização e PDF > 4 MB → nenhuma linha `DOWNLOAD`.
- **Integridade:** adulteração de `content` ou `sha256` → 500 `integrity`, sem texto na resposta nem no log.
- **RLS sozinha:** papel ausente/forjado, outra clínica, `DELETE`/`TRUNCATE`/edição de conteúdo negados (`42501`).
- **Higiene:** nenhum texto clínico, motivo, especialidade, nome de paciente ou CRO em log ou erro; detector validado por mutação.

## 4. Achados durante a implementação (corrigidos)
- `Phase4ArchitectureTest` acusou a camada web importando a projeção do repositório → `SummaryView` virou registro próprio do service.
- `CrmProperties` com segundo construtor quebrou o binding → `@ConstructorBinding` no construtor canônico.
- `Phase3SchemaValidateIT` afirmava exatamente 30 migrations → agora `>= 30`.
- `ck_users_cro_role`: a fixture comum cria todos como recepção; a Fase 4 usa `newDentistClinic()`.

## 5. Divergências em relação aos contracts (a conciliar)
- `UserResponse` mantém `clinicId`, `active` (já existentes) e ganha `cro`, `croState`, `version`; o exemplo do contrato da F01 não os listava.
- `ClinicalDocument.markVoided(...)` (e não `void`, palavra reservada).
- Validação por tipo em `DocumentRules` chamada pelo service (sem `@ValidDocument`); o 400 mantém `errors[]`.
- `DashboardResponse` vive em `dashboard.service` (a camada service não depende de `web`); `AppointmentStats` trafega status como texto para o dashboard não importar o domínio de `scheduling`.
- Interceptor de limite cobre emissão e PDF desde a F02.

## 5.1 Smoke no Supabase real (2026-10-27)
Backend `prod` (jar) contra o projeto real, só pela API (`smoke_phase4.py`): **27/27 verificações OK**. `ddl-auto=validate` passou no schema real. Cobriu CRO, emissão dos três tipos, lista, detalhe, anulação, PDF (`%PDF`, cabeçalhos), auditoria `INSERT`/`VOID`/`DOWNLOAD`, dashboard, `weekOf` inválido e anonimização. **6 emissões simultâneas atrás do pooler 6543 receberam os números 2 a 7, sem repetição**: o lock `cdoc:` está provado no real. Clínica fictícia `teste-smoke-f3-p4b-<ts>`; **limpeza pendente com o usuário** (`database/scripts/cleanup_smoke_phase3_phase4.sql`, como `postgres`).

## 5.2 Correções do QA (mesma data)
- `DocumentDownloadRecorderTest` (P0002 → 404, CR003 → 409, outros erros propagam) e teste do selo nulo em `DocumentIntegrityTest`.
- Contratos de F01, F02 e F03 e spec da F05 alinhados ao código (`UserResponse`, `markVoided`, `DashboardResponse` em `service`).
- Cobertura de linhas medida (JaCoCo): `document.service` 94,3%, `document.web` 95,5%, `dashboard.service` 100%.

## 6. Pendências
| # | Pendência | Dono |
|---|---|---|
| 1 | Limpeza dos dados do smoke (`cleanup_smoke_phase3_phase4.sql`) | usuário, como `postgres` |
| 2 | Reauditoria do QA da F06 | `qa-feature-legacy` |
| 4 | Pendências de segurança herdadas (senha do `postgres`, bucket, chave AES) e commit | usuário |
