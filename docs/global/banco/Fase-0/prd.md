# PRD Global (Banco) — CRM Clínica Dental

> **Flyway abandonado (2026-10-21):** o projeto não usa mais o Flyway. As migrations são aplicadas por `database/scripts/apply_migrations.sh` (psql, uma transação por arquivo), **sem histórico nem checksum no banco**; `flyway.conf`, `flyway.sh`, `pg_uri_to_flyway_env.sh`, `core.flyway_schema_history` e os testes 23/24 (histórico e `validate`) deixaram de existir. Onde este documento cita Flyway, é o desenho original da Fase 0; vale o que está em `database/README.md`.

**Escopo:** modelo de dados, papéis, segurança (RLS), migrations e operação do PostgreSQL. Detalhamento da **Fase 0**; as fases 1–4 aparecem só como visão.
**Fontes:** `docs/arq_inicial.md`, `docs/global/backend/{prd,spec}.md` (seção 2: contrato exigido do banco), `docs/features/backend/F01..F05`.
**Função no pipeline:** `banco`. O backend não cria nem altera objetos de banco.

## Decisões fechadas

| # | Decisão | Valor |
|---|---|---|
| B1 | Estrutura | Um banco PostgreSQL, **um schema por módulo**, **um único papel de aplicação** com `GRANT` por schema |
| B2 | Provedor | **Supabase, plano gratuito, usado como PostgreSQL** (sem Auth, Realtime nem Data API; o **Storage** só a partir da Fase 1, bucket privado e só pelo backend) *(Revisada na Fase 1, 2026-10-21: o Supabase Storage passa a guardar os exames, em bucket privado acessado só pelo backend; ver `docs/global/banco/Fase-1/prd.md`, P5.)*; com pooler (Supavisor) |
| B3 | Migrations | **Flyway CLI em contêiner**, arquivos `V<NNN>__descricao.sql`, executado com o papel dono |
| B4 | Tenant | `clinic_id uuid` em toda tabela de negócio; RLS `ENABLE` + `FORCE`; `app.clinic_id` via `set_config(..., true)` |
| B5 | Criptografia | AES-256-GCM **na aplicação** (F03 do backend); o banco guarda texto cifrado e nunca vê o texto claro |
| B6 | Trial | Não existe: nenhuma coluna de plano, trial ou expiração |
| B8 | Ambientes | **Só produção.** Sem homologação; a validação é o banco descartável do PR |
| B7 | CI/CD | **GitHub Actions**: valida as migrations em PostgreSQL descartável a cada PR e aplica no Supabase por Flyway CLI em contêiner, com segredos do GitHub |

## Padrões adotados (sem pergunta; contestar se discordar)

- Nomes em `snake_case`, tabelas no plural, FKs `<entidade>_id`.
- Chave primária `uuid` (gerada pela aplicação; `DEFAULT gen_random_uuid()` como reserva). Justificativa: o registro precisa conhecer o `clinic_id` antes de inserir, e UUIDs não expõem sequência entre tenants.
- `timestamptz` para instantes, `numeric(12,2)` para dinheiro, `text` com `CHECK` de tamanho ou `varchar(n)`.
- E-mail: coluna sempre em minúsculas (`CHECK (email = lower(email))`) com índice único simples. Sem `citext`, para reduzir dependência de extensão.
- Status e papéis como `text` + `CHECK`.
- `updated_at` mantido por trigger; `version bigint` para o `@Version` do JPA.
- Exclusão lógica por `active` apenas em `users`; demais tabelas da Fase 0 sem exclusão lógica.
- Sem `audit_log` na Fase 0 (só `created_by`); trilha de auditoria entra com os dados clínicos (Fase 1).
- Extensões na Fase 0: nenhuma obrigatória (`gen_random_uuid()` é nativo). `btree_gist` entra na Fase 2 (conflito de agenda); `pg_trgm` na Fase 1, se a busca de pacientes pedir.
- Papéis dentro do tenant (`app.role`): o contrato é definido agora, mas só é reforçado no banco a partir da Fase 1 (prontuário e financeiro). Na Fase 0 isso fica no backend.
- Backup: o plano gratuito do Supabase **não oferece PITR nem backups gerenciados confiáveis**; o backup passa a ser o `pg_dump` lógico **diário** por workflow agendado do GitHub Actions, criptografado e guardado fora do Supabase; retenção de 30 dias; restauração testada antes de ir para produção.
- Pausa por inatividade: o plano gratuito pausa o projeto após ~7 dias sem atividade; um workflow agendado faz uma consulta leve (*keep-alive*) a cada 3 dias.

