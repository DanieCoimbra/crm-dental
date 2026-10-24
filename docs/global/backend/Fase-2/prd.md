# PRD Global (Backend) — Fase 2: Agenda

**Status:** aprovado pelo usuário em 2026-10-24 (perguntas abertas 1 e 2 resolvidas conforme as propostas). Próximo passo do pipeline após a aprovação: `spec-write-legacy` (`docs/global/backend/Fase-2/spec.md`) e depois `breakdown-legacy`.
**Escopo:** API REST do módulo `scheduling` (agendamentos com bloqueio de conflito, máquina de status, horário comercial, feriados e fuso da clínica) e os ajustes nos módulos `patient`, `record` e `clinic` que a agenda exige, sobre as Fases 0 e 1.
**Fontes:** `docs/arq_inicial.md` (módulo Agenda e Fase 2), `docs/global/backend/Fase-{0,1}/{prd,spec}.md`, `docs/global/banco/Fase-2/{prd,spec}.md` (contrato do banco, seções 2, 3, 4.5 e 6), código atual de `back-java/` (Fases 0 e 1) e `database/` (migrations V001–V021). O `AGENTS.md` da raiz está **vazio**: as premissas vêm dos documentos acima e da memória do projeto.
**Função no pipeline:** `backend`. O backend **não** cria nem altera objetos de banco; consome o contrato da função `banco` (V014–V021, já aplicadas no Supabase real). Este PRD é a conciliação prevista no PRD do banco da Fase 2 ("o contrato deve ser conciliado quando o backend planejar a fase").

## Decisões fechadas

Herdam D1–D8 (Fase 0) e E1–E6 (Fase 1): monólito modular, Spring Boot 4.1.1 / Java 21, pacote `crm_data`, JWT 15 min + refresh rotativo, filtro Hibernate **+** RLS, sem trial, schema dono do banco, papel e tenant só do JWT. Herdam também as decisões do banco A1–A12 e S1–S12 (Fase 2). Novas, confirmadas em 2026-10-23:

| # | Decisão | Valor |
|---|---|---|
| G1 | Horário comercial e feriado | A recepção é **bloqueada** (422) ao agendar fora do expediente, em dia fechado, sobre o almoço ou em feriado. O `DENTIST_ADMIN` pode **encaixar** enviando `allowOutsideHours=true`. O banco aceita tudo (A8, A9); a regra é do backend |
| G2 | Inativar paciente com agendamentos futuros | O backend **cancela automaticamente**, na mesma transação, os agendamentos futuros `SCHEDULED`/`CONFIRMED` (regra 6 da spec do banco). Se houver um `IN_PROGRESS`, recusa com 409 |
| G3 | Nota clínica × atendimento | `appointmentId` **opcional** na nota; deve ser do mesmo paciente; **no máximo 1 nota por atendimento** (a segunda retorna 409). Regra do backend, pois o banco não tem `UNIQUE` (A7) |
| G4 | Agendamento no passado | Só o `DENTIST_ADMIN` cria ou remarca com `startsAt` anterior a agora (tolerância de 5 min). A recepção recebe 422 |

## Padrões adotados (sem pergunta; contestar se discordar)

- **Rotas por ação, não por status genérico.** Não existe `PATCH status`. Cada transição é um `POST /{id}/<ação>` com `{version}` no corpo, o que deixa o papel autorizável por rota e a máquina de estados explícita.
- **Instantes na API:** `startsAt` e `endsAt` em ISO-8601 com deslocamento (`2026-11-03T13:00:00Z`), gravados como `timestamptz`. O front converte para a hora local com `GET /clinic` (`timezone`). **Datas de consulta** (`from`, `to`) e **horários de funcionamento** (`HH:mm`) são sempre **locais da clínica**.
- **Duração:** múltipla de 5 min, de 5 min a 12 h; `startsAt` sem segundos. O backend valida antes do banco (400 com os campos) para não depender do `23514`.
- **Intervalo semiaberto** `[startsAt, endsAt)`: um atendimento que termina às 10:00 e outro que começa às 10:00 não conflitam.
- **Listagem da agenda:** `GET /api/v1/appointments?from&to&status`, `from`/`to` são datas locais (`to` inclusivo), janela máxima de **42 dias** (visão de mês com bordas), sem paginação (volume de um dentista solo: ~10 a 30 por dia), ordenada por `startsAt` e `id`. Usa a consulta indexável da spec do banco (4.6) com o recuo de 12 h. Devolve todos os status; `status` (repetível) filtra.
- **Conflito:** a **autoridade é a constraint** `ex_appt_no_overlap` (`23P01` → 409 "Horário já ocupado", sem detalhes do outro agendamento). **Sem pré-checagem por `&&`**: a spec do banco 4.6 tem correção pendente (o `&&` em `tstzrange` não usa índice sob RLS). Para mostrar o horário ocupado, o front usa a própria listagem da agenda.
- **Remarcar** é `PUT` (`startsAt`, `endsAt`, `notes`, `version`) e só vale para `SCHEDULED` e `CONFIRMED`; um `CONFIRMED` remarcado volta para `SCHEDULED` (nova confirmação). O paciente de um agendamento não muda: para trocar, cancela-se e cria-se outro.
- **Reativar** (`CANCELED`/`NO_SHOW` → `SCHEDULED`, só dentista) reavalia conflito e paciente (banco: `23P01`, `CR005`, `CR003`), mas **não** reaplica horário comercial nem feriado (o horário já foi validado ao criar) e não reaplica a regra do passado.
- **Iniciar atendimento** só no dia do agendamento (data local da clínica) e **um único `IN_PROGRESS` por clínica**; **faltou** (`NO_SHOW`) só a partir do `startsAt`.
- **`procedure_type_id` não é aceito** (nem na entrada nem na saída) até a Fase 3. `notes` até 500 caracteres, texto claro, com aviso na documentação da API de que não leva dado clínico.
- **IDs:** o `id` é gerado pela aplicação (UUID) e a inserção usa `persist` (sem `merge` com `SELECT` prévio), como na Fase 1.
- **Resposta do agendamento** traz o paciente resumido (`id`, `name`, `phone`) obtido em lote pela interface pública de `patient` (sem N+1 e sem repositório alheio). Nunca traz `clinicId`.
- **Cobertura da agenda por papel:** ver a matriz da seção 3 (resumo: recepção cria, remarca, confirma, cancela e marca falta; dentista faz tudo, inclusive iniciar, finalizar, reativar, encaixar fora do horário, registrar no passado e configurar horários e feriados).
- Convenções das Fases 0 e 1 mantidas: Lombok sem `@Data`/`@ToString`/`@EqualsAndHashCode`; `ProblemDetail` (RFC 9457); `@PreAuthorize` nos services como segunda barreira; DTOs sem `clinicId`, `createdBy`, `role` ou `id` vindos do cliente; `@Version Long` com 409 em conflito; auditoria de log só com identificadores.

