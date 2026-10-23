# PRD — F05 Vínculo da nota clínica com o atendimento (Fase 2)

**Derivação:** PRD global da Fase 2, US-205, decisão A7, FR-8; spec global, seções 2.3 (`clinical_notes`), 2.4 (`ix_notes_appointment`) e 2.5 (V019). Cumpre o que a Fase 1 deixou anotado na V010 ("o vínculo com agendamento entra na Fase 2, nulo e aditivo").
**Dependências externas:** F02 (`appointments` e `ux_appt_id_patient_clinic`), Fase 1 F03 (`core.clinical_notes`). Consumida pelo backend (módulo `record`).

## 1. Introdução

- **Problema:** a nota clínica de um atendimento não sabe a qual agendamento pertence.
- **Solução:** coluna `appointment_id` nula em `clinical_notes`, com FK composta que exige o mesmo paciente e a mesma clínica.
- **Metas:**
  1. Notas existentes continuam válidas, sem reescrever linhas.
  2. Impossível vincular nota a agendamento de outro paciente ou de outra clínica.
  3. O vínculo pode ser feito depois da criação da nota.

## 2. User stories

### US-001: Coluna e chave estrangeira
**Descrição:** Como dentista, quero associar a nota ao agendamento em que o atendimento ocorreu.

**Critérios de aceite:**
- [ ] `clinical_notes.appointment_id uuid` **nulo**, sem default.
- [ ] FK composta `(appointment_id, patient_id, clinic_id)` → `appointments (id, patient_id, clinic_id)` `ON DELETE RESTRICT`; com `appointment_id` nulo a FK não é verificada.
- [ ] Agendamento de outro paciente ou de outra clínica falha com `23503`.
- [ ] Índice parcial `ix_notes_appointment (clinic_id, appointment_id) WHERE appointment_id IS NOT NULL`.

### US-002: Mutável e sem unicidade
**Descrição:** Como dentista, quero vincular a nota depois de criada e anotar mais de uma vez o mesmo atendimento.

**Critérios de aceite:**
- [ ] `appointment_id` **não** entra na lista de colunas imutáveis (`UPDATE` permitido ao dentista).
- [ ] Sem `UNIQUE` em `appointment_id`: várias notas no mesmo agendamento são aceitas pelo banco ("uma nota por atendimento" é regra do backend).
- [ ] A recepção continua sem acesso a `clinical_notes`.

## 3. Requisitos funcionais

- **FR-1:** Migration `V019__core_notes_appointment_link.sql`: `ADD COLUMN`, `ADD CONSTRAINT`, índice.
- **FR-2:** Nenhum grant ou política novo: a coluna herda os da tabela.
- **FR-3:** `ddl-auto=validate` do backend passa com a coluna mapeada (teste 84).
- **FR-4:** Rollback `U019` (só seguro antes de haver vínculos; depois, restaurar do `pg_dump`).

## 4. Fora do escopo

- Impor uma nota por atendimento; tornar o vínculo obrigatório.
- Apagar nota ao apagar agendamento (não há `DELETE`).
- Alterar a cifra, os limites de tamanho ou as políticas das notas.

## 5. Considerações técnicas e riscos

- `ADD COLUMN` nulo sem default é só metadado; a tabela não é reescrita e as linhas antigas ficam intactas.
- A verificação da FK não passa pela RLS (integridade referencial é do dono), mas o `clinic_id` na chave isola as clínicas.
- **Pergunta aberta:** nenhuma.
