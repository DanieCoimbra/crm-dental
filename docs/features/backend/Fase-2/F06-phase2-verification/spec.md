# Spec — F06 Verificação da fase

Complementa `docs/global/backend/Fase-2/spec.md` (seção 8) e o PRD global (metas da seção 1).

## 1. Visão técnica
- **Feature:** F06 Verificação da fase.
- **Stack:** JUnit 5, Mockito, `@WebMvcTest` + `spring-security-test`, Testcontainers PostgreSQL, ArchUnit, `RecordingStatementInspector` (Fase 1), `support/PerfLimits`.
- **Abordagem:** nenhuma classe de produção nova. Auxiliar `Phase2ItSupport` (ou extensão do `Phase1ItSupport`) que aplica `database/bootstrap` e V001–V021.

## 2. Dados
- Nenhuma alteração de schema. Fixtures: duas clínicas (A e B), dentista e recepcionista em cada uma, 20 mil agendamentos na clínica A para o teste de desempenho (~8 por dia, sem sobreposição), feriados nacionais do seed.

## 3. Componentes de teste
| Componente | Responsabilidade |
|---|---|
| `SchedulingRoleMatrixTest` | 401/403/2xx das rotas novas e alteradas; contagem de statements = 0 nas negações |
| `AppointmentConcurrencyIT` | Duas threads, transações separadas, 20 repetições; clínicas diferentes passam |
| `SchedulingIsolationIT` | Filtro Hibernate desligado: agenda, horário comercial, feriados da clínica, histórico (inclusive SQL nativo) |
| `SchedulingForgedRoleIT` | `app.role = RECEPTIONIST` forjado: estados e tabelas protegidos; papel ausente/inválido |
| `SchedulingPerformanceIT` | Agenda da semana e criação; `EXPLAIN` sem `Seq Scan` |
| `SchedulingLogHygieneIT` | Captura de log em fluxos felizes e de erro; validado por mutação |
| `SchemaValidateIT` | `ddl-auto=validate` contra V001–V021 |
| `Phase2ArchitectureTest` | Regras de dependência entre módulos |
| `traceability.md` | Mapa US-P2-01…14 → testes |

## 4. Lógica
1. Subir PostgreSQL via Testcontainers com bootstrap e V001–V021.
2. Rodar a matriz web, os ITs e a arquitetura; reexecutar a suíte das Fases 0 e 1.
3. Medir cobertura (JaCoCo) nos services de `scheduling` e nos alterados de `patient`/`record`.
4. Preencher a rastreabilidade ao final.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Flakiness da concorrência | Sincronização por latch; falha se houver qualquer 500 |
| Limite de desempenho em máquina lenta | `-Dperf.limit-ms` (200 ms com `CI`) |
| Docker indisponível | Os ITs falham explicitamente (não são pulados) |
| Testes da Fase 1 que inativam pacientes | Ajustados para considerar o cancelamento dos futuros |

## 6. Segurança e desempenho
- Os testes de papel forjado e isolamento são a prova de defesa em profundidade (filtro + RLS + papel).
- Metas: agenda da semana p95 ≤ 100 ms; criação p95 ≤ 100 ms; cobertura ≥ 80% nos services.
- Nenhum dado real; fixtures sintéticas.