---

## 1. Resumo executivo

**Problema:** com pacientes e prontuário prontos (Fase 1), a clínica ainda não consegue marcar atendimentos. O dentista solo precisa de uma agenda em que dois atendimentos nunca ocupem o mesmo horário, mesmo com recepção e dentista marcando ao mesmo tempo, que respeite o expediente e os feriados, e em que a recepção confirme e cancele, mas só o dentista inicie e finalize o atendimento.

**Solução:** o módulo `scheduling` expõe agendamentos com conflito resolvido pelo banco, uma máquina de status validada no servidor, horário comercial e feriados configuráveis e o fuso da clínica; e os módulos `patient` e `record` passam a conversar com a agenda (inativar paciente cancela os futuros, anonimização traduz `CR004`, nota ganha o vínculo com o atendimento).

**Metas e métricas (backend, Fase 2):**
1. 100% dos endpoints novos têm teste da matriz papel × endpoint (401/403/2xx); `RECEPTIONIST` recebe 403 em `start`, `finish`, `reactivate`, `PUT /business-hours`, escrita em `/clinic-holidays` e `PUT /clinic`.
2. **Concorrência:** 2 `POST /appointments` simultâneos para o mesmo horário na mesma clínica resultam em exatamente um 201 e um 409, em 20 repetições, sem nenhum 500; em clínicas diferentes, os dois passam. (Fecha o teste de concorrência do banco pela API.)
3. Com o filtro Hibernate desligado e `app.role = RECEPTIONIST` forjado no teste, a RLS sozinha impede gravar `IN_PROGRESS`/`COMPLETED` e alterar linha em estado do dentista; nenhuma linha de outra clínica aparece em agenda, horário comercial ou feriados (defesa em profundidade provada em Testcontainers, como a Fase 1).
4. Agenda da semana pela API: p95 ≤ 100 ms com 20 mil agendamentos na clínica e 4 requisições concorrentes em ambiente local; criação de agendamento p95 ≤ 100 ms (`support/PerfLimits`: 200 ms com `CI`).
5. `ddl-auto=validate` passa contra as migrations V001–V021 (fecha o teste 84 do banco) e `mvn verify` continua verde em máquina limpa.
6. Nenhum texto de `notes`, nome de paciente ou CPF em log, `toString` ou mensagem de erro (teste de higiene de log, validado por mutação como na Fase 1).
7. Cobertura ≥ 80% nas classes de serviço de `scheduling` e nas classes de serviço alteradas de `patient` e `record`.

---

## 2. Módulos e mudanças nos módulos existentes

| Módulo | Responsabilidade / mudança |
|---|---|
| `scheduling` (novo) | Agendamentos, máquina de status, regras de horário/feriado/passado, horário comercial, feriados (nacionais e da clínica), consulta de agenda e histórico do paciente |
| `patient` (alterado) | `PatientLookup` ganha `requireSchedulable` (existe, não anonimizado, **ativo**) e um resumo em lote (`id`, `name`, `phone`) para a agenda. Inativar paciente passa a disparar o cancelamento dos futuros (G2). Tradução de `CR004` na anonimização |
| `record` (alterado) | `ClinicalNote` mapeia `appointment_id`; criação e edição da nota aceitam `appointmentId` (G3). `AuditQueryService` aceita `appointments` como `tableName` |
| `clinic` / `auth` (alterado) | `Clinic` mapeia `timezone`; `GET/PUT /clinic` expõe o fuso (só `DENTIST_ADMIN` altera, validado contra `ZoneId`) |
| `shared` (alterado) | Tradução centralizada de `23P01` (constraint `ex_appt_no_overlap` → 409), `CR004` e `CR005` (409); `Phase*ArchitectureTest` passa a cobrir `scheduling` |

**Direção de dependência (sem ciclo).** `scheduling` depende de `patient` (interface `PatientLookup`) e `record` depende de `scheduling` apenas por uma interface pública de leitura (o vínculo da nota precisa checar que o atendimento existe e é do paciente). O cancelamento ao inativar **não** pode fazer `patient` chamar `scheduling` (criaria ciclo): `patient` publica um evento **síncrono dentro da transação** (`PatientEvents.Deactivating`) e `scheduling` o escuta, cancelando os futuros na mesma transação. Nenhum módulo usa o repositório de outro.

