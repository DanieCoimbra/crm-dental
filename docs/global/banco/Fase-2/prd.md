# PRD Global (Banco) — Fase 2: Agenda

**Escopo:** modelo de dados, papéis, RLS, auditoria e migrations da **Fase 2** (agendamentos com bloqueio de conflito, horário comercial, feriados e vínculo da nota clínica com o atendimento), sobre as Fases 0 e 1.
**Fontes:** `docs/arq_inicial.md` (módulo Agenda e Fase 2), `docs/global/banco/Fase-{0,1}/{prd,spec}.md`, `docs/global/backend/Fase-{0,1}/`, `database/` (migrations V001–V013, bootstrap, testes). O `AGENTS.md` da raiz está vazio.
**Função no pipeline:** `banco`. O backend não cria nem altera objetos de banco. Não há docs de backend da Fase 2 ainda: o contrato é a proposta do banco e deve ser conciliado quando o backend planejar a fase.

> **Nota (2026-10-23):** onde este PRD e a `spec.md` divergem, **vale a spec** (decisões S1–S12 e seção 11 da spec). As user stories abaixo já foram ajustadas às divergências; as "Perguntas abertas" da seção 7 estão respondidas na spec (S2, S3, S5, S7, S8, S9, S10).

## Decisões fechadas

Herdam B1–B8 (Fase 0) e P1–P5 (Fase 1). Novas, confirmadas em 2026-10-23:

| # | Decisão | Valor |
|---|---|---|
| A1 | Schema | **`core`**, como na Fase 1 (nada de schema `scheduling`; revisa a visão das fases seguintes da Fase 0/1) |
| A2 | Intervalo do agendamento | `starts_at` e `ends_at` (`timestamptz`), `CHECK (ends_at > starts_at)` |
| A3 | Conflito de horário | `EXCLUDE USING gist` por `clinic_id` e `tstzrange(starts_at, ends_at)`, **ignorando** `CANCELED` e `NO_SHOW`; exige `btree_gist` |
| A4 | Status e transições | O banco só valida a **lista de status** (`CHECK`). A regra de transição (máquina de estados) fica **no backend**, como diz o `arq_inicial.md` ("todo status é validado no backend") |
| A5 | Papéis | Ver tabela abaixo. O banco reforça o que cada papel pode **gravar** na agenda e na configuração |
| A6 | Conteúdo | `patient_id` (FK composta), horário, status, `notes` curtas em texto claro e `procedure_type_id` **nulo, sem FK** (a FK entra na Fase 3, quando a tabela existir) |
| A7 | Vínculo com prontuário | `clinical_notes.appointment_id` nulo, FK composta `ON DELETE RESTRICT`, aditiva. "Uma nota por atendimento" é regra do backend (sem `UNIQUE` no banco) |
| A8 | Horário comercial | Tabela `business_hours` com até duas faixas por dia (almoço). O banco **não** bloqueia agendamento fora do horário; o backend avisa/decide |
| A9 | Feriados | `holidays` global (nacionais, seed idempotente 2026–2030) e `clinic_holidays` por clínica. Feriado é aviso/bloqueio decidido pelo backend |
| A10 | Exclusão e auditoria | `crm_app` sem `DELETE`; cancelar é status. `appointments` entra no `audit_log` (sem valores). A anonimização do paciente **mantém** os agendamentos |
| A11 | Fuso | Tudo em `timestamptz`; coluna `clinics.timezone` (default `America/Sao_Paulo`). O backend calcula o intervalo do dia/semana e passa ao banco |
| A12 | Metas de desempenho | Agenda da semana com 20 mil agendamentos na clínica ≤ 10 ms; checagem de conflito ≤ 5 ms; sem `Seq Scan` |

**Papéis × agenda (A5)**

