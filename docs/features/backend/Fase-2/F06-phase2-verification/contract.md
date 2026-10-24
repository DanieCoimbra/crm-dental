# Contract — F06 Verificação da fase

## 1. Resumo
- **Feature:** F06-phase2-verification
- **Versão:** 1.0.0
- **Consumidores:** o time (gate de qualidade da Fase 2) e o QA. Não expõe endpoints nem interfaces de produção.

## 2. Entradas
- Código das features F01 a F05 implementado em `back-java/`.
- `database/bootstrap` e `database/migrations` (V001–V021).
- Docker em execução (Testcontainers).

## 3. Saídas
- Suíte de verificação em `back-java/src/test/java/crm_data/phase2/`: `SchedulingRoleMatrixWebTest` (24 rotas × papéis), `SchedulingRoleMatrixIT` (zero instruções SQL nas negações), `SchedulingForgedRoleIT` (RLS com SQL direto: papel forjado, ausente e inválido, e isolamento entre clínicas), `SchedulingPerformanceIT`, `SchedulingLogHygieneIT`, `Phase2SchemaValidateIT` e `Phase2ArchitectureTest`. A concorrência (20 rodadas) vive em `AppointmentsIT`, a das notas em `ClinicalIntegrationIT`; os auxiliares são `Phase2ItSupport` e `AgendaDates`.
- `docs/features/backend/Fase-2/F06-phase2-verification/traceability.md` (história → testes).
- Resultado `mvn verify` e relatório de cobertura.

### Falhas esperadas (o que reprova a fase)
| Situação | Consequência |
|---|---|
| Qualquer 500 no teste de concorrência | Reprova |
| Linha de outra clínica em qualquer consulta com o filtro desligado | Reprova |
| Recepção grava `IN_PROGRESS`/`COMPLETED` com papel forjado | Reprova |
| `notes`, nome ou CPF em log | Reprova |
| p95 acima do limite sem justificativa | Reprova |
| Cobertura < 80% nos services | Reprova |

## 4. Regras e limites
- Sem código de produção novo nesta feature; correções encontradas voltam à feature de origem.
- Limites de desempenho sobrescrevíveis por `-Dperf.limit-ms`; 200 ms com `CI`.
- Os testes não podem depender de ordem nem de dados residuais.

## 5. Eventos emitidos (internos)
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] `mvn verify` verde em máquina limpa com Docker ligado.
- [ ] Matriz papel × endpoint 100% das rotas novas e alteradas.
- [ ] Concorrência: 20 repetições, um 201 e um 409, nenhum 500; clínicas diferentes passam.
- [ ] Isolamento e papel forjado verdes; `ddl-auto=validate` verde contra V001–V021.
- [ ] Agenda da semana p95 ≤ 100 ms (20 mil agendamentos); sem `Seq Scan`.
- [ ] Nenhum `notes`, nome de paciente ou CPF em log, `toString` ou erro (validado por mutação).
- [ ] Cobertura ≥ 80% nos services de `scheduling` e nos alterados de `patient`/`record`.
- [ ] Suíte das Fases 0 e 1 verde; `traceability.md` cobre US-P2-01 a US-P2-14.