---

## 3. Matriz papel × endpoint

| Endpoint | `DENTIST_ADMIN` | `RECEPTIONIST` |
|---|---|---|
| `POST /api/v1/appointments` | sim (passado e encaixe permitidos) | sim (só futuro, só dentro do expediente) |
| `GET /api/v1/appointments`, `GET /api/v1/appointments/{id}` | sim | sim |
| `PUT /api/v1/appointments/{id}` | sim | sim (só `SCHEDULED`/`CONFIRMED`) |
| `POST /…/{id}/confirm`, `/cancel`, `/no-show` | sim | sim |
| `POST /…/{id}/start`, `/finish`, `/reactivate` | sim | **403** |
| `GET /api/v1/patients/{id}/appointments` | sim | sim |
| `GET /api/v1/business-hours`, `GET /api/v1/holidays` | sim | sim |
| `PUT /api/v1/business-hours`, `POST/PUT/DELETE /api/v1/clinic-holidays` | sim | **403** |
| `GET /api/v1/clinic` | sim | sim |
| `PUT /api/v1/clinic` (inclui `timezone`) | sim | **403** |

O backend nega com 403 **antes** do banco (`SecurityConfig` por rota e `@PreAuthorize` nos services); a RLS é a segunda barreira.

---

## 4. User stories

### US-P2-01: Fuso da clínica
**Descrição:** Como dentista, quero que a clínica tenha um fuso para o sistema entender "hoje" e "esta semana" na hora local.

**Critérios de aceite:**
- [ ] `GET /api/v1/clinic` (D, R) devolve `timezone` (padrão `America/Sao_Paulo` para as clínicas existentes); `PUT /api/v1/clinic` (só D) aceita `timezone` opcional e mantém o valor atual quando ausente.
- [ ] Nome de fuso desconhecido retorna 400 com o campo (validado por `ZoneId` antes do banco; o `23514` do banco é a segunda barreira).
- [ ] Todo cálculo de dia, semana, expediente e "iniciar no dia" usa o fuso lido da clínica do token, nunca uma constante.
- [ ] `Clinic` mapeia a coluna sem quebrar `ddl-auto=validate`; o registro de clínica (`/auth/register`) segue funcionando com o default do banco.

### US-P2-02: Horário comercial
**Descrição:** Como dentista, quero configurar os dias e horários em que atendo, com intervalo de almoço.

**Critérios de aceite:**
- [ ] `GET /api/v1/business-hours` (D, R) devolve os 7 dias (1 = segunda … 7 = domingo); dia sem linha vem como `configured: false, isOpen: false` (não configurado = fechado, S3).
- [ ] `PUT /api/v1/business-hours` (só D) recebe de 1 a 7 dias distintos (`weekday`, `isOpen`, `opensAt`, `closesAt`, `lunchStartsAt`, `lunchEndsAt`, `version`) e grava tudo na mesma transação (cria o dia na primeira vez, atualiza depois com `version`); `version` defasada retorna 409.
- [ ] Validação no servidor com a lista de campos inválidos: aberto exige `opensAt < closesAt`; almoço opcional, com as duas pontas, dentro do expediente e início antes do fim; fechado não aceita horários; `weekday` fora de 1–7 ou repetido na requisição retorna 400. Horários em `HH:mm`, hora local.
- [ ] Não existe `DELETE`: fechar o dia é `isOpen=false`. Fechamento às 24:00 **não** é aceito (limite do `LocalTime`; o máximo é 23:59).
- [ ] Recepção recebe 403 no `PUT`; clínica de outro tenant nunca é lida ou alterada.
- [ ] **Aviso de impacto (acrescentado em 2026-10-24):** o `PUT` devolve `affectedAppointments` (agendamentos futuros em aberto que ficam em dia fechado, fora do horário ou no almoço, com `reason`), e `?dryRun=true` mostra o mesmo aviso sem gravar. O aviso é informativo: nada é cancelado nem bloqueado. Detalhes na F02.

### US-P2-03: Feriados nacionais e da clínica
**Descrição:** Como dentista, quero ver os feriados nacionais já cadastrados e acrescentar os da minha clínica.

**Critérios de aceite:**
- [ ] `GET /api/v1/holidays?from&to` (D, R), datas locais, janela máxima de 366 dias, devolve a união dos feriados nacionais (`core.holidays`, só leitura) e dos da clínica, cada item com `date`, `name`, `source` (`NATIONAL` ou `CLINIC`) e, para os da clínica, `id` e `version`.
- [ ] `POST /api/v1/clinic-holidays` (só D) cria `{date, name}` (nome 2–80, data ≥ 2000-01-01); data repetida na clínica retorna 409; a mesma data em outra clínica é aceita; repetir uma data nacional é aceito.
- [ ] `PUT /api/v1/clinic-holidays/{id}` (só D) altera nome e data com `version`; `DELETE /api/v1/clinic-holidays/{id}` (só D) remove fisicamente e responde 204 (única exceção a "sem `DELETE`", S5).
- [ ] Não há endpoint de escrita para feriado nacional. Recepção recebe 403 nas escritas; feriado de outra clínica retorna 404.

### US-P2-04: Criar agendamento
**Descrição:** Como recepcionista ou dentista, quero marcar um atendimento para um paciente num horário livre.