| Objeto | `DENTIST_ADMIN` | `RECEPTIONIST` |
|---|---|---|
| `appointments` — ler e criar | sim | sim |
| `appointments` — alterar | tudo | só gravar `SCHEDULED`, `CONFIRMED`, `CANCELED`, `NO_SHOW`, e só sobre linhas hoje `SCHEDULED` ou `CONFIRMED` (não toca em `IN_PROGRESS` nem `COMPLETED`) |
| `business_hours`, `clinic_holidays` | ler e gravar | só ler |
| `holidays` (global) | só ler | só ler (escrita só `crm_owner`, por migration) |

## Padrões adotados (sem pergunta; contestar se discordar)

- **Convenções** das Fases 0 e 1: `snake_case`, plural, `uuid` gerado pela aplicação, `varchar(n)` + `CHECK`, `timestamptz`, `updated_at` por trigger, `version bigint`, FKs compostas `(x_id, clinic_id)`, `created_by` como FK composta para `users`, política `SELECT TO crm_backup USING (true)` em toda tabela com `clinic_id`, funções de sessão como `(SELECT core.current_app_role())` e `OR` de igualdades em vez de `IN`.
- **Um só dentista por clínica:** a agenda é única por `clinic_id`; não há `professional_id`. O `EXCLUDE` depende disso (ver Non-goals).
- **Intervalo semiaberto** `[starts_at, ends_at)`: dois agendamentos consecutivos (um termina às 10:00, outro começa às 10:00) **não** conflitam.
- **Duração:** múltipla de 5 minutos, entre 5 min e 12 h; `starts_at` sem segundos. *(Limites propostos, contestáveis.)*
- **Status (lista fixa, nomes em inglês como `sex` e `kind`):** `SCHEDULED`, `CONFIRMED`, `IN_PROGRESS`, `COMPLETED`, `NO_SHOW`, `CANCELED`. Mapeamento: agendado, confirmado, em atendimento, finalizado, faltou, cancelado.
- **Reativar cancelado** (voltar a `SCHEDULED`) refaz a checagem de conflito: pode falhar com `23P01`; o backend trata.
- **Paciente inativo ou anonimizado não recebe agendamento novo** (trigger de `INSERT`, no padrão `trg_*_no_anonymized_parent` da Fase 1). Agendamentos existentes de quem for inativado seguem como estão. *(Acréscimo meu; contestável.)*
- **Agendamento não é "registro clínico"**: não entra no cálculo da retenção de 20 anos da `anonymize_patient`.
- **Anonimização:** a função passa a **limpar `appointments.notes`** do paciente (texto livre pode citar nome ou queixa); o resto do agendamento fica, pois não guarda dado pessoal. *(Acréscimo meu; contestável.)*
- **`notes` do agendamento é texto claro:** o contrato avisa que **não** é lugar de dado clínico (esse vai no prontuário cifrado). Limite de 500 caracteres.
- **Horários locais:** `business_hours.opens_at/closes_at` são `time` na hora local da clínica (`clinics.timezone`); a conversão para `timestamptz` é do backend.
- **Fuso:** valor validado contra os nomes de fuso do PostgreSQL por trigger (um `CHECK` não pode consultar `pg_timezone_names`).
- **Seed de feriados nacionais:** só feriados nacionais por lei (Confraternização, Sexta-feira Santa, Tiradentes, Dia do Trabalho, Independência, N. Sra. Aparecida, Finados, Proclamação da República, Consciência Negra, Natal), 2026–2030. Carnaval e Corpus Christi são ponto facultativo e ficam para o cadastro da clínica. Atualizar a lista é nova migration de seed. *(Contestável.)*

---

## 1. Resumo executivo

**Problema:** a clínica precisa de uma agenda de um só dentista em que dois atendimentos nunca ocupem o mesmo horário, mesmo com recepção e dentista agendando ao mesmo tempo, e em que a recepção não consiga mexer no que é do dentista (iniciar/finalizar atendimento, configurar horários).

**Solução:** três tabelas de agenda (`appointments`, `business_hours`, `clinic_holidays`) e uma global (`holidays`) em `core`, com a garantia de não sobreposição no próprio banco (`EXCLUDE` + `btree_gist`), RLS por clínica e por papel, auditoria dos agendamentos e o vínculo opcional da nota clínica com o atendimento.