---

## 1. Resumo executivo

**Problema:** o produto guarda dados de saúde de várias clínicas no mesmo banco; um erro de consulta no backend não pode expor dados entre clínicas.

**Solução:** schema versionado, papéis de menor privilégio e RLS que nega por padrão, entregues como contrato que o backend consome (`ddl-auto=validate`).

**Metas e métricas (Fase 0):**
1. Migrations aplicam do zero em PostgreSQL limpo e são idempotentes quanto à ordem (teste em contêiner).
2. Com `app.clinic_id = A`, o papel de aplicação lê, altera e apaga 0 linhas da clínica B; `INSERT` com `clinic_id = B` falha.
3. Sem `app.clinic_id`, 0 linhas visíveis e 0 gravações possíveis.
4. O papel de aplicação não executa `DROP`, `TRUNCATE`, `ALTER` nem `CREATE` e não tem `BYPASSRLS`.
5. Consultas de login e refresh usam índice único (plano `Index Scan`, ≤ 5 ms com 100 mil usuários).
6. Backup diário restaurável: o último `pg_dump` restaura em um PostgreSQL limpo sem erro (verificado por workflow mensal).

---

## 2. Escopo da Fase 0

Módulo (schema) `core`: tabelas `clinics`, `users` e `refresh_tokens`, funções de bootstrap, função de tenant, papéis, grants, triggers e migrations. As demais tabelas (pacientes, agenda, financeiro etc.) são planejadas depois.

---

## 3. User stories — Fase 0

### US-001: Esquema versionado e reprodutível
**Descrição:** Como responsável pelo banco, quero migrations versionadas para recriar o banco do zero.

**Critérios de aceite:**
- [ ] Flyway CLI em contêiner aplica `V001…` em PostgreSQL limpo, com o papel dono.
- [ ] O workflow de PR no GitHub Actions aplica todas as migrations do zero em contêiner PostgreSQL e executa os testes de isolamento; PR com falha não pode ser mesclado.
- [ ] Migrations aplicadas são imutáveis; cada uma documenta o rollback.
- [ ] A tabela de histórico do Flyway fica fora do alcance do papel de aplicação.

### US-002: Papéis de menor privilégio
**Descrição:** Como responsável pela segurança, quero que a aplicação use um papel limitado.

**Critérios de aceite:**
- [ ] Papéis: `crm_owner` (dono, só migrations), `crm_app` (aplicação) e `crm_backup` (somente leitura, para o `pg_dump`); nenhum é superusuário e nenhum tem `BYPASSRLS`.
- [ ] `crm_app`: `NOBYPASSRLS`, não dono, só `SELECT/INSERT/UPDATE/DELETE` nas tabelas do schema `core` (por tabela, sem `GRANT ALL`), `USAGE` no schema, `EXECUTE` nas funções necessárias.
- [ ] `REVOKE ALL ON SCHEMA public FROM PUBLIC`.
- [ ] Senhas dos papéis vêm de segredo externo, nunca do repositório.

### US-003: Isolamento por clínica
**Descrição:** Como titular de uma clínica, quero que meus dados nunca apareçam para outra.