**Critérios de aceite:**
- [ ] `POST /api/v1/appointments` (D, R) recebe `patientId`, `startsAt`, `endsAt`, `notes` (opcional) e `allowOutsideHours` (opcional); cria com status `SCHEDULED` e devolve 201 com o recurso. `clinicId`, `createdBy`, `status`, `id` e `procedureTypeId` nunca vêm do corpo.
- [ ] Validação (400 com `errors[]`): duração múltipla de 5 min entre 5 min e 12 h, `startsAt` sem segundos, `endsAt > startsAt`, `notes` ≤ 500 (sem ser só espaços).
- [ ] **Conflito:** sobreposição parcial, total ou contida na mesma clínica retorna 409 "Horário já ocupado" (`23P01`); consecutivos passam; outra clínica passa; `CANCELED` e `NO_SHOW` não ocupam horário.
- [ ] **Paciente:** inexistente na clínica, 404; anonimizado, 409; **inativo**, 409 (`CR005`/`requireSchedulable`).
- [ ] **Expediente e feriado (G1):** para a recepção, `startsAt`/`endsAt` (convertidos ao fuso da clínica) devem estar dentro de um dia aberto, entre `opensAt` e `closesAt`, fora do almoço, no mesmo dia local e fora de feriado (nacional ou da clínica); caso contrário, 422 `business-rule` com o campo `reason` (`CLOSED_DAY`, `OUTSIDE_HOURS`, `LUNCH`, `HOLIDAY`). O dentista com `allowOutsideHours=true` ignora essas checagens; a recepção que enviar `allowOutsideHours=true` recebe 403.
- [ ] **Passado (G4):** `startsAt` anterior a agora − 5 min retorna 422 (`reason: PAST`) para a recepção e é aceito para o dentista.
- [ ] A criação do dentista fora do expediente com `allowOutsideHours=true` gera um evento de log (só identificadores) para rastreio, já que o banco não registra o encaixe.
- [ ] Teste de matriz: D e R recebem 201; sem token, 401.

### US-P2-05: Consultar a agenda
**Descrição:** Como recepcionista ou dentista, quero ver a agenda do dia ou da semana e abrir um agendamento.

**Critérios de aceite:**
- [ ] `GET /api/v1/appointments?from&to&status` (D, R) devolve os agendamentos que **intersectam** a janela `[from 00:00, to+1 dia 00:00)` no fuso da clínica (inclusive o que começou antes da janela e entra nela), ordenados por `startsAt` e `id`.
- [ ] `from` ou `to` ausentes ou inválidos, `to < from` ou janela > 42 dias retornam 400. `status` aceita os seis valores, repetível; valor desconhecido retorna 400.
- [ ] Cada item traz `id`, `patient {id, name, phone}`, `startsAt`, `endsAt`, `status`, `notes`, `createdBy`, `createdAt`, `updatedAt`, `version`; nunca `clinicId` nem `procedureTypeId`.
- [ ] `GET /api/v1/appointments/{id}` (D, R) devolve um agendamento; id de outra clínica retorna 404.
- [ ] Meta 4: p95 ≤ 100 ms com 20 mil agendamentos (teste de desempenho em IT, sem `Seq Scan` no plano da consulta). A resposta usa `Cache-Control: no-store`.
- [ ] Agendamento de paciente anonimizado continua listado, com o resumo anonimizado do paciente.

### US-P2-06: Remarcar e editar observação
**Descrição:** Como recepcionista ou dentista, quero mudar o horário ou a observação de um agendamento que ainda não começou.

**Critérios de aceite:**
- [ ] `PUT /api/v1/appointments/{id}` (D, R) aceita `startsAt`, `endsAt`, `notes`, `allowOutsideHours` e exige `version`; versão defasada retorna 409.
- [ ] Só `SCHEDULED` e `CONFIRMED` podem ser alterados; qualquer outro status retorna 409 com o status atual. Alterar o horário de um `CONFIRMED` o devolve a `SCHEDULED`.
- [ ] As mesmas validações, conflito (409 por `23P01`), expediente/feriado e passado da criação valem para o novo horário (apenas quando `startsAt`/`endsAt` mudam).
- [ ] `patientId` não é aceito no corpo; tentar enviá-lo é ignorado e nunca altera o paciente.
- [ ] Linha alterada por outra transação entre a leitura e a gravação (RLS afeta 0 linhas) retorna 409, nunca 500.

### US-P2-07: Confirmar, cancelar e marcar falta
**Descrição:** Como recepcionista ou dentista, quero confirmar a presença, cancelar ou registrar que o paciente faltou.

**Critérios de aceite:**
- [ ] `POST /api/v1/appointments/{id}/confirm` (D, R): `SCHEDULED` → `CONFIRMED`.
- [ ] `POST /…/{id}/cancel` (D, R): `SCHEDULED` ou `CONFIRMED` → `CANCELED`; libera o horário na hora (novo agendamento no mesmo horário passa).
- [ ] `POST /…/{id}/no-show` (D, R): `SCHEDULED` ou `CONFIRMED` → `NO_SHOW`, só quando agora ≥ `startsAt`; antes disso, 422.
- [ ] Todas exigem `{version}`; transição fora da tabela da seção 5 retorna 409 com o status atual; versão defasada, 409; sempre devolvem o recurso atualizado.
- [ ] `IN_PROGRESS` e `COMPLETED` nunca são alterados pela recepção, nem por bug de rota: 403 do backend antes do banco; com o papel forjado no banco, `42501` ou 0 linhas (meta 3).

### US-P2-08: Iniciar e finalizar atendimento
**Descrição:** Como dentista, quero iniciar e finalizar o atendimento com um botão.