**Metas e métricas:**
1. Duas gravações concorrentes de agendamentos sobrepostos na mesma clínica: exatamente uma passa e a outra falha com `23P01`; em clínicas diferentes, ambas passam.
2. Sob `app.clinic_id = A`, 0 linhas das clínicas B em `appointments`, `business_hours` e `clinic_holidays`, para `SELECT`, `UPDATE` e `INSERT` (testes de isolamento).
3. Com `app.role = RECEPTIONIST`: 0 gravações possíveis em `business_hours` e `clinic_holidays`; em `appointments`, tentativa de gravar `IN_PROGRESS` ou `COMPLETED`, ou de alterar linha nesses estados, afeta 0 linhas ou falha. Com `app.role` ausente ou inválido, tudo negado.
4. 100% dos `INSERT/UPDATE` em `appointments` geram 1 linha em `audit_log`, sem valores; `crm_app` continua sem `DELETE` em qualquer tabela de agenda.
5. Agenda da semana (keyset ou intervalo) ≤ 10 ms e checagem de conflito ≤ 5 ms com 20 mil agendamentos na clínica e 200 mil no banco, sem `Seq Scan` (`EXPLAIN ANALYZE`).
6. Migrations da Fase 2 aditivas, aplicam do zero sobre V001–V013, com rollback documentado; o `pg_dump` do `crm_backup` traz as 4 tabelas novas e a restauração fecha as contagens.

---

## 2. User stories

### US-201: Tabela de agendamentos
**Descrição:** Como backend, quero armazenar os agendamentos de cada clínica para a recepção e o dentista usarem.

**Critérios de aceite:**
- [ ] `core.appointments` com `patient_id` (FK composta `(patient_id, clinic_id)` → `patients`, `ON DELETE RESTRICT`), `starts_at`, `ends_at`, `status` (lista da A4, default `SCHEDULED`), `notes` (nulo, até 500 caracteres), `procedure_type_id` (nulo, **sem FK**), `created_by` (FK composta para `users`), `created_at`, `updated_at`, `version`.
- [ ] `CHECK (ends_at > starts_at)`, duração múltipla de 5 min entre 5 min e 12 h, `starts_at` sem segundos.
- [ ] `UNIQUE (id, patient_id, clinic_id)` (alvo da FK composta da US-205). Não se cria `UNIQUE (id, clinic_id)`: seria índice redundante (spec S1).
- [ ] RLS `ENABLE` + `FORCE`; política por operação com `clinic_id = core.current_clinic_id()`; `DELETE` sem política; `crm_app` sem `DELETE`.
- [ ] `clinic_id`, `id`, `patient_id` e `created_at` imutáveis (trigger, como na Fase 1); `updated_at` por trigger.
- [ ] `INSERT` falha com `CR003` se o paciente estiver anonimizado e com `CR005` se estiver inativo (spec S7).

### US-202: Bloqueio de conflito no banco
**Descrição:** Como dentista, quero que o sistema nunca aceite dois atendimentos no mesmo horário, mesmo sob concorrência.

**Critérios de aceite:**
- [ ] Extensão `btree_gist` instalada pelo bootstrap (como `postgres`, schema `extensions`), em arquivo idempotente.
- [ ] `EXCLUDE USING gist (clinic_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&) WHERE (status NOT IN ('CANCELED','NO_SHOW'))`; violação é `23P01`.
- [ ] Teste: sobreposição parcial, total e contida falham; consecutivos (`10:00` fim / `10:00` início) passam; clínica diferente passa.
- [ ] Teste: cancelar libera o horário; reativar um cancelado sobre horário já ocupado falha com `23P01`.
- [ ] Teste de concorrência: duas sessões inserindo o mesmo horário; uma falha com `23P01`.

### US-203: Status do agendamento e papéis
**Descrição:** Como titular da clínica, quero que a recepção confirme e cancele, mas só o dentista inicie e finalize o atendimento, mesmo que o backend erre.

