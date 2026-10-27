# Spec — F06 Verificação da Fase 4

## 1. Technical Overview
- **Feature:** suíte transversal de verificação, smoke contra o Supabase real e relatório.
- **Tech Stack:** JUnit 5, Spring Boot Test, Testcontainers PostgreSQL (V001–V035 reais), MockMvc/`TestRestTemplate`, `PdfReader` (OpenPDF), Python 3 (`urllib`) para o smoke.
- **Architecture Approach:** testes de integração por tema, reaproveitando os suportes da Fase 3 (`PerfLimits`, semeadores, utilitários de tenant e de log). Nenhum código de produção novo.

## 2. Data Models & Schema
- Sem mudança. Massa de teste:
  - Desempenho: 100 mil agendamentos, 20 mil pacientes, 1,5 mil orçamentos aprovados, 100 mil documentos clínicos.
  - Dashboard: todos os status, pacientes ativos/inativos, retornos no mês, orçamentos em meses diferentes.
  - Isolamento: duas clínicas com dentista e recepção.

## 3. Component Architecture
Arquivos de teste (nomes sugeridos):
- `Phase4SchemaValidateIT`: `ddl-auto=validate` com `User` e `ClinicalDocument`.
- `Phase4MatrixIT`: papel × endpoint (401/403/2xx).
- `Phase4IsolationIT` e `Phase4RlsOnlyIT`: duas clínicas; filtro Hibernate desligado e papel/tenant forjados.
- `Phase4ConcurrencyIT`: emissões, anulações e PDFs simultâneos (20 repetições).
- `Phase4IntegrityIT`: adulteração de `content`/`sha256`; texto cifrado no banco; falha injetada no PDF → nenhum `DOWNLOAD`.
- `Phase4AuditIT`: `INSERT`, `VOID`, `DOWNLOAD`, `ANONYMIZE` via `GET /audit-log`.
- `Phase4HygieneTest`: captura de logs/exceções/eventos; mutação para provar que o teste falha com vazamento.
- `Phase4PerformanceIT`, `Phase4PoolIT`.
- `smoke_phase4.py` e `cleanup_smoke_phase3_phase4.sql` (já existente em `database/scripts/`).
- `traceability.md` e `report.md`.

## 4. Core Logic & Algorithms
1. Rodar a suíte completa (unitário/web e IT) com cobertura de linhas por pacote (JaCoCo, como nas fases anteriores).
2. Matriz: para cada rota da Fase 4, três chamadas (anônimo, R, D) com status esperado do contrato.
3. Isolamento: criar recurso na clínica A, ler/anular/baixar com token da B → 404; listar na B → vazio.
4. RLS sozinha: desligar o filtro do Hibernate e forjar `app.clinic_id`/papel em conexão do `crm_app`; nada de outra clínica aparece.
5. Concorrência: barreira `CountDownLatch`, 20 repetições; verificar números, status e auditoria.
6. Integridade: `UPDATE` como administrador no `content`/`sha256` (somente no banco de teste) → 500 `integrity`.
7. Atomicidade do PDF: `DocumentPdfRenderer` substituído por um que lança exceção → contar `DOWNLOAD` = 0.
8. Higiene: executar fluxos com texto marcador e verificar ausência do marcador em saídas de log.
9. Desempenho: aquecer, 4 concorrentes no dashboard, p95 ≤ limites da spec 7.2; `Phase4PoolIT` com 3 PDFs e leituras.
10. Real (com confirmação do usuário): subir o backend em `prod`, rodar `smoke_phase4.py`, anotar resultados; o usuário roda a limpeza.

## 5. Error Handling & Edge Cases
- Teste instável por tempo: usar barreiras e relógio injetado; `PerfLimits` dobra com `CI`.
- Smoke que falha no meio deixa dados: a limpeza cobre qualquer clínica `teste-smoke-f3-%`.
- Falta de acesso ao `postgres` no real: conferências somente de leitura via API e catálogo como `crm_app`.
- Divergência entre contrato e implementação: registrada no relatório como achado, nunca "ajustada" no teste.

## 6. Security & Performance
- Smoke com e-mail/senha gerados na hora, nada impresso; nenhum segredo no repositório.
- Dados fictícios; PDFs do smoke não são gravados em disco além do necessário para checar o cabeçalho `%PDF`.
- Duração total da suíte de IT aceitável para execução local (sem meta rígida).