**Critérios de aceite:**
- [ ] `POST /…/{id}/start` (só D): `CONFIRMED` → `IN_PROGRESS`, apenas no dia do agendamento (data local da clínica; fora dele, 422) e se a clínica não tiver outro `IN_PROGRESS` (409).
- [ ] `POST /…/{id}/finish` (só D): `IN_PROGRESS` → `COMPLETED` (terminal). O backend não cria nota nem registra horário real (S8 do banco).
- [ ] Ambas exigem `{version}`; recepção recebe 403 em 100% dos casos; transição inválida, 409.
- [ ] `COMPLETED` não aceita nenhuma ação nem `PUT`; só o `audit-log` o mostra no histórico.

### US-P2-09: Reativar agendamento cancelado ou faltoso
**Descrição:** Como dentista, quero reativar um agendamento cancelado ou marcado como falta.

**Critérios de aceite:**
- [ ] `POST /…/{id}/reactivate` (só D): `CANCELED` ou `NO_SHOW` → `SCHEDULED`, com `{version}`.
- [ ] Horário já ocupado por outro agendamento retorna 409 (`23P01` também em `UPDATE`); paciente inativo, 409 (`CR005`); anonimizado, 409 (`CR003`).
- [ ] Recepção recebe 403 (ela cria um agendamento novo).
- [ ] Não reaplica horário comercial, feriado nem a regra do passado (decisão de padrão acima).

### US-P2-10: Histórico de agendamentos do paciente
**Descrição:** Como recepcionista ou dentista, quero ver os agendamentos de um paciente.

**Critérios de aceite:**
- [ ] `GET /api/v1/patients/{id}/appointments?page&size` (D, R) devolve `PageResponse` (`size` máx. 50) ordenado por `startsAt DESC, id DESC`, com todos os status.
- [ ] Paciente inexistente ou de outra clínica retorna 404; paciente inativo ou anonimizado continua consultável.
- [ ] A resposta não traz prontuário, só os campos do agendamento.

### US-P2-11: Inativar e anonimizar paciente com a agenda
**Descrição:** Como dentista, quero inativar ou anonimizar um paciente sem deixar horários ocupados por quem saiu da clínica.

**Critérios de aceite:**
- [ ] `PATCH /api/v1/patients/{id}/active` com `active=false` cancela, **na mesma transação**, todos os agendamentos futuros (`ends_at > agora`) `SCHEDULED`/`CONFIRMED` do paciente; se falhar o cancelamento, a inativação não persiste.
- [ ] Se existir agendamento `IN_PROGRESS` do paciente, a inativação retorna 409 "finalize o atendimento antes de inativar".
- [ ] Cada cancelamento gera sua linha de auditoria (autor = dentista do token); nenhum agendamento passado é tocado.
- [ ] `POST /api/v1/patients/{id}/anonymize`: `CR004` do banco vira 409 "cancele os agendamentos futuros antes de anonimizar"; depois da anonimização os `notes` dos agendamentos do paciente ficam nulos e `startsAt`, `endsAt` e `status` permanecem (conferido por IT).
- [ ] Reativar o paciente não restaura os agendamentos cancelados.

### US-P2-12: Nota clínica vinculada ao atendimento
**Descrição:** Como dentista, quero associar a nota clínica ao agendamento em que o atendimento ocorreu.

**Critérios de aceite:**
- [ ] `POST /api/v1/patients/{id}/notes` e `PUT …/notes/{noteId}` (só D) aceitam `appointmentId` opcional; a resposta da nota passa a trazê-lo (nulo quando não vinculada).
- [ ] O agendamento deve existir na clínica **e ser do mesmo paciente** da nota; caso contrário 404 (inexistente ou de outra clínica) ou 409 (de outro paciente).
- [ ] Máximo de 1 nota por atendimento: vincular uma segunda nota ao mesmo agendamento retorna 409; a nota que já está vinculada pode ser reeditada sem conflito consigo mesma.
- [ ] Notas existentes seguem válidas com `appointmentId` nulo; vincular depois por `PUT` é permitido.
- [ ] **Desvincular (acrescentado em 2026-10-24):** no `PUT`, `appointmentId` substitui o vínculo; `null` ou ausente desvincula e libera o atendimento para outra nota; preenchido vincula ou troca com as mesmas regras.
- [ ] O conteúdo da nota continua cifrado e fora de log; a recepção continua com 403 em notas.

### US-P2-13: Auditoria da agenda
**Descrição:** Como dentista, quero saber quem criou ou alterou agendamentos.

**Critérios de aceite:**
- [ ] `GET /api/v1/audit-log?tableName=appointments&recordId=…` (só D) devolve `INSERT` e `UPDATE` dos agendamentos (autor, ação, instante), sem valores de colunas; `tableName=appointments` deixa de ser 400.
- [ ] Nenhum endpoint de escrita na auditoria; `business_hours`, `clinic_holidays` e `holidays` não aparecem (não são auditadas).
- [ ] Falha na auditoria do banco (`28000`, sem `app.user_id`) reverte a operação e a API responde 500 `internal` sem detalhes.

### US-P2-14: Isolamento e papel provados na agenda
**Descrição:** Como responsável pelo produto, quero garantia técnica de que uma clínica não vê a agenda de outra e de que a recepção não passa dos seus limites, mesmo com bug no backend.

