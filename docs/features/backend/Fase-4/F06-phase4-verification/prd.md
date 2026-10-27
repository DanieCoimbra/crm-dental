# PRD — F06 Verificação da Fase 4

**Derivação:** PRD global da Fase 4, seção de critérios de conclusão e riscos; spec global, seção 9 (testes) e 7.2 (desempenho). Pendências herdadas do banco: teste 143 (`ddl-auto=validate`), lock `cdoc:` atrás do pooler 6543 e conciliação do código `CR012` (relatório F06 do banco, seção 4).
**Dependências externas:** F01 a F05 implementadas, banco V001–V035 (harness local e Supabase real), `Phase4ArchitectureTest`, convenções de verificação da Fase 3 (F08: `traceability.md`, `smoke_phase3.py`, script de limpeza).

## 1. Introdução

- **Problema:** nenhuma feature da Fase 4 é "concluída" sem prova transversal: matriz de papéis, isolamento entre clínicas, RLS sozinha, concorrência, integridade, higiene de log, desempenho e execução contra o banco real.
- **Solução:** uma suíte de verificação e um relatório rastreável (cláusula do contrato → teste → resultado), mais um smoke da API contra o Supabase real com limpeza.
- **Metas:**
  1. 100% das cláusulas dos contratos F01 a F05 rastreadas a um teste ou a uma evidência.
  2. Suíte unitária/web e de integração verdes com V001–V035 reais; `ddl-auto=validate` com `User` e `ClinicalDocument` (fecha o teste 143 do banco).
  3. Todas as metas de desempenho da spec 7.2 medidas e registradas.
  4. Smoke no Supabase real 100% verde e dados de teste removidos.

## 2. User stories

### US-001: Matriz de segurança
**Descrição:** Como responsável técnico, quero provar quem pode o quê.

**Critérios de aceite:**
- [ ] Matriz papel (D, R, anônimo) × endpoint da Fase 4 (401/403/2xx) em teste web e de integração.
- [ ] Isolamento entre duas clínicas em todas as rotas (404 em recurso alheio); RLS sozinha com o filtro do Hibernate desligado e papel/tenant forjados.
- [ ] DTOs sem `clinicId`, `sha256`, `createdBy`, `number`/`voided*` de entrada; corpo com campos proibidos é ignorado.

### US-002: Integridade, concorrência e atomicidade
**Descrição:** Como responsável técnico, quero provar as garantias do desenho.

**Critérios de aceite:**
- [ ] Adulteração de `content`/`sha256` no banco → 500 `integrity` em detalhe e PDF.
- [ ] 20 repetições de emissões simultâneas, anulações simultâneas e PDFs simultâneos: sem 500 inesperado, números sequenciais, uma auditoria `VOID`.
- [ ] Falha injetada na geração do PDF → zero `DOWNLOAD`; `INSERT` e `VOID` sempre auditados; `ANONYMIZE` por documento.
- [ ] Texto cifrado no banco (consulta administrativa) e **ausente** de logs, exceções, eventos, metadados e nome de arquivo (testes de higiene validados por mutação).

### US-003: Desempenho e pool
**Descrição:** Como responsável técnico, quero as metas medidas.

**Critérios de aceite:**
- [ ] Dashboard ≤ 150 ms, emissão ≤ 100 ms, detalhe ≤ 50 ms, PDF ≤ 300 ms, lista ≤ 50 ms (p95; `PerfLimits` dobra com `CI`) com a massa da spec 7.2.
- [ ] IT de pool: 3 PDFs simultâneos não degradam a leitura (p95 estável).

### US-004: Banco real
**Descrição:** Como responsável técnico, quero confirmar tudo contra o Supabase.

**Critérios de aceite:**
- [ ] `ddl-auto=validate` passa contra o schema real (perfil `prod`).
- [ ] Smoke pela API: CRO, emissão dos três tipos, lista/detalhe, anulação, PDF (cabeçalhos e `%PDF`), auditoria `INSERT`/`VOID`/`DOWNLOAD`, dashboard (D com faturamento, R sem), anonimização do paciente; emissões simultâneas atrás do pooler 6543 com números distintos.
- [ ] `CR012` conciliado (409 `conflict`) e registrado no relatório do banco.
- [ ] Dados do smoke removidos com `cleanup_smoke_phase3_phase4.sql` (feito pelo usuário como `postgres`); 0 clínicas `teste-smoke-%`.

### US-005: Rastreabilidade e relatório
**Critérios de aceite:**
- [ ] `traceability.md` liga cada cláusula de F01 a F05 a teste/evidência; `report.md` registra resultados, cobertura e pendências.
- [ ] Cobertura de linhas ≥ 80% nos pacotes `document` e `dashboard` e nas partes novas de `auth`/`record`.

## 3. Requisitos funcionais

- **FR-1:** `Phase4SchemaValidateIT` (`User`, `ClinicalDocument`), `Phase4MatrixIT`, `Phase4IsolationIT`, `Phase4RlsOnlyIT`, `Phase4ConcurrencyIT`, `Phase4IntegrityIT`, `Phase4AuditIT`, `Phase4HygieneTest`, `Phase4PerformanceIT`, `Phase4PoolIT`.
- **FR-2:** `smoke_phase4.py` (somente API, dados fictícios `teste-smoke-f3-p4-...`, sem imprimir segredos).
- **FR-3:** Relatório e matriz de rastreabilidade em `docs/features/backend/Fase-4/F06-phase4-verification/`.
- **FR-4:** Atualizar o relatório do banco (teste 143 e lock `cdoc:`) quando os testes passarem.

## 4. Fora do escopo

- Teste de carga em produção, pentest externo e CI/CD (removido até o fim do projeto).
- Rotação de segredos, senha do `postgres` vazada, limite do bucket e guarda da chave AES (pendências de segurança do usuário).
- Commit e push (somente a pedido).

## 5. Considerações técnicas e riscos

- O smoke grava dados no Supabase real: exige confirmação do usuário antes de rodar, e a limpeza fica com o usuário (`postgres`).
- O `crm_app` não é dono das tabelas no real; o teste de permissões do harness local continua sendo o do banco.
- A prova do lock `cdoc:` atrás do pooler 6543 só vale com emissões simultâneas reais.
