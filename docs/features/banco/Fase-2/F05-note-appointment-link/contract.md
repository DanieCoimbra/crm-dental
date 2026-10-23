# Contract — F05 Vínculo da nota clínica com o atendimento (Fase 2)

## 1. Resumo
- **Feature:** F05-note-appointment-link (Fase 2)
- **Versão:** 1.0.0
- **Consumidores:** backend (módulo `record`); `crm_backup`.

## 2. Entradas

Mudança aditiva em `core.clinical_notes`. As regras de sessão e de papel são as da Fase 1 (F03): só `DENTIST_ADMIN` acessa a tabela.

| Campo novo | Tipo | Regra |
|---|---|---|
| `appointment_id` | `uuid`, **nulo** | Agendamento do **mesmo paciente e da mesma clínica** da nota; opcional; mutável; sem `UNIQUE` |

`INSERT` e `UPDATE` de nota podem informar `appointment_id`.

## 3. Saídas

`SELECT` em `clinical_notes` passa a trazer `appointment_id` (uuid ou `NULL`). Notas antigas têm `NULL`.

### Erros
| SQLSTATE | Causa | Resposta sugerida |
|---|---|---|
| `23503` | `appointment_id` inexistente, de outra clínica ou de outro paciente | 422 (agendamento inválido para este paciente) |

## 4. Regras e limites
- "Uma nota por atendimento" **não** é imposta pelo banco; é regra do backend.
- O vínculo é opcional e pode ser definido ou trocado depois da criação da nota.
- O backend mapeia `appointmentId` como `UUID` simples e **não** o marca `updatable = false`.
- A coluna não é cifrada e não carrega dado clínico.
- A recepção não lê nem grava `clinical_notes` (inalterado).
- Na anonimização (Fase 1 F06), as notas são apagadas e os vínculos acompanham.

## 5. Eventos emitidos
Nenhum no banco. `UPDATE`s de nota continuam auditados pela trilha da Fase 1.

## 6. Critérios de aceite (integração)
- [ ] Teste 75: nota sem `appointment_id` passa; notas existentes intactas após a migration; vincular depois por `UPDATE` passa; várias notas no mesmo agendamento passam; recepção continua sem acesso.
- [ ] Teste 60 (parte): agendamento de outra clínica ou de outro paciente → `23503`.
- [ ] Teste 84: `ddl-auto=validate` do backend passa com o campo mapeado (F08).
- [ ] Teste 82 (parte): V019 aplica do zero; `U019` reverte e reaplica.