**Critérios de aceite:**
- [ ] Com duas clínicas e o filtro Hibernate desligado, consultas de agenda, horário comercial, feriados da clínica e histórico do paciente nunca retornam linhas da outra (inclusive por SQL nativo).
- [ ] Com `app.role = RECEPTIONIST` forjado no teste, `IN_PROGRESS`/`COMPLETED` não são gravados e linhas em estado do dentista não são alteradas; `business_hours` e `clinic_holidays` não aceitam escrita; `app.role` ausente ou inválido nega tudo.
- [ ] A matriz da seção 3 está coberta por teste web (401/403/2xx) e por IT.
- [ ] `ddl-auto=validate` passa contra V001–V021 com as entidades novas (`@Version Long`, `@Enumerated(STRING)`, `updatable=false` nas colunas imutáveis, `insertable/updatable=false` em `created_at`/`updated_at`).

---

## 5. Máquina de estados do agendamento (validada no servidor)

O banco só valida a lista de status e, para a recepção, **quem** grava o quê (A4); a coluna **Para** é do backend.

| De | Ação (rota) | Para | Quem | Regras extras do backend |
|---|---|---|---|---|
| — | `POST /appointments` | `SCHEDULED` | D, R | G1 (expediente/feriado), G4 (passado), conflito, paciente ativo |
| `SCHEDULED` | `confirm` | `CONFIRMED` | D, R | — |
| `SCHEDULED`, `CONFIRMED` | `cancel` | `CANCELED` | D, R | — |
| `SCHEDULED`, `CONFIRMED` | `no-show` | `NO_SHOW` | D, R | agora ≥ `startsAt` |
| `CONFIRMED` | `start` | `IN_PROGRESS` | só D | mesmo dia local; um só `IN_PROGRESS` por clínica |
| `IN_PROGRESS` | `finish` | `COMPLETED` | só D | — |
| `CANCELED`, `NO_SHOW` | `reactivate` | `SCHEDULED` | só D | conflito e paciente (banco) |
| `SCHEDULED`, `CONFIRMED` | `PUT` (remarcar) | `SCHEDULED` | D, R | `CONFIRMED` volta a `SCHEDULED` se o horário mudar |
| `IN_PROGRESS`, `COMPLETED` | qualquer | — | — | 409; nenhuma ação, nem `PUT` |

Toda transição fora da tabela retorna 409 com o status atual. A recepção só envia ao banco `SCHEDULED`, `CONFIRMED`, `CANCELED` e `NO_SHOW`; `42501` num `UPDATE` de status é bug do backend, nunca erro do usuário (o backend responde 403 antes).

---

## 6. Requisitos funcionais

- **FR-1:** O sistema deve permitir criar, consultar, remarcar e mudar o status de agendamentos conforme a matriz da seção 3 e a máquina de estados da seção 5, com toda transição validada no servidor.
- **FR-2:** O sistema deve resolver o conflito de horário pela constraint do banco (`ex_appt_no_overlap`), traduzindo `23P01` em 409, em `INSERT` e em `UPDATE` (remarcação e reativação). Não deve haver `SELECT ... FOR UPDATE`, lock do backend nem pré-checagem por `&&`.
- **FR-3:** O sistema deve ler o fuso de `clinics.timezone` e converter "dia", "semana" e janelas de consulta em `[início, fim)` de `timestamptz` pelo fuso da clínica; o banco não calcula "hoje".
- **FR-4:** O sistema deve cruzar o agendamento com `business_hours` (hora local) e `holidays` ∪ `clinic_holidays`, bloqueando a recepção (422 com `reason`) e deixando o dentista encaixar com `allowOutsideHours=true`; dia sem linha em `business_hours` é tratado como fechado.
- **FR-5:** O sistema deve bloquear agendamento no passado para a recepção (tolerância de 5 min) e permiti-lo ao dentista.
- **FR-6:** O sistema deve validar no servidor duração (múltipla de 5 min, 5 min a 12 h), `startsAt` sem segundos, tamanho de `notes` e coerência dos horários comerciais, devolvendo todos os erros de uma vez.
- **FR-7:** O sistema deve permitir configurar `business_hours` (7 dias, `PUT` em lote, sem `DELETE`) e `clinic_holidays` (CRUD com `DELETE` físico) somente a `DENTIST_ADMIN`; feriados nacionais são só leitura.
- **FR-8:** O sistema deve consultar a agenda por janela local (`from`/`to`, máx. 42 dias) usando a consulta indexável da spec do banco (4.6) e o histórico do paciente paginado (`size` ≤ 50).
- **FR-9:** O sistema deve cancelar, na mesma transação da inativação, os agendamentos futuros em aberto do paciente, recusar a inativação com `IN_PROGRESS` e traduzir `CR004` (anonimização), `CR005` (paciente inativo) e `CR003` (anonimizado) em 409.
- **FR-10:** O sistema deve aceitar `appointmentId` opcional na nota clínica, exigindo mesmo paciente e no máximo uma nota por atendimento, e devolvê-lo na resposta.
- **FR-11:** O sistema deve aceitar `tableName=appointments` na consulta de auditoria e continuar sem endpoint de escrita nela.
- **FR-12:** O sistema deve aplicar controle otimista (`version`) em agendamento, horário comercial e feriado da clínica, e responder 409 também quando a RLS afetar 0 linhas por mudança concorrente de estado.
- **FR-13:** O sistema deve negar por papel no servidor (`SecurityConfig` e `@PreAuthorize`) antes de qualquer acesso ao banco e nunca aceitar `clinicId`, `createdBy`, `role`, `status` ou `procedureTypeId` do cliente.
- **FR-14:** O sistema deve definir `app.clinic_id`, `app.role` e `app.user_id` em toda transação (ponto único da Fase 1) e usar `created_by = app.user_id` em toda gravação nova.
- **FR-15:** O sistema deve manter `notes`, nome do paciente, CPF e conteúdo clínico fora de logs, `toString` e mensagens de erro; eventos de log levam só identificadores, status e contagens.
- **FR-16:** Plataforma: mapear as entidades novas e alteradas com `ddl-auto=validate` contra V001–V021 (fecha o teste 84 do banco); nenhuma migration e nenhuma alteração em `database/`.

