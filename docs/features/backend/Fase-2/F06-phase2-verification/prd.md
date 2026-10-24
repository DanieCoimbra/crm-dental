# PRD — F06 Verificação da fase

**Derivação:** PRD global da Fase 2, metas 1 a 7 da seção 1, US-P2-13 e US-P2-14 e critérios de saída; spec global, seção 8. Fecha o teste 84 do banco (`ddl-auto=validate` contra V001–V021) e o teste de concorrência do banco pela API.
**Dependências externas:** F01 a F05 implementadas, o harness do banco (`database/bootstrap` e `database/migrations` via Testcontainers, com `btree_gist`) e o Docker em execução.

## 1. Introdução

- **Problema:** cada feature tem testes próprios, mas as garantias da fase (papel, isolamento, conflito concorrente, higiene de log, desempenho) cruzam features e precisam de uma verificação única antes de declarar a Fase 2 pronta.
- **Solução:** uma suíte de verificação transversal, sem código de produção novo, que prova as metas do PRD global e serve de gate de qualidade, mais um documento de rastreabilidade das histórias.
- **Metas:**
  1. Matriz papel × endpoint cobre 100% das rotas novas e alteradas.
  2. Concorrência: 2 `POST /appointments` simultâneos no mesmo horário e clínica → um 201 e um 409, em 20 repetições, sem 500; clínicas diferentes passam as duas.
  3. Isolamento e papel forjado provados com o filtro Hibernate desligado em agenda, horário comercial, feriados e histórico.
  4. Agenda da semana p95 ≤ 100 ms (20 mil agendamentos, 4 concorrentes); criação p95 ≤ 100 ms.
  5. 0 `notes`, nome de paciente ou CPF em log, `toString` ou erro.
  6. `mvn verify` verde em máquina limpa; cobertura ≥ 80% nos services de `scheduling` e nos alterados de `patient`/`record`.

## 2. User stories

### US-001: Matriz de papéis
**Descrição:** Como responsável pelo produto, quero uma prova de que cada rota respeita o papel.

**Critérios de aceite:**
- [ ] `@WebMvcTest` com `spring-security-test` cobre as rotas novas e alteradas × {sem token, R, D}.
- [ ] R recebe 403 em `start`, `finish`, `reactivate`, `PUT /business-hours`, escritas em `/clinic-holidays` e `PUT /clinic`, sem executar instrução no banco.
- [ ] `allowOutsideHours=true` da recepção → 403.

### US-002: Isolamento e papel forjado no banco
**Descrição:** Como titular da clínica, quero que a RLS proteja os dados mesmo se o backend errar.

**Critérios de aceite:**
- [ ] Com duas clínicas e o filtro desligado (inclusive SQL nativo), nenhuma consulta devolve linhas da outra.
- [ ] Com `app.role = RECEPTIONIST` forjado, `IN_PROGRESS`/`COMPLETED` não são gravados, linhas em estado do dentista não mudam e `business_hours`/`clinic_holidays` não aceitam escrita; papel ausente ou inválido nega tudo.
- [ ] `ddl-auto=validate` passa contra V001–V021.

### US-003: Concorrência e desempenho
**Descrição:** Como dentista, quero que dois atendimentos nunca ocupem o mesmo horário e que a agenda abra rápido.

**Critérios de aceite:**
- [ ] Teste de concorrência com duas threads em transações separadas, 20 repetições.
- [ ] Plano da consulta da agenda sem `Seq Scan` com 20 mil agendamentos; p95 ≤ 100 ms (limite sobrescrevível por `-Dperf.limit-ms`).

### US-004: Auditoria da agenda e higiene de log
**Descrição:** Como dentista, quero saber quem alterou a agenda sem que dado pessoal vaze.

**Critérios de aceite:**
- [ ] `GET /audit-log?tableName=appointments` devolve `INSERT`/`UPDATE` com autor e instante, sem valores; `business_hours`, `clinic_holidays` e `holidays` não aparecem.
- [ ] Falha na auditoria (`28000`) reverte a operação e responde 500 sem detalhes.
- [ ] Teste de higiene de log (validado por mutação) sem `notes`, nome ou CPF.

## 3. Requisitos funcionais

- **FR-1:** Suíte transversal em `src/test` (web, integração, arquitetura), sem código de produção novo.
- **FR-2:** Auxiliar de IT aplicando V001–V021 e o bootstrap com `btree_gist`.
- **FR-3:** Reexecutar a suíte das Fases 0 e 1, ajustando os testes que inativam pacientes.
- **FR-4:** `traceability.md` mapeando cada história (US-P2-01 a US-P2-14) a seus testes.

## 4. Fora do escopo

- Testes de carga em ambiente remoto e CI/CD (adiado).
- Testes de frontend.

## 5. Considerações técnicas e riscos

- O teste de concorrência é sensível a tempo; usar sincronização explícita (`CountDownLatch`) em vez de `sleep`.
- Os limites de desempenho em CI podem ser relaxados por `-Dperf.limit-ms`.