**Critérios de aceite:**
- [ ] `CHECK` do status limitado aos seis valores. A **máquina de transições fica no backend** (decisão A4); o banco não valida a ordem.
- [ ] Política `UPDATE` de `RECEPTIONIST`: `USING` só sobre linhas `SCHEDULED` ou `CONFIRMED`; `WITH CHECK` só aceita gravar `SCHEDULED`, `CONFIRMED`, `CANCELED` ou `NO_SHOW`. `DENTIST_ADMIN` sem restrição de status.
- [ ] `INSERT` por qualquer dos dois papéis, mas `RECEPTIONIST` só com `status = 'SCHEDULED'`.
- [ ] Teste: sob `RECEPTIONIST`, `UPDATE ... SET status = 'IN_PROGRESS'` falha por `WITH CHECK`; `UPDATE` de linha `COMPLETED` afeta 0 linhas; `UPDATE` `SCHEDULED → CONFIRMED` passa.
- [ ] Teste: `app.role` ausente ou `'ADMIN'` nega tudo; autoria `created_by = app.user_id` exigida no `INSERT`.

### US-204: Consulta da agenda
**Descrição:** Como recepcionista e dentista, quero ver a agenda do dia ou da semana rapidamente.

**Critérios de aceite:**
- [ ] Índice `(clinic_id, starts_at, id)` atende a consulta por intervalo (o backend passa `[início, fim)` já convertido do fuso da clínica).
- [ ] Índice `(clinic_id, patient_id, starts_at DESC, id DESC)` atende o histórico de agendamentos do paciente (também cobre a FK composta).
- [ ] `EXPLAIN` documentado: agenda da semana ≤ 10 ms e checagem de conflito ≤ 5 ms com 20 mil agendamentos na clínica e 200 mil no total, sem `Seq Scan`.

### US-205: Vínculo da nota clínica com o atendimento
**Descrição:** Como dentista, quero associar a nota clínica ao agendamento em que o atendimento ocorreu.

**Critérios de aceite:**
- [ ] `core.clinical_notes.appointment_id uuid` **nulo**, sem default; migration aditiva que não reescreve linhas existentes.
- [ ] FK composta `(appointment_id, patient_id, clinic_id)` → `appointments (id, patient_id, clinic_id)` `ON DELETE RESTRICT`, garantindo agendamento da mesma clínica **e do mesmo paciente** da nota.
- [ ] Coluna imutável depois de preenchida? **Não**: o dentista pode vincular a nota depois (sem `forbid_immutable`); a edição segue só para `DENTIST_ADMIN`.
- [ ] Índice parcial `(clinic_id, appointment_id) WHERE appointment_id IS NOT NULL` (suporte à FK).
- [ ] Sem `UNIQUE` em `appointment_id`: várias notas no mesmo atendimento são permitidas pelo banco.
- [ ] Notas existentes continuam válidas (`appointment_id` nulo); `ddl-auto=validate` do backend segue passando.

### US-206: Horário comercial
**Descrição:** Como dentista, quero configurar os dias e horários em que a clínica atende, com intervalo de almoço.

**Critérios de aceite:**
- [ ] `core.business_hours`: **uma linha por clínica e dia** (spec S2): `weekday` (1 = segunda … 7 = domingo), `is_open`, `opens_at`, `closes_at`, `lunch_starts_at`, `lunch_ends_at` (`time`, hora local), `created_by`, `created_at`, `updated_at`, `version`; `UNIQUE (clinic_id, weekday)`.
- [ ] `CHECK`s de uma linha só: `opens_at < closes_at`; almoço opcional, dentro do expediente e com início antes do fim; dia fechado não tem horários.
- [ ] Dia **sem linha** significa "não configurado"; o backend o trata como fechado. Dia fechado de propósito é `is_open = false`.
- [ ] RLS: leitura para os dois papéis; `INSERT` e `UPDATE` só `DENTIST_ADMIN`; **sem `DELETE`** (spec S3).
- [ ] O banco **não** impede agendamento fora do horário comercial (decisão A8).

### US-207: Feriados nacionais e da clínica
**Descrição:** Como dentista, quero feriados nacionais já cadastrados e poder acrescentar os da minha clínica.

