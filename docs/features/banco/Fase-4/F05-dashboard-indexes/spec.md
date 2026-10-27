# Spec — F05 Base de consulta do dashboard (Fase 4)

## 1. Visão técnica
- **Feature:** índices e consultas de referência do dashboard.
- **Stack:** PostgreSQL (índice parcial), `EXPLAIN`.
- **Abordagem:** `V035__core_dashboard_indexes.sql`, como `crm_owner`, transacional. Sem função, sem view.

## 2. Objetos

```sql
CREATE INDEX ix_appt_completed_patient
  ON core.appointments (clinic_id, patient_id, starts_at)
  WHERE status = 'COMPLETED';
```
Condicional (só se `phase4_performance` reprovar K1 antes da aplicação):
```sql
CREATE INDEX ix_patients_clinic_active ON core.patients (clinic_id) WHERE active = true;
```

Índices existentes aproveitados: `ix_appt_clinic_start (clinic_id, starts_at, id)`, `ix_appt_patient (clinic_id, patient_id, starts_at DESC, id DESC)`, `ix_patients_clinic_name`, `ix_budgets_decided (clinic_id, decided_at, id) WHERE status = 'APPROVED'`.

## 3. Consultas de referência (parâmetros do backend, `[ini, fim)` no fuso da clínica)

```sql
-- K1 total de pacientes
SELECT count(*) FROM core.patients WHERE clinic_id = :c AND active = true;
-- K2 consultas hoje
SELECT count(*) FROM core.appointments
 WHERE clinic_id = :c AND starts_at >= :dia_ini AND starts_at < :dia_fim
   AND status <> 'CANCELED' AND status <> 'NO_SHOW';
-- K3 faturamento do mês (competência)
SELECT COALESCE(sum(total_amount), 0) FROM core.budgets
 WHERE clinic_id = :c AND status = 'APPROVED' AND decided_at >= :mes_ini AND decided_at < :mes_fim;
-- K4 taxa de retorno (:ano_ini = :mes_ini - 12 meses)
WITH m AS (SELECT DISTINCT patient_id FROM core.appointments
            WHERE clinic_id = :c AND status = 'COMPLETED' AND starts_at >= :mes_ini AND starts_at < :mes_fim)
SELECT count(*) AS atendidos,
       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM core.appointments p
                                       WHERE p.clinic_id = :c AND p.patient_id = m.patient_id AND p.status = 'COMPLETED'
                                         AND p.starts_at >= :ano_ini AND p.starts_at < :mes_ini)) AS retornos
  FROM m;
-- K5 gráfico semanal (segunda a domingo)
SELECT (starts_at AT TIME ZONE :tz)::date AS dia, status, count(*)
  FROM core.appointments WHERE clinic_id = :c AND starts_at >= :sem_ini AND starts_at < :sem_fim
 GROUP BY 1, 2;
```
Taxa de retorno = `retornos / NULLIF(atendidos, 0)`.

| KPI | Índice | Plano esperado |
|---|---|---|
| K1 | `ix_patients_clinic_name` (ou o condicional) | índice por `clinic_id`, filtro `active` |
| K2, K5 | `ix_appt_clinic_start` | `Index Scan` por faixa de `starts_at` |
| K3 | `ix_budgets_decided` | `Index Scan` por faixa de `decided_at` |
| K4 | `ix_appt_clinic_start` (mês) + `ix_appt_completed_patient` (sonda) | `Index Only Scan` na sonda |

## 4. Lógica
1. Montar a massa (100 mil agendamentos, 100 mil parcelas e pagamentos, 20 mil pacientes numa clínica; duas clínicas) como `crm_owner` com `SET LOCAL session_replication_role = replica`, depois `ANALYZE`.
2. Para cada K, `SET LOCAL ROLE crm_app`, variáveis de sessão da clínica e do papel, `EXPLAIN (ANALYZE, BUFFERS)`; asserir ausência de `Seq Scan` nas tabelas grandes e tempo ≤ 50 ms (mediana de 5 execuções).
3. Se K1 reprovar, incluir `ix_patients_clinic_active` na `V035` e repetir.

## 5. Erros e bordas
| Cenário | Resultado |
|---|---|
| Janela no fuso errado | cálculo do backend; o banco não valida |
| Semana com virada de mês/ano | janela `[segunda, próxima segunda)`; K5 agrupa por dia local |
| Clínica sem atendimentos | K4 devolve `atendidos = 0`; o backend evita divisão por zero |
| Recepção consulta `installments`/`payments` | 0 linhas (RLS) |
| Predicado com `IN` ou `&&` de `tstzrange` | perde índice sob RLS: proibido nas consultas de referência |

## 6. Segurança e desempenho
- Sem objeto novo, sem privilégio novo; índice parcial pequeno.
- Metas: K1–K5 p95 ≤ 50 ms; criação do índice < 1 s na massa do teste.
- Rollback `U035`: `DROP INDEX` (e do condicional, se existir).
- Tabela real grande: criar à mão com `CREATE INDEX CONCURRENTLY` como `crm_owner` (fora da transação do `apply_migrations.sh`).
