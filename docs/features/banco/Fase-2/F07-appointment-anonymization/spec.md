# Spec — F07 Anonimização e agendamentos (Fase 2)

Complementa `docs/global/banco/Fase-2/spec.md` (seção 4.4) e a spec da F06 da Fase 1 (`core.anonymize_patient`).

## 1. Visão técnica
- **Feature:** ajuste de `core.anonymize_patient` e privilégios do `crm_definer` em `appointments`.
- **Stack:** PL/pgSQL `SECURITY DEFINER`, migration `V021`.
- **Abordagem:** `CREATE OR REPLACE` da função da V013 com dois passos novos, mais grants e políticas escopadas ao tenant para o definer.

## 2. Objetos (`V021__core_appointments_anonymization.sql`, como `crm_owner`, transação única)

### 2.1 Privilégios e políticas do `crm_definer`
```sql
GRANT SELECT                 ON core.appointments TO crm_definer;
GRANT UPDATE (notes, version) ON core.appointments TO crm_definer;

CREATE POLICY appt_definer_sel ON core.appointments FOR SELECT TO crm_definer
  USING (clinic_id = core.current_clinic_id());
CREATE POLICY appt_definer_upd ON core.appointments FOR UPDATE TO crm_definer
  USING (clinic_id = core.current_clinic_id())
  WITH CHECK (clinic_id = core.current_clinic_id());
```

### 2.2 Função (substitui a da V013; dono e `EXECUTE` do `crm_app` permanecem)
Se necessário, envolver com `GRANT CREATE ON SCHEMA core TO crm_definer` / `REVOKE CREATE` (padrão da V013). Os trechos novos estão marcados `-- NOVO`; o resto é a função da V013 sem alteração.

```sql
CREATE OR REPLACE FUNCTION core.anonymize_patient(p_patient_id uuid) RETURNS timestamptz
  LANGUAGE plpgsql VOLATILE SECURITY DEFINER
  SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  v_clinic uuid; v_active boolean; v_anonymized timestamptz;
  v_last timestamptz; v_until timestamptz; v_now timestamptz := pg_catalog.now();
BEGIN
  -- 1) papel e sessão; 2) SELECT ... FOR UPDATE; 3) CR003 / CR001   (inalterados)
  -- 4) retenção: último registro clínico + core.clinical_retention_period()  (inalterada;
  --    NÃO considera agendamentos)

  -- NOVO: agendamento futuro em aberto impede a anonimização
  IF EXISTS (SELECT 1 FROM core.appointments a
              WHERE a.patient_id = p_patient_id
                AND (a.status = 'SCHEDULED' OR a.status = 'CONFIRMED' OR a.status = 'IN_PROGRESS')
                AND a.ends_at > v_now) THEN
    RAISE EXCEPTION 'cancele os agendamentos futuros antes de anonimizar' USING ERRCODE = 'CR004';
  END IF;

  PERFORM set_config('core.anonymizing', 'on', true);

  -- DELETE de medical_histories e clinical_notes; UPDATE de patient_files   (inalterados)

  -- NOVO: texto livre da agenda; horário, status e paciente permanecem
  UPDATE core.appointments
     SET notes = NULL, version = version + 1
   WHERE patient_id = p_patient_id AND notes IS NOT NULL;

  -- UPDATE de patients; INSERT 'ANONYMIZE' em audit_log; set_config('core.anonymizing','off'); RETURN v_now  (inalterados)
END $$;
```
O `CR004` fica **depois** da checagem de retenção (condição permanente antes da transitória) e **antes** do `set_config('core.anonymizing', 'on', true)`. Cada `UPDATE` de `notes` dispara os triggers de `appointments`: `set_updated_at`, `forbid_immutable` (colunas imutáveis intactas), `guard_patient` (retorna sem consultar: o status não muda) e `write_audit` (usa `app.user_id`, já validado no passo 1).

## 3. Componentes
`V021`, `U021`, ampliação de `tests/anonymize_patient*.sql`, endpoint de anonimização do backend (tradução de `CR004`).

## 4. Lógica
1. Passos 1 a 4 como na Fase 1 (papel, sessão, existência, `CR003`, `CR001`, retenção).
2. Se há agendamento do paciente `SCHEDULED`/`CONFIRMED`/`IN_PROGRESS` com `ends_at > now()`: `CR004`.
3. Liga `core.anonymizing`; apaga prontuário; marca exames como removidos.
4. Zera `notes` dos agendamentos (versão +1; auditoria por linha).
5. Substitui os dados pessoais do paciente, grava `ANONYMIZE`, desliga a flag, devolve o instante.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Agendamento futuro `SCHEDULED`/`CONFIRMED`/`IN_PROGRESS` | `CR004`; nada muda |
| Futuros só `CANCELED`/`NO_SHOW`, ou só passados | Passa |
| Agendamento passado ainda `SCHEDULED` | Passa (o backend deve fechar a agenda) |
| Paciente sem registro clínico, inativo | Anonimiza na hora (S4 da Fase 1); agendamentos passados mantidos |
| Retenção de 20 anos não vencida | `CR002` vem antes do `CR004` |
| Paciente sem agendamentos | Comportamento idêntico à Fase 1 |
| 500 agendamentos do paciente | Um `UPDATE` em lote; dentro do `statement_timeout` de 15 s |
| Anonimização concorrente com novo agendamento | O trigger `guard_patient` espera (`FOR KEY SHARE`) e o paciente já anonimizado devolve `CR003` |
| Falta `app.user_id` | `28000` (passo 1) |
| `crm_definer` tentando alterar outra coluna de `appointments` | Falta de privilégio (`42501`) |

## 6. Segurança e desempenho
- Privilégio mínimo: `SELECT` e `UPDATE (notes, version)`; políticas escopadas ao tenant, sem `USING (true)`.
- `SECURITY DEFINER` com `search_path` fixo, sem SQL dinâmico, dono sem login e sem `BYPASSRLS`.
- `ix_appt_patient` atende o `EXISTS` do `CR004` e o `UPDATE` de `notes` por `patient_id`.
- A auditoria continua sem dado pessoal.