---

## 7. Non-goals (fora de escopo)

- Mais de um dentista ou cadeira por clínica (`professional_id`) e agenda multi-profissional (exigiria mudar o `EXCLUDE`).
- Tipos de procedimento, duração padrão por procedimento e cor na agenda (Fase 3); a FK de `procedure_type_id`.
- Carimbos de confirmação, início e fim reais do atendimento (S8 do banco; entram aditivos depois, se o relatório da Fase 4 pedir).
- Recorrência, lista de espera, lembretes por SMS, e-mail ou WhatsApp, confirmação pelo paciente e agendamento online.
- Calendário externo (Google, iCal) e sincronização.
- Permuta de horários entre dois agendamentos (a constraint não é deferrable); o usuário cancela e recria.
- Trigger de transição de status no banco, auditoria de **leitura** da agenda e comparação de valores antigo/novo.
- Feriados municipais ou regionais prontos (a clínica cadastra); feriados nacionais depois de 2030 (nova migration de seed do banco) e Carnaval/Corpus Christi (S9).
- Motivo de cancelamento e de falta, notificações e histórico de status além do `audit-log`.
- Dashboard e KPIs ("consultas hoje", gráfico semanal): Fase 4.
- Migration nova ou qualquer alteração de banco (a função `banco` não é acionada); frontend (consumidor desta API; ainda não iniciado).
- Fechamento do expediente às 24:00 e horário que atravessa a meia-noite.

## 8. Requisitos de IA

Não se aplica.

---

## 9. Especificações técnicas e considerações de design

**Arquitetura (fluxo):**
```
React ─▶ /api/v1/** ─▶ filtro JWT (clinic_id, user_id, role) ─▶ controller ─▶ service (@PreAuthorize, @Transactional)
                                                              │
                          transação: set_config(app.clinic_id, app.role, app.user_id, true) + filtro Hibernate
                                                              │
        scheduling ─▶ core.appointments (EXCLUDE gist por clínica) ──trigger──▶ core.audit_log (append-only)
                   ─▶ core.business_hours / core.clinic_holidays / core.holidays (global, só leitura)
        scheduling ─▶ PatientLookup (patient): paciente ativo + resumo em lote
        patient ──evento síncrono (Deactivating)──▶ scheduling: cancela futuros na mesma transação
        record ─▶ core.clinical_notes.appointment_id ─▶ interface pública de scheduling (atendimento existe e é do paciente)
        clinic ─▶ core.clinics.timezone
```

**Pontos de integração:**
- **Banco (contrato, spec do banco da Fase 2, seção 6):** nomes de tabelas e colunas, variáveis de sessão, constraints úteis ao backend (`ex_appt_no_overlap`, `ux_bh_clinic_weekday`, `ux_clinic_holidays_date`) e SQLSTATE: `23P01`, `23505`, `23503`, `23514`, `42501`, `28000`, `CR003`, `CR004`, `CR005`. Mapeamento de entidades na seção 6.2 daquela spec. V014–V021 já estão aplicadas no Supabase real.
- **Fase 0/1:** `TenantContext`/`TenantHibernateJpaDialect` (sem mudança), `GlobalExceptionHandler`, `DatabaseErrors`, `ProblemTypes` (`CONFLICT` 409 e `BUSINESS_RULE` 422 já existem), `PageResponse`, `Role`, `TokenPrincipal`, `Clock` injetável (testes de "agora" e "hoje"), `PerfLimits` e a base `Phase1ItSupport` (contexto Spring compartilhado).
- **Auditoria:** os logs de agenda seguem o padrão após commit dos `*AuditLogger` existentes (só identificadores).

**Segurança e privacidade:**
- O papel vem **só** do JWT validado; o `clinic_id` nunca vem do corpo ou da URL.
- Um agendamento revela que a pessoa é paciente da clínica, mas não guarda dado clínico; fica em texto claro sob RLS (trade-off P2 da Fase 1). `notes` é o ponto de vazamento: limite de 500 caracteres, aviso na documentação da API, nunca em log e zerado pelo banco na anonimização.
- O 409 de conflito não revela dados do outro agendamento; o `DETAIL` do erro do driver nunca é logado nem devolvido.
- O encaixe (`allowOutsideHours`) e o registro no passado são prerrogativas do dentista, checadas no servidor; o banco não distingue, então a barreira é do backend (limite aceito da arquitetura de papel único `crm_app`).
- Respostas da agenda com `Cache-Control: no-store`.

**UI/UX:** não se aplica (API). O contrato de erro e de paginação segue a Fase 0/1. O front deve tratar o `reason` do 422 (`CLOSED_DAY`, `OUTSIDE_HOURS`, `LUNCH`, `HOLIDAY`, `PAST`) para oferecer o encaixe ao dentista.

---

## 10. Riscos e perguntas abertas

