# PRD — F05 Base de consulta do dashboard (Fase 4)

**Derivação:** PRD global da Fase 4, US-407, decisões D9–D12 e FR-12; spec global, seção 2.6 e S12, S13.
**Dependências externas:** Fase 1 (`patients`), Fase 2 (`appointments`, `ix_appt_clinic_start`, `ix_appt_patient`), Fase 3 (`budgets`, `ix_budgets_decided`). Consumida pelo backend (consultas K1 a K5). Independente de F01–F04.

## 1. Introdução

- **Problema:** o dashboard precisa de quatro KPIs e um gráfico semanal calculados na hora, sem tabela de resumo, para um consultório de uma cadeira.
- **Solução:** o banco não ganha view nem resumo (D9); entrega as consultas de referência com o índice e o plano esperado de cada uma e cria só os índices que a medição exigir (hoje, `ix_appt_completed_patient`).
- **Metas:**
  1. K1 a K5 com p95 ≤ 50 ms com 100 mil agendamentos, 100 mil parcelas, 100 mil pagamentos e 20 mil pacientes na clínica, RLS ativa.
  2. Nenhum `Seq Scan` nas tabelas grandes nos planos de K2 a K5.
  3. Nenhuma view, view materializada ou tabela de resumo.
  4. Recepção continua sem `installments` e `payments`.

## 2. User stories

### US-001: Consultas de referência medidas
**Descrição:** Como backend, quero consultas de referência com plano comprovado para montar o dashboard.

**Critérios de aceite:**
- [ ] K1 a K5 documentadas na spec com parâmetros (`:dia_ini`, `:mes_ini`, `:sem_ini`, `:tz`) calculados pelo backend no fuso da clínica.
- [ ] `EXPLAIN (ANALYZE, BUFFERS)` de cada uma como `crm_app` com RLS ativa registrado no teste, sem `Seq Scan` nas tabelas grandes.
- [ ] Predicados com `<>`/`OR` (nunca `IN` ou `&&` de `tstzrange`).

### US-002: Índice da taxa de retorno
**Descrição:** Como backend, quero a taxa de retorno rápida.

**Critérios de aceite:**
- [ ] `ix_appt_completed_patient ON core.appointments (clinic_id, patient_id, starts_at) WHERE status = 'COMPLETED'` criado na `V035`.
- [ ] A sonda do `EXISTS` de K4 usa `Index Only Scan` neste índice.
- [ ] Índice condicional de K1 (`ix_patients_clinic_active`) **só** se o teste reprovar a meta.

### US-003: Papéis
**Descrição:** Como arquiteto, quero que o dashboard respeite a matriz de papéis existente.

**Critérios de aceite:**
- [ ] Recepção lê `patients`, `appointments` e `budgets` (C2) e **não** lê `installments` nem `payments` (teste).
- [ ] O corte do faturamento (K3) por papel é regra do backend, registrado no contrato.

## 3. Requisitos funcionais
- **FR-1:** Nenhum objeto novo além de índices (D9).
- **FR-2:** `V035` aditiva e reversível (`U035` derruba o índice).
- **FR-3:** Medição com a massa da spec global (6.2); reprovação de K1 antes da aplicação adiciona o índice condicional à própria `V035`.

## 4. Non-goals
- Views, materializadas, `security_invoker`, tabelas de resumo, particionamento, cache.
- Novos KPIs, fluxo de caixa com saídas, `started_at`/`finished_at` (D12).
- Cálculo de janelas de data (é do backend).

## 5. Requisitos de IA
Não se aplica.

## 6. Especificações técnicas
Ver `spec.md`. Janelas: dia, mês e semana (segunda a domingo) em `[ini, fim)` no fuso de `clinics.timezone`.

## 7. Riscos e perguntas abertas
1. A taxa de retorno é a consulta mais cara (janela de 12 meses por paciente); medir com a massa cheia.
2. Predicados sob RLS: `IN` e `&&` perdem índice (lição das Fases 2 e 3).
3. Em tabela real grande, o índice deve ser criado `CONCURRENTLY` à mão.
- Perguntas abertas: nenhuma.
