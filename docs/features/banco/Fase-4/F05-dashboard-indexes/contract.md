# Contract — F05 Base de consulta do dashboard (Fase 4)

## 1. Resumo
- **Feature:** F05-dashboard-indexes (Fase 4)
- **Versão:** 1.0.0
- **Consumidores:** backend (módulo de dashboard); `crm_backup` (índice não afeta).

## 2. Entradas
O backend define `app.clinic_id`, `app.role` e `app.user_id` na transação e executa **consultas de leitura** como `crm_app`. Os parâmetros de janela são `timestamptz` calculados pelo backend no fuso de `clinics.timezone`:

| Parâmetro | Significado |
|---|---|
| `:dia_ini`, `:dia_fim` | hoje, `[00:00, 00:00 do dia seguinte)` |
| `:mes_ini`, `:mes_fim` | mês corrente `[dia 1, dia 1 do mês seguinte)` |
| `:ano_ini` | `:mes_ini` menos 12 meses |
| `:sem_ini`, `:sem_fim` | semana corrente `[segunda 00:00, próxima segunda 00:00)` |
| `:tz` | `clinics.timezone` (nome IANA) |

Consultas K1 a K5: ver `spec.md`, seção 3 (são o contrato; o backend usa exatamente esses predicados).

## 3. Saídas
| KPI | Resultado | Quem vê |
|---|---|---|
| K1 total de pacientes | `count` | dentista e recepção |
| K2 consultas hoje | `count` | dentista e recepção |
| K3 faturamento do mês | `numeric(12,2)` | **só o dentista (regra do backend)** |
| K4 taxa de retorno | `retornos` e `atendidos` (o backend divide) | dentista e recepção |
| K5 gráfico semanal | linhas `(dia, status, count)` | dentista e recepção |

### Erros
Nenhum específico. Falha de sessão sem `app.clinic_id` devolve 0 linhas (RLS), nunca dados de outra clínica.

## 4. Regras e limites
- Faturamento = orçamentos `APPROVED` por `decided_at` (competência, Fase 3). Entradas de caixa (`payments`) não fazem parte do dashboard desta fase.
- A recepção lê `budgets` no banco (C2): o corte de K3 é do backend (S16).
- Usar `<>`/`OR`; não usar `IN` nem `tstzrange &&` nos predicados.
- Semana de segunda a domingo; status de K5 devolvidos crus, o agrupamento visual é do front.
- Sem cache obrigatório no banco; o backend pode cachear por curtos intervalos por sua conta.

## 5. Eventos emitidos
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] Teste 137: K1 a K5 ≤ 50 ms p95 com a massa cheia, sem `Seq Scan` nas tabelas grandes, plano de K4 com `Index Only Scan` na sonda.
- [ ] Teste 139 (parte): recepção sem `installments`/`payments`, com `patients`, `appointments`, `budgets`.
- [ ] Teste 140 (parte): `V035` aplica do zero; `U035` reverte e reaplica.
