# Contract — F06 Retenção e Anonimização de Paciente (Fase 1)

## 1. Resumo
- **Feature:** F06-patient-anonymization (Fase 1)
- **Versão:** 1.0.0
- **Consumidores:** backend (rota de anonimização do dentista e limpeza do Storage).

## 2. Entradas

| Função | Parâmetro | Regra |
|---|---|---|
| `core.anonymize_patient(p_patient_id uuid)` | id do paciente | Da clínica da sessão; inativo; fora da retenção |

Pré-condições da sessão: `app.clinic_id`, `app.role = DENTIST_ADMIN`, `app.user_id`, tudo na mesma transação. Somente `crm_app` executa.

## 3. Saídas

`timestamptz` — o instante gravado em `patients.anonymized_at`.

Efeitos: `medical_histories` e `clinical_notes` do paciente apagadas; `patient_files` com `deleted_at` e `original_filename = 'anonimizado'` (`storage_key` preservada); `patients` com nome `Paciente anonimizado`, `cpf` e demais dados pessoais nulos, `phone = '0000000000'`, `sex = 'NOT_INFORMED'`, `birth_date` reduzida a 1º de janeiro do ano, `active = false`, `version + 1`; linhas em `audit_log` (`DELETE`, `UPDATE`, `ANONYMIZE`).

### Erros
| SQLSTATE | Significado | HTTP sugerido |
|---|---|---|
| `42501` | Papel diferente de `DENTIST_ADMIN`, ou tentativa de gravar `anonymized_at` fora da função | 403 |
| `28000` | Sessão sem clínica ou usuário | 500 |
| `P0002` | Paciente inexistente na clínica | 404 |
| `CR001` | Paciente ainda ativo | 409 (inativar antes) |
| `CR002` | Retenção não vencida; `DETAIL` traz a data liberada | 409 |
| `CR003` | Já anonimizado (também edição/reativação/novos filhos) | 409 |

## 4. Regras e limites
- Retenção: último registro clínico + `core.clinical_retention_period()` (20 anos); sem registro clínico, sem retenção.
- O relógio da retenção usa `updated_at` (e `noted_at`): qualquer `UPDATE` em prontuário, nota ou exame, mesmo sem mudança de conteúdo, reinicia a contagem de 20 anos. O backend não deve regravar registros clínicos sem necessidade.
- Irreversível; atômico; auditável.
- O backend lista as `storage_key` antes, apaga os objetos depois do sucesso e concilia as falhas.
- `audit_log` não é alterado nem anonimizado.
- `crm_app` não grava `anonymized_at`; o backend mapeia a coluna como somente leitura.
- Dumps do `crm_backup` conservam o dado original por até 30 dias.

## 5. Eventos emitidos
Linha `ANONYMIZE` em `audit_log` (F05). Nenhum outro evento.

## 6. Critérios de aceite (integração)
- [ ] Teste 46: recepção → `42501`; outra clínica → `P0002`; ativo → `CR001`.
- [ ] Teste 47: com prontuário dentro de 20 anos → `CR002`; com registros inseridos já com `updated_at`/`noted_at` além de 20 anos no passado → passa.
- [ ] Teste 48: paciente inativo sem registro clínico → passa na hora.
- [ ] Teste 49: nenhuma coluna pessoal mantém o valor original; histórico e notas apagados; exames marcados; auditoria preservada com `ANONYMIZE`.
- [ ] Teste 50: segunda chamada, edição, reativação e novos filhos → `CR003`.
- [ ] Teste 51: `crm_app` não grava `anonymized_at` diretamente.
- [ ] Teste 53 (parte): `search_path` fixo, `EXECUTE` só para `crm_app`; `crm_definer` sem `CREATE` em `core`.
- [ ] Teste 55 (parte): paciente com 500 notas anonimiza em menos de 15 s.
