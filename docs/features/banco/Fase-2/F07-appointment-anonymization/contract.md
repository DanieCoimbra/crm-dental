# Contract — F07 Anonimização e agendamentos (Fase 2)

## 1. Resumo
- **Feature:** F07-appointment-anonymization (Fase 2)
- **Versão:** 1.0.0
- **Consumidores:** backend (módulo `patient`, endpoint de anonimização); `crm_backup`.

## 2. Entradas

Mesma assinatura da Fase 1: `SELECT core.anonymize_patient(p_patient_id uuid)`, chamada por `crm_app` com `app.clinic_id`, `app.role = DENTIST_ADMIN` e `app.user_id` definidos na transação.

Pré-condições (as da Fase 1, mais uma): paciente da clínica, **inativo**, retenção vencida (ou sem registro clínico) e **sem agendamento futuro em aberto**.

## 3. Saídas

- **Sucesso:** `timestamptz` com o instante da anonimização (`patients.anonymized_at`).
- **Efeito novo na agenda:** `appointments.notes` do paciente ficam `NULL` e `version` sobe 1; `starts_at`, `ends_at`, `status` e `patient_id` permanecem; cada agendamento limpo gera uma linha `appointments`/`UPDATE` no `audit_log`.

### Erros
| SQLSTATE | Causa | Resposta sugerida |
|---|---|---|
| `42501` | Papel não é `DENTIST_ADMIN` | 403 |
| `28000` | Sessão sem clínica ou usuário | 500 |
| `P0002` | Paciente inexistente na clínica | 404 |
| `CR001` | Paciente ativo | 409 (inative antes) |
| `CR002` | Retenção não vencida (`DETAIL` com a data) | 409 com a data liberada |
| `CR003` | Já anonimizado | 409 |
| **`CR004`** | **Agendamento futuro `SCHEDULED`/`CONFIRMED`/`IN_PROGRESS` com `ends_at > now()`** | **409 (cancele os agendamentos futuros)** |

## 4. Regras e limites
- Futuros `CANCELED`/`NO_SHOW` e agendamentos passados não bloqueiam.
- O backend, ao **inativar** o paciente, cancela os agendamentos futuros em aberto; assim o `CR004` só aparece por esquecimento ou por agendamento criado depois.
- Agendamentos **não** são registro clínico: não entram no cálculo da retenção de 20 anos.
- A limpeza de `notes` é irreversível; horário, status e `patient_id` ficam (não identificam ninguém sozinhos).
- `crm_definer` só tem `SELECT` e `UPDATE (notes, version)` em `appointments`, escopados ao tenant.
- Os demais efeitos (prontuário apagado, exames marcados como removidos, dados pessoais substituídos, `ANONYMIZE` no log, limpeza do Storage pelo backend) são os da Fase 1.
- Dumps do `crm_backup` guardam o dado por até 30 dias após a anonimização (política de privacidade, Fase 1).

## 5. Eventos emitidos
`ANONYMIZE` no `audit_log` (como na Fase 1); mais uma linha `appointments`/`UPDATE` por agendamento limpo.

## 6. Critérios de aceite (integração)
- [ ] Teste 77: `CR004` com agendamento futuro `SCHEDULED`/`CONFIRMED`/`IN_PROGRESS`; passa com futuros só `CANCELED`/`NO_SHOW` ou só passados; `notes` ficam `NULL`; horário, status e paciente permanecem; uma linha de auditoria `UPDATE` por agendamento limpo, mais o `ANONYMIZE`.
- [ ] Teste 78: paciente sem registro clínico, inativo e só com agendamentos passados anonimiza na hora; a retenção ignora agendamentos; os testes 46–51 da Fase 1 continuam verdes.
- [ ] Teste 79 (parte): `crm_definer` só com `SELECT` e `UPDATE (notes, version)` em `appointments`.
- [ ] Teste 82 (parte): V021 aplica do zero; `U021` restaura a função da V013 e reaplica.