**Critérios de aceite:**
- [ ] `core.holidays` **global**, sem `clinic_id`: `holiday_date` (`date`, único), `name`. RLS `ENABLE` + `FORCE` com política `SELECT` para `crm_app` (`USING (true)`) e para `crm_backup`; sem política de escrita para `crm_app`.
- [ ] Seed idempotente (migration separada) dos feriados nacionais por lei de 2026 a 2030 (`ON CONFLICT DO NOTHING`).
- [ ] `core.clinic_holidays`: `clinic_id`, `holiday_date`, `name`, `created_by`, `version`; `UNIQUE (clinic_id, holiday_date)`; RLS por clínica; leitura para os dois papéis; gravação só `DENTIST_ADMIN`; remoção por `DELETE` físico, só `DENTIST_ADMIN`. É a **única** tabela da fase com `DELETE` para `crm_app`: configuração sem dado pessoal e sem histórico a preservar (spec S5).
- [ ] Teste: `crm_app` não consegue `INSERT/UPDATE/DELETE` em `holidays`.

### US-208: Fuso da clínica
**Descrição:** Como backend, quero saber o fuso da clínica para converter "hoje" e a semana em intervalos de `timestamptz`.

**Critérios de aceite:**
- [ ] `core.clinics.timezone varchar(50) NOT NULL DEFAULT 'America/Sao_Paulo'`, migration aditiva (linhas existentes recebem o default).
- [ ] Trigger rejeita valor que não esteja em `pg_timezone_names` (`23514`).
- [ ] O backend passa a ler a coluna; o banco **não** calcula "hoje" nem a semana.
- [ ] Só `DENTIST_ADMIN` altera o fuso, por **trigger** em `clinics` que age apenas quando o valor muda (spec S4): a política de `clinics` não olha o papel nem a coluna, e o JPA reenvia todas as colunas em todo `UPDATE`.

### US-209: Auditoria da agenda e anonimização
**Descrição:** Como dentista, quero saber quem criou ou alterou agendamentos e manter o rastro mesmo após anonimizar o paciente.

**Critérios de aceite:**
- [ ] `core.audit_log.table_name` aceita também `'appointments'` (`CHECK` ampliado em migration própria); trigger `AFTER INSERT OR UPDATE OR DELETE` em `appointments` (mesmo padrão da V012; o `DELETE` é negado ao `crm_app`) via `core.write_audit()` (sem valores; falha com `28000` sem `app.user_id`).
- [ ] `business_hours`, `clinic_holidays` e `holidays` **não** entram na auditoria (configuração, sem dado pessoal). *(Contestável.)*
- [ ] `core.anonymize_patient` passa a: manter os `appointments` do paciente e **zerar `notes`**; nenhum outro dado muda. A função continua `SECURITY DEFINER`, com `search_path` fixo e sem SQL dinâmico.
- [ ] A política do `crm_definer` em `appointments` é escopada ao tenant e limitada a `SELECT` e `UPDATE (notes, version)`.
- [ ] A anonimização falha com `CR004` se o paciente tiver agendamento futuro em aberto (spec S7).
- [ ] Teste: após anonimizar, nenhum `appointments.notes` do paciente mantém valor; `starts_at`, `ends_at` e `status` permanecem.

### US-210: Backup e testes da fase
**Descrição:** Como responsável pelo banco, quero que as tabelas novas entrem no backup e nos testes existentes.

**Critérios de aceite:**
- [ ] Cada tabela nova tem política `SELECT TO crm_backup USING (true)`; `pg_dump` como `crm_backup` traz as 4 tabelas.
- [ ] `database/tests/` ganha testes de isolamento, papel, constraints (incluindo `EXCLUDE`), concorrência, auditoria e desempenho; `run_all.sh` passa do zero e a cadeia de rollback reverte e reaplica.
- [ ] Rollback documentado em `database/rollback/` para cada migration nova (`U014…`).

---

## 3. Requisitos funcionais