**Critérios de aceite:**
- [ ] `clinics`, `users` e `refresh_tokens` com RLS `ENABLE` + `FORCE` e políticas por operação.
- [ ] Função de tenant devolve `NULL` quando `app.clinic_id` não está definido, e `NULL` nunca casa com nenhuma linha.
- [ ] Testes de isolamento obrigatórios passam (ver spec).

### US-004: Login e refresh antes de existir tenant
**Descrição:** Como backend, preciso localizar o usuário por e-mail e o refresh token por hash antes de saber a clínica.

**Critérios de aceite:**
- [ ] Duas funções `SECURITY DEFINER` com `search_path` fixo, `EXECUTE` só para `crm_app`, retorno mínimo.
- [ ] Nenhum outro caminho de leitura sem tenant.

### US-005: Registro de clínica atômico
**Descrição:** Como backend, quero criar clínica e dentista em uma transação, informando o tenant antes.

**Critérios de aceite:**
- [ ] Com `app.clinic_id = X`, `INSERT` em `clinics (id = X)` e em `users (clinic_id = X)` passa; com `id` diferente falha.
- [ ] Segundo `DENTIST_ADMIN` na mesma clínica e e-mail duplicado violam constraint.

### US-007: Entrega contínua das migrations
**Descrição:** Como responsável pelo banco, quero que as migrations cheguem ao Supabase de forma automática e auditável.

**Critérios de aceite:**
- [ ] Workflow no GitHub Actions aplica as migrations no Supabase após merge na `main`, usando a conexão **direta** (sem pooler) e o papel `crm_owner`.
- [ ] Credenciais só como *secrets* do GitHub (ambiente `production` com aprovação manual obrigatória); nunca em arquivos ou logs.
- [ ] Migration já aplicada que for alterada faz o `flyway validate` falhar.
- [ ] A API (backend) nunca roda migrations.

### US-006: Limpeza de tokens expirados
**Descrição:** Como operador, quero que refresh tokens vencidos sejam apagados.

**Critérios de aceite:**
- [ ] Função `SECURITY DEFINER` que apaga tokens expirados há mais de 30 dias, executável só pelo papel de aplicação (ou por job do provedor), sem expor dados.

---

## 4. Requisitos funcionais

- **FR-1:** O banco deve ter os papéis `crm_owner`, `crm_app`, `crm_backup` e `crm_definer` (sem login) com os privilégios da US-002; nenhum com `BYPASSRLS`.
- **FR-2:** O banco deve ter o schema `core` e as tabelas `clinics`, `users`, `refresh_tokens` conforme o contrato do backend (spec global do backend, seção 2.3).
- **FR-3:** Toda tabela de negócio deve ter `clinic_id uuid NOT NULL` com FK, índice e RLS `ENABLE` + `FORCE`, com política por operação (`USING` e `WITH CHECK`).
- **FR-4:** Deve existir a função de tenant (`core.current_clinic_id()` ou equivalente) lendo `app.clinic_id`.
- **FR-5:** `users` deve garantir: e-mail único em minúsculas; no máximo um `DENTIST_ADMIN` por clínica; `role` restrito a `DENTIST_ADMIN` e `RECEPTIONIST`.
- **FR-6:** `refresh_tokens` deve garantir `token_hash` único, `expires_at` obrigatório e índices por `user_id` e `family_id`.
- **FR-7:** Triggers devem manter `updated_at`; `version` é incrementado pela aplicação (JPA).
- **FR-8:** Funções de bootstrap: `find_user_for_login(email)` e `find_refresh_token(hash)`; mais a de limpeza (US-006).
- **FR-9:** Nenhuma coluna de plano, trial, cobrança ou expiração de conta.
- **FR-10:** Nenhum campo de senha em texto claro; `password_hash` recebe só o hash da aplicação.
- **FR-12:** O repositório deve ter workflows do GitHub Actions: validação em PR (migrations do zero + testes de isolamento), aplicação no Supabase após merge, `pg_dump` diário criptografado e *keep-alive* a cada 3 dias.
- **FR-11:** O contrato com o backend (nomes de tabelas, colunas e funções, variável `app.clinic_id`) deve ser publicado em dicionário de dados e confirmado com a spec do backend.

