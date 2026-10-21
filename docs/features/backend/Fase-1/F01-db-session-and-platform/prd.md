# PRD — F01 Sessão do banco e plataforma

**Derivação:** PRD global da Fase 1, US-P1-11, FR-11, FR-12 (reforço de papel), FR-16 e FR-17 (parte de plataforma); decisão E4. Detalhe técnico em `docs/global/backend/Fase-1/spec.md` (seções 3.1, 4.1, 5.1, 6.1 e 7).
**Dependências externas:** Fase 0 (F02 tenant, F01 plataforma e erros) e as migrations V007–V013 do banco (`core.current_app_role()`, `core.current_user_id()`, códigos `CR001`–`CR003`). Todas as outras features da Fase 1 dependem desta.

## 1. Introdução

- **Problema:** hoje a transação só informa ao banco o `app.clinic_id`. A Fase 1 tem RLS por papel e auditoria por usuário, que exigem também `app.role` e `app.user_id`; os erros novos do banco (`CR001`–`CR003`, `42501`) ainda viram 500; e a mensagem do driver para CPF duplicado repete o CPF e iria para o log.
- **Solução:** estender o ponto único que fixa o tenant para fixar as três variáveis de sessão, traduzir os erros do banco em `ProblemDetail` sem logar valores e garantir cabeçalhos de cache e higiene de log para as rotas clínicas.
- **Metas:**
  1. 100% das transações autenticadas definem as três variáveis, em uma única instrução (nenhuma ida extra ao banco).
  2. Com pool de 1 conexão, nenhuma variável vaza entre requisições.
  3. Sem `app.role` válido, as tabelas clínicas negam tudo (provado com o filtro Hibernate desligado).
  4. Nenhuma exceção de banco registra a mensagem do driver; o CPF não aparece em log.
  5. Os 250 testes unitários e 100 ITs da Fase 0 continuam verdes após a mudança.

## 2. User stories

### US-001: Papel e usuário na sessão do banco
**Descrição:** Como responsável pelo produto, quero que o banco receba o papel e o usuário de cada transação para reforçar a separação entre dentista e recepção.

**Critérios de aceite:**
- [ ] Toda transação autenticada fixa `app.clinic_id`, `app.role` e `app.user_id` com `set_config(..., true)`, a partir do `TenantContext`, em uma só instrução.
- [ ] Fluxos sem usuário autenticado (registro, refresh, logout, via `TenantRunner`) fixam `app.role` e `app.user_id` como texto vazio; o banco os lê como `NULL`.
- [ ] Teste com pool de 1 conexão: após a transação A (dentista), a transação B (outro tenant, recepcionista) não enxerga nenhum valor de A.
- [ ] Teste de papel forjado: com `app.role = RECEPTIONIST` e o filtro Hibernate desligado, uma leitura de tabela clínica devolve 0 linhas; com `app.role` ausente ou inválido, até `patients` devolve 0 linhas.
- [ ] Nenhum service, controller ou DTO define essas variáveis (verificado por teste de arquitetura ou busca no código).

### US-002: Erros do banco em ProblemDetail
**Descrição:** Como front-end, quero erros claros e estáveis quando o banco recusa uma operação.

**Critérios de aceite:**
- [ ] `CR001`, `CR002` e `CR003` resultam em 409 `conflict`; `CR002` informa a data de liberação extraída do `DETAIL`; `P0002` em 404; `42501` em 403.
- [ ] `28000`, `23514` e `23503` resultam em 500 `internal` sem detalhe, com log de erro contendo só classe, `SQLState` e nome da constraint.
- [ ] Violação de unicidade nunca é registrada com a mensagem do driver.
- [ ] Os mapeamentos existentes (`OptimisticLockingFailureException` → 409, indisponibilidade → 503) continuam iguais.

### US-003: Cache e higiene de log nas rotas clínicas
**Descrição:** Como responsável pelo produto, quero que respostas e logs não retenham dado de saúde.

**Critérios de aceite:**
- [ ] Respostas de `/api/v1/patients/**` e `/api/v1/audit-log/**` levam `Cache-Control: no-store`.
- [ ] Nos três perfis, `show-sql` está desligado e os loggers `org.hibernate.SQL` e `org.hibernate.orm.jdbc.bind` não registram parâmetros; não há *access log* do Tomcat.
- [ ] Teste de captura de log: uma violação de unicidade de CPF não deixa o CPF em nenhuma linha de log.

## 3. Requisitos funcionais

- **FR-1:** O dialeto de transação deve fixar `app.clinic_id`, `app.role` e `app.user_id` em uma única instrução, no início de toda transação.
- **FR-2:** O `TenantIdProvider` deve expor papel e usuário sem lançar 401 quando ausentes (para os fluxos de `TenantRunner`); `clinicId()` continua lançando.
- **FR-3:** Deve existir um utilitário de erros de banco que devolva `SQLState`, nome da constraint e a data de `CR002`, sem depender de classes do driver.
- **FR-4:** O handler global deve traduzir `SQLState` conforme a tabela do contrato, sem logar a mensagem do driver.
- **FR-5:** As constraints `ux_patients_clinic_cpf` e `ux_histories_patient` devem ter constantes em `ConstraintViolations`.
- **FR-6:** Rotas clínicas devem responder com `Cache-Control: no-store`.
- **FR-7:** Os testes da Fase 0 que afirmam o texto SQL do tenant devem ser atualizados.

## 4. Fora do escopo

- Entidades e endpoints de negócio (F02–F05).
- Storage, multipart e limites de upload (F04).
- Qualquer alteração de banco (migrations).
- Mudança no JWT ou no `TenantContextFilter` (o papel e o usuário já estão no contexto).

## 5. Considerações técnicas e riscos

- O banco confia no `app.role` informado: o ponto único e o teste de papel forjado são a mitigação (risco 1 do PRD global).
- O `Cache-Control` das rotas de auth já existe; confirmar que o `SecurityHeadersConfig` aceita estender a lista sem conflito.
- **Pergunta aberta:** nenhuma.