- **FR-1:** Criar em `core` as tabelas `appointments`, `business_hours`, `clinic_holidays` e `holidays`; alterar `clinics` (coluna `timezone`) e `clinical_notes` (coluna `appointment_id`), conforme dicionário da spec.
- **FR-2:** Toda tabela com `clinic_id` tem FK, índice com `clinic_id` à frente, RLS `ENABLE` + `FORCE`, uma política por operação e política `SELECT` do `crm_backup`.
- **FR-3:** `appointments` impede sobreposição por `EXCLUDE` (A3), com `btree_gist` no bootstrap.
- **FR-4:** `appointments.status` limitado por `CHECK` aos seis valores; transições são do backend (A4).
- **FR-5:** O papel `RECEPTIONIST` só grava `SCHEDULED/CONFIRMED/CANCELED/NO_SHOW` e só sobre linhas `SCHEDULED/CONFIRMED`; `business_hours` e `clinic_holidays` só `DENTIST_ADMIN` grava (A5).
- **FR-6:** `crm_app` não tem `DELETE` em `appointments` nem em `holidays`; `holidays` é global, somente leitura para a aplicação.
- **FR-7:** `appointments.procedure_type_id` existe nulo e sem FK; a FK entra na Fase 3.
- **FR-8:** `clinical_notes.appointment_id` nulo, com FK composta que exige o mesmo paciente e a mesma clínica; nenhuma linha antiga é reescrita.
- **FR-9:** `clinics.timezone` com default `America/Sao_Paulo` e validação contra `pg_timezone_names`.
- **FR-10:** `audit_log` passa a aceitar `appointments`; `anonymize_patient` zera `appointments.notes` e mantém o restante.
- **FR-11:** Seed idempotente de feriados nacionais 2026–2030 em migration separada, executada como `crm_owner`.
- **FR-12:** Sem `app.clinic_id`, `app.role` ou `app.user_id` válidos, as tabelas da agenda negam (falha fechada).
- **FR-13:** `crm_app` recebe só os grants por tabela da matriz da spec; `EXECUTE` só nas funções necessárias; sem `GRANT ALL`.
- **FR-14:** Publicar o contrato para o backend da Fase 2: nomes, tipos, erros (`23P01` conflito, `CR*` novos), regra de transição e de feriado/horário que ficam no backend.
- **FR-15:** Migrations aditivas, numeradas a partir de `V014`, com `-- Reverter:` apontando para `U0NN`.

## 4. Non-goals (fora de escopo)

- Mais de um dentista ou cadeira por clínica; `professional_id`. (Exigiria incluir o profissional no `EXCLUDE`.)
- Máquina de transições de status no banco (decidido: backend).
- Bloqueio de agendamento por horário comercial ou feriado no banco; "encaixe" é decisão do backend.
- Tipos de procedimento, duração padrão e cor na agenda (Fase 3); a FK de `procedure_type_id` também.
- Recorrência, lista de espera, lembretes por SMS/e-mail/WhatsApp e confirmação pelo paciente.
- Agendamento online pelo próprio paciente.
- Calendário externo (Google, iCal) e sincronização.
- Auditoria de **leitura** da agenda e de valores antigo/novo.
- Calcular "hoje", semana ou feriado móvel no banco; calendário de feriados municipais ou regionais prontos (a clínica cadastra).
- Particionamento e réplicas.
- Dado clínico em `appointments.notes` (vai no prontuário cifrado).

## 5. Requisitos de IA

Não se aplica.

## 6. Especificações técnicas e considerações de design

**Arquitetura (dados):**
```
React ─▶ API (JWT: clinic_id, role, user_id) ─▶ transação: set_config(app.clinic_id, app.role, app.user_id, local)
                                                    │
       ┌────────────────────────────────────────────┼─────────────────────────────┐
       ▼                                            ▼                             ▼
core.appointments                    core.business_hours / clinic_holidays   core.holidays (global)
(EXCLUDE gist por clínica)           (leitura: 2 papéis; escrita: DENTIST_ADMIN)  (leitura; escrita só migration)
   │  └─ trigger ─▶ core.audit_log (sem valores)
   └─ FK composta ◀─ core.clinical_notes.appointment_id (nulo)
```