## 5. Fora do escopo

- Tabelas das fases 1–4 e `audit_log` (planejadas depois).
- Execução de SQL e escrita das migrations finais (`dev-engineer-postgres`).
- Reforço de `app.role` no banco (Fase 1+).
- Particionamento, réplicas de leitura e multi-região.
- Criptografia no banco (`pgcrypto`): a cifra é da aplicação.

## 6. Considerações técnicas e riscos

1. **Papéis no Supabase.** O usuário `postgres` do Supabase não é superusuário pleno, e papéis internos (`anon`, `authenticated`, `service_role`) têm privilégios amplos e, no caso de `service_role`, `BYPASSRLS`. Criar `crm_owner` e `crm_app` por SQL, com `NOBYPASSRLS`, **não conceder** a eles nenhum papel interno do Supabase e provar por teste que `crm_app` não contorna a RLS. Verificar no projeto real se `CREATE ROLE` e `ALTER ROLE ... NOBYPASSRLS` são permitidos.
2. **Supabase expõe `public` via API automática (PostgREST).** Manter todas as tabelas em `core`, nunca em `public`; revogar acesso de `anon`/`authenticated` a `core`; desligar a Data API; e **não usar** Auth nem Realtime do Supabase (a autenticação é do backend). O Storage só entra na Fase 1, em bucket privado acessado apenas pelo backend (revisão de 2026-10-21; ver `docs/global/banco/Fase-1/prd.md`, P5). Sem isso, a chave `anon` pública poderia alcançar os dados.
3. **Supavisor (pooler).** A API usa o pooler em modo *transaction*: `set_config(..., true)` é seguro, mas exige `prepareThreshold=0`. A conexão direta do Supabase é IPv6, e os runners do GitHub são IPv4; portanto as migrations (Flyway) usam o pooler em modo *session* (porta 5432 do Supavisor), que aceita DDL e `SET` de sessão. Confirmar no projeto real.
4. **Funções `SECURITY DEFINER`** ampliam a superfície: `search_path` fixo, `REVOKE ... FROM PUBLIC`, retorno mínimo, sem SQL dinâmico.
5. **E-mail único global** impede o mesmo e-mail em duas clínicas (decisão aprovada no backend).
6. **Perda da chave AES** (fora do banco) torna os campos clínicos irrecuperáveis; backup das chaves é responsabilidade do backend/operação.
7. **Segredos de produção no GitHub Actions** (senha do `crm_owner`): usar ambiente protegido com aprovação, mínimo de permissões do workflow e sem imprimir variáveis em log.
8. **Plano gratuito sem PITR, sem SLA e com pausa por inatividade.** Aceito: o projeto é de portfólio e usa apenas dados fictícios, então o risco de LGPD não se aplica. Mantidos o `pg_dump` diário e o *keep-alive* por baixo custo. **Condição:** se o projeto passar a operar com pacientes reais, migrar para plano pago e revisar backup e retenção antes.
9. **Sem homologação**: uma migration só é exercitada no contêiner do PR antes de produção; por isso as migrations devem ser pequenas, reversíveis e aditivas.

**Perguntas abertas**
- Nenhuma. (Plano gratuito e ambiente único de produção confirmados em 2026-10-19.)

## 7. Fases seguintes (visão)

| Fase | Schema | Destaques de banco |
|---|---|---|
| 1 | `core` (+`patients`, `records`) | Pacientes, prontuário cifrado, metadados de arquivos, `audit_log`, `pg_trgm` se necessário |
| 2 | `scheduling` | Agendamentos com `EXCLUDE USING gist` (`btree_gist`), feriados globais e por clínica, tabela de transições |
| 3 | `billing`, `finance` | Procedimentos, orçamentos e parcelas em `numeric(12,2)`, transições de status |
| 4 | `documents`, `dashboard` | Documentos gerados, views de KPIs |