**Riscos técnicos**
1. **`23P01` aparece no flush/commit, não no `persist`.** Se a exceção estourar só no commit, ela escapa do `try` do service e vira 500. *Mitigação:* `flush` explícito dentro do service em criação, remarcação e reativação, para traduzir a exceção ali; IT de concorrência cobre o caminho.
2. **Ciclo entre módulos.** A agenda precisa do paciente (checagem e resumo) e o paciente precisa cancelar agendamentos ao inativar. *Mitigação:* evento síncrono na transação (seção 2) e `PatientLookup` como única via; o teste de arquitetura passa a cobrir `scheduling`.
3. **Recepção e linhas que a RLS "esconde" do `UPDATE`.** Um `UPDATE` sobre linha em estado do dentista afeta 0 linhas sem erro; com `@Version`, o Hibernate lança conflito otimista. *Mitigação:* o service checa o estado antes (409 por regra) e traduz o conflito otimista para 409; IT com papel forjado.
4. **Regra "uma nota por atendimento" sem `UNIQUE`.** Duas gravações concorrentes poderiam vincular duas notas. *Mitigação:* o service serializa por agendamento (trava da linha do atendimento) ou aceita a corrida, pois só o dentista escreve notas; decisão na spec.
5. **Hora de fechamento `24:00` do banco.** O `time` aceita `'24:00:00'`, mas `LocalTime` não o representa; uma linha assim quebraria a leitura. *Mitigação:* a API só aceita até 23:59; a spec trata a leitura defensiva de linhas gravadas por fora.
6. **Seed de feriados nacionais termina em 2030.** Depois disso `GET /holidays` só traz os da clínica, e a recepção passaria a agendar em feriado nacional sem bloqueio. *Mitigação:* nova migration de seed antes de 2031 (função `banco`); a resposta não esconde a lacuna.
7. **Mudar o fuso com agendamentos existentes.** Os instantes não mudam, mas o expediente (hora local) passa a significar outro momento e agendamentos antigos podem ficar "fora do horário". Nenhuma revalidação automática; o dentista é o único que altera o fuso.
8. **Um só `IN_PROGRESS` por clínica.** Se o dentista esquece de finalizar, o próximo `start` falha até ele finalizar. É intencional (um dentista, uma cadeira); `finish` fica sempre disponível ao dentista.
9. **Mudança de comportamento da Fase 1.** Inativar paciente passa a cancelar agendamentos e a anonimização ganha `CR004`; os testes da Fase 1 que inativam pacientes precisam ser revistos. *Mitigação:* a verificação da fase reexecuta a suíte da Fase 1.
10. **Correção pendente na spec do banco 4.6.** A pré-checagem de conflito por `&&` não é usada até a função `banco` corrigir a consulta; o backend não depende dela.
11. **Consulta da agenda com recuo de 12 h** só é correta enquanto o teto de duração for 12 h (S10); se o teto mudar, a consulta muda junto.
12. **Auditoria que falha bloqueia a operação** (sem `app.user_id` a gravação falha). Intencional; cada escrita em `appointments` custa uma linha de auditoria, e cancelar em lote ao inativar gera várias.
13. **Dumps do `crm_backup`** continuam trazendo dados até 30 dias; a política de privacidade deve citar a janela (herdado da Fase 1).

**Conflitos e lacunas sinalizados**
- O `AGENTS.md` da raiz está vazio; stack, papéis e fases foram lidos de `arq_inicial.md` e dos PRDs/specs. Vale preenchê-lo.
- A visão da Fase 0 e da Fase 1 do backend falava em schema `scheduling`; a decisão A1 do banco manteve tudo em `core`. O pacote Java `scheduling` continua, só não há schema separado.
- `PatientRules.CLINIC_ZONE` (constante `America/Sao_Paulo`, usada na validação de data de nascimento) fica **como está**; alinhá-la ao fuso da clínica é melhoria fora do escopo desta fase.
- O PRD do banco deixou em aberto limites (5 min–12 h, 500 caracteres) e a tradução do paciente inativo; este PRD confirma os valores e usa `CR005`.

**Perguntas abertas**
- ~~Contagem de "no máximo 1 nota por atendimento"~~ **Resolvido (2026-10-24):** serializar por trava da linha do agendamento; o detalhe de implementação fica na spec.
- ~~`GET /appointments` devolve cancelados e faltas por padrão?~~ **Resolvido (2026-10-24):** sim, devolve todos os status, com filtro `status` repetível.
- Exportação de dados do titular e prazo de 20 anos de guarda (herdadas da Fase 1; não mudam nesta fase).
- Armazenamento do refresh token no React (herdado, pendente da spec do frontend).

**Lançamento em etapas**
- **MVP (Fase 2):** US-P2-01 a US-P2-14.
- **v1.1 (Fase 3):** `procedureTypeId`, duração padrão e cor por procedimento; carimbos de início e fim reais se a Fase 4 pedir.
- **v2.0:** lembretes e confirmação pelo paciente, múltiplos profissionais, permuta de horários, trigger de transição no banco e auditoria de leitura.

**Fatiamento sugerido para o `breakdown` (referência, a confirmar após aprovação):** (1) plataforma da agenda e fuso (`Clinic.timezone`, módulo `scheduling` vazio, mapeamento `validate`, tradução de `23P01`/`CR004`/`CR005`, `PatientLookup` estendido, `audit-log` aceita `appointments`, teste de arquitetura); (2) horário comercial e feriados; (3) agendamentos (criar, consultar, remarcar, conflito, expediente/feriado/passado); (4) máquina de status (confirmar, cancelar, falta, iniciar, finalizar, reativar); (5) integração com o núcleo clínico (histórico do paciente, inativar cancela futuros, anonimização `CR004`, nota × atendimento); (6) verificação da fase (matriz de papéis, isolamento com filtro desligado e papel forjado, concorrência, desempenho, log limpo, rastreabilidade). Ordem: 1 → 2 → 3 → 4 → 5 → 6.
