# PRD — F02 Contexto de Tenant (backend)

**Derivação:** PRD global, US-007 e FR-7, FR-10; risco 5 (pool + `SET LOCAL`).
**Dependências externas:** F01 (erros e perfis). Consome o contrato do banco (spec global, seção 2), entregue pela função **banco** (`postgres-dba-architect`). Fornece o contexto de tenant que a F04 preenche a partir do token.

## 1. Introdução

- **Problema:** a RLS do banco só isola se a aplicação informar o tenant correto em cada transação; e a aplicação precisa de uma primeira barreira própria.
- **Solução:** `TenantContext` por requisição, filtro Hibernate por `clinic_id` e aspecto transacional que define `app.clinic_id` no banco.
- **Metas:**
  1. Com o filtro Hibernate desligado, a RLS do banco ainda bloqueia o acesso cruzado (teste que valida a integração backend ↔ banco).
  2. Nenhum `app.clinic_id` vaza entre requisições com pool de 1 conexão.
  3. 100% das entidades de negócio herdam `TenantAwareEntity`.
  4. O `clinic_id` nunca é lido de corpo, query ou URL.

## 2. User stories

### US-001: Tenant definido por transação
**Descrição:** Como desenvolvedor de módulo, quero que o tenant seja aplicado automaticamente em toda transação.

**Critérios de aceite:**
- [ ] Toda transação executa `set_config('app.clinic_id', <uuid>, true)` com o `clinic_id` do token e ativa o filtro Hibernate.
- [ ] Sem tenant no contexto, operações de negócio falham com 401 antes de tocar o banco.
- [ ] O contexto é limpo ao fim da requisição.

### US-002: Entidades isoladas por padrão
**Descrição:** Como desenvolvedor das fases 1–4, quero uma superclasse que garanta o `clinic_id`.

**Critérios de aceite:**
- [ ] `TenantAwareEntity` define `clinic_id` imutável, preenchido pelo contexto.
- [ ] Teste de arquitetura falha se uma entidade de negócio não herdar `TenantAwareEntity`.

### US-003: Tarefas fora de requisição
**Descrição:** Como desenvolvedor, quero um modo explícito de definir o tenant em tarefas assíncronas ou agendadas.

**Critérios de aceite:**
- [ ] API explícita para executar um bloco sob um tenant; sem ela, a tarefa não enxerga dados.

## 3. Requisitos funcionais

- **FR-1:** A aplicação deve conectar com o papel de aplicação do banco (`DB_APP_USER`) e nunca com o dono.
- **FR-2:** A cada transação, definir `app.clinic_id` com `is_local = true`.
- **FR-3:** Habilitar o filtro Hibernate `tenantFilter` em toda sessão que acesse tabelas de negócio.
- **FR-4:** Entregar `TenantContext`, `TenantContextFilter`, `TenantJpaTransactionManager` (com `TenantHibernateJpaDialect`), `TenantAwareEntity` e `TenantIdProvider`.
- **FR-5:** Consultas a tabelas de negócio apenas por JPA/JPQL; SQL nativo exige revisão.

## 4. Fora do escopo

- Tabelas, papéis, políticas RLS, função de tenant e migrations: função **banco**.
- Funções `SECURITY DEFINER` de login e refresh: função banco, consumidas pela F04.
- Escolha do provedor de PostgreSQL.
- Particionamento ou banco por clínica.

## 5. Considerações técnicas e riscos

- Dependência do banco: o nome da variável de sessão (`app.clinic_id`) e da função de tenant devem ser confirmados no dicionário de dados do banco.
- Pooler em modo *transaction* exige `prepareThreshold=0`.
- **Pergunta aberta:** nenhuma.