**Pontos de integração:**
- Backend (módulo `scheduling`): entidades JPA com `ddl-auto=validate`; define as três variáveis de sessão em toda transação; implementa a máquina de status, o aviso de horário/feriado e traduz `23P01` em 409.
- `clinical_notes` (módulo `record`): passa a mapear `appointment_id` (aditivo).
- Operação: `scripts/apply_migrations.sh`, `database/tests/run_all.sh`, backup e restauração já existentes; `bootstrap/` ganha a extensão `btree_gist`.

**UI/UX:** não se aplica (banco).

**Segurança e privacidade:**
- Agendamento revela que a pessoa é paciente da clínica (dado pessoal), mas não guarda dado clínico; fica em texto claro sob RLS (mesmo trade-off P2 da Fase 1).
- `appointments.notes` livre é o ponto de vazamento: limite de 500 caracteres, aviso no contrato e limpeza na anonimização.
- Menor privilégio: sem `GRANT ALL`, sem `BYPASSRLS`, `DELETE` negado em `appointments`; a única tabela da fase com `DELETE` para `crm_app` é `clinic_holidays` (US-207, spec S5).
- `crm_definer` só enxerga o tenant da sessão (padrão da Fase 1).

## 7. Riscos e perguntas abertas

**Riscos técnicos**
1. **Transições só no backend (A4).** Um bug ou acesso direto ao banco pode gravar `COMPLETED → SCHEDULED` ou pular etapas. Atenuação parcial: a RLS impede a recepção de tocar em linhas `IN_PROGRESS/COMPLETED`; o `audit_log` registra quem alterou. Risco aceito pela decisão do usuário; um trigger de transição pode entrar depois sem mudar o modelo.
2. **`btree_gist` no Supabase** pode exigir `CREATE EXTENSION ... SCHEMA extensions` pelo `postgres` e `USAGE` ao `crm_owner`, como o `pg_trgm`. Validar no projeto real; **não há plano B** equivalente (sem a extensão a garantia de conflito cai para o backend). É pré-requisito de aceite.
3. **`EXCLUDE` e RLS:** a verificação do constraint enxerga todas as linhas (não passa por RLS), mas o `clinic_id` na chave isola as clínicas; testar com duas clínicas para provar que não há conflito cruzado.
4. **Reativação de cancelado** pode falhar por conflito; o backend precisa tratar `23P01` também em `UPDATE`.
5. **Feriado nacional desatualizado:** o seed termina em 2030; criar migration de seed antes disso. Feriados criados por lei nova exigem nova migration.
6. **Fuso e horário de verão:** o Brasil não tem horário de verão hoje, mas a conversão de `business_hours` (`time` local) para `timestamptz` é do backend; testar a borda de virada de dia.
7. **`DELETE` em configuração** quebra o princípio "sem DELETE" só para `clinic_holidays` (spec S5); `business_hours` usa `is_open = false` (S3).
8. **Sem homologação:** migrations pequenas, aditivas e reversíveis; validação por contêiner e ensaios locais (CI/CD adiado).

**Perguntas abertas**
- Limites de duração (5 min–12 h) e de 500 caracteres das `notes`: confirmados ou ajustados na spec com o backend da Fase 2.
- Qual código `CR*` e qual `SQLSTATE` para agendamento de paciente **inativo** (a spec define; `CR003` já é "anonimizado").
- `business_hours` e `clinic_holidays`: `DELETE` físico ou `active`/`deleted_at`?
- Listar Carnaval e Corpus Christi no seed ou só no cadastro da clínica?
- A tabela `appointments` deve guardar `confirmed_at`/`started_at`/`finished_at`? Sem a máquina no banco, esses carimbos seriam só informativos; o backend decide se os quer (colunas aditivas).

**Lançamento em etapas**
- **MVP (Fase 2):** US-201 a US-210.
- **v1.1 (Fase 3):** FK de `procedure_type_id`; duração padrão por procedimento.
- **v2.0:** trigger de transição de status, múltiplos profissionais, lembretes e auditoria de leitura.
