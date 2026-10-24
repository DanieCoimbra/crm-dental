# PRD — F04 Máquina de status do agendamento

**Derivação:** PRD global da Fase 2, US-P2-07, US-P2-08 e US-P2-09, FR-1 (transições), FR-2 (reativação), FR-12 e FR-13; seção 5 (máquina de estados). Spec global, seção 4.7.
**Dependências externas:** F03 (`Appointment`, `AppointmentService`, regra de conflito), F01 (`ClinicZones`, tradução de `23P01`/`CR003`/`CR005`), migration V015 (políticas de papel). Requer o access token da Fase 0.

## 1. Introdução

- **Problema:** a recepção confirma e cancela, mas só o dentista inicia e finaliza o atendimento; o banco só valida a lista de status e o que a recepção grava, então a ordem das transições é do servidor.
- **Solução:** uma rota por ação (`POST /appointments/{id}/<ação>` com `{version}`), uma tabela de transições pura e testável, e as regras extras (dia, horário, um único atendimento em andamento).
- **Metas:**
  1. Toda transição fora da tabela retorna 409 com o status atual.
  2. Recepção recebe 403 em 100% das chamadas de `start`, `finish` e `reactivate`, sem tocar o banco.
  3. `IN_PROGRESS` e `COMPLETED` nunca são alterados pela recepção (backend, e RLS com papel forjado).
  4. Cobertura ≥ 80% em `AppointmentTransitions` e nas transições do `AppointmentService`.

## 2. User stories

### US-001: Confirmar, cancelar e marcar falta
**Descrição:** Como recepcionista ou dentista, quero confirmar a presença, cancelar ou registrar que o paciente faltou.

**Critérios de aceite:**
- [ ] `POST /appointments/{id}/confirm` (D, R): `SCHEDULED` → `CONFIRMED`.
- [ ] `POST /…/cancel` (D, R): `SCHEDULED`/`CONFIRMED` → `CANCELED`; libera o horário na hora.
- [ ] `POST /…/no-show` (D, R): `SCHEDULED`/`CONFIRMED` → `NO_SHOW`, só quando agora ≥ `startsAt`; antes disso 422 `NOT_STARTED`.
- [ ] Todas exigem `{version}`; transição inválida → 409 com o status atual; versão defasada → 409; devolvem o recurso atualizado.

### US-002: Iniciar e finalizar atendimento
**Descrição:** Como dentista, quero iniciar e finalizar o atendimento com um botão.

**Critérios de aceite:**
- [ ] `POST /…/start` (só D): `CONFIRMED` → `IN_PROGRESS`, apenas no dia do agendamento (data local da clínica; fora dele 422 `NOT_TODAY`) e se não houver outro `IN_PROGRESS` na clínica (409).
- [ ] `POST /…/finish` (só D): `IN_PROGRESS` → `COMPLETED` (terminal); não cria nota nem registra horário real.
- [ ] Recepção recebe 403 em `start` e `finish`; `COMPLETED` não aceita ação nem `PUT`.

### US-003: Reativar cancelado ou faltoso
**Descrição:** Como dentista, quero reativar um agendamento cancelado ou marcado como falta.

**Critérios de aceite:**
- [ ] `POST /…/reactivate` (só D): `CANCELED`/`NO_SHOW` → `SCHEDULED`, com `{version}`.
- [ ] Horário ocupado → 409; paciente inativo → 409; anonimizado → 409.
- [ ] Recepção recebe 403 (ela cria um agendamento novo).
- [ ] Não reaplica expediente, feriado nem a regra do passado.

## 3. Requisitos funcionais

- **FR-1:** `AppointmentTransitions` com origens, destino e papéis por ação, sem Spring.
- **FR-2:** Métodos de service com `@PreAuthorize` D para `start`, `finish` e `reactivate`; rotas protegidas no `SecurityConfig`.
- **FR-3:** Ordem de checagem: papel (403) → carregar (404) → `version` (409) → origem (409) → regra extra (422/409) → gravar com `flush`.
- **FR-4:** Traduzir 0 linhas da RLS e `OptimisticLockException` em 409; `42501` em `UPDATE` de status da recepção é defeito (500 com log).
- **FR-5:** Evento `StatusChanged(appointmentId, from, to)` para log após o commit.

## 4. Fora do escopo

- Carimbos de confirmação, início e fim reais; motivo de cancelamento ou falta.
- Trigger de transição de status no banco; índice único de `IN_PROGRESS` (pendência da função `banco`).
- Notificações e lembretes.

## 5. Considerações técnicas e riscos

- Dois `start` simultâneos de agendamentos diferentes podem passar (sem constraint no banco); a corrida é aceita (dentista solo).
- Se o dentista esquecer de finalizar, o próximo `start` falha até o `finish`; é intencional e `finish` fica sempre disponível.
