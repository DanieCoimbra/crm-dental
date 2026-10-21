# PRD — F01 Papéis e Bootstrap

**Derivação:** PRD global do banco, US-002 e FR-1; riscos 1 e 2 (papéis e Data API do Supabase); spec global, seções 1.1, 1.2, 3.2, 5.2 e 7.
**Dependências externas:** projeto Supabase criado (plano gratuito). Nenhuma outra feature. É a base de F02 a F06.

## 1. Introdução

- **Problema:** a API, as migrations e o backup precisam de credenciais distintas e de menor privilégio; o Supabase cria papéis amplos (`anon`, `authenticated`, `service_role`) que não podem alcançar os dados.
- **Solução:** um script de *bootstrap* idempotente, executado uma vez como `postgres`, que cria os papéis e o schema `core` e verifica que nenhum papel é privilegiado; mais a migration `V001` que endurece os privilégios.
- **Metas:**
  1. Quatro papéis criados, nenhum com `SUPERUSER`, `BYPASSRLS`, `CREATEROLE` ou `CREATEDB`.
  2. `anon`, `authenticated` e `service_role` sem `USAGE` em `core`.
  3. Rodar o bootstrap duas vezes produz o mesmo estado, sem erro.
  4. 0 senhas em arquivos do repositório.

## 2. User stories

### US-001: Criar papéis e schema
**Descrição:** Como responsável pelo banco, quero criar os papéis e o schema de forma reproduzível.

**Critérios de aceite:**
- [ ] Cria `crm_definer` (NOLOGIN), `crm_owner`, `crm_app` e `crm_backup` (LOGIN) e `CREATE SCHEMA core AUTHORIZATION crm_owner`.
- [ ] `GRANT crm_definer TO crm_owner`.
- [ ] Idempotente: segunda execução não falha e não duplica nada.
- [ ] Senhas chegam como verificadores SCRAM-SHA-256 (variáveis do `psql`), nunca em texto claro nem em arquivo.

### US-002: Verificar que ninguém é privilegiado
**Descrição:** Como responsável pela segurança, quero que o bootstrap aborte se algum papel for perigoso.

**Critérios de aceite:**
- [ ] Aborta se qualquer papel tiver `rolsuper`, `rolbypassrls`, `rolcreaterole`, `rolcreatedb` ou `rolreplication`.
- [ ] Aborta se algum papel for membro de `anon`, `authenticated`, `service_role`, `supabase_admin` ou de papel com `BYPASSRLS`.

### US-003: Endurecer privilégios
**Descrição:** Como responsável pela segurança, quero que o schema `core` nasça sem acesso para ninguém.

**Critérios de aceite:**
- [ ] `V001` revoga tudo de `PUBLIC`, `anon`, `authenticated`, `service_role` em `core`; o bootstrap revoga `CREATE` de `PUBLIC` em `public` (o `crm_owner` não é dono de `public`).
- [ ] Define `ALTER DEFAULT PRIVILEGES` sem concessões automáticas.
- [ ] `crm_app` e `crm_backup` recebem apenas `USAGE` em `core`; `crm_definer` também.

### US-004: Limites de sessão do papel da API
**Descrição:** Como operador, quero timeouts que protejam o plano gratuito.

**Critérios de aceite:**
- [ ] `crm_app`: `statement_timeout = 15s`, `idle_in_transaction_session_timeout = 30s`, `search_path = core, pg_catalog`.

## 3. Requisitos funcionais

- **FR-1:** O bootstrap roda como `postgres`, via workflow manual `db-bootstrap.yml` (ambiente `production`).
- **FR-2:** Atributos: `NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS` nos quatro papéis.
- **FR-3:** O `crm_owner` é dono de `core` e de tudo que as migrations criarem; ninguém mais é dono.
- **FR-4:** Documentar a checklist manual do projeto Supabase: Data API desligada, `core` fora dos schemas expostos, chaves `anon`/`service_role` fora do repositório, TLS obrigatório.

## 4. Fora do escopo

- Tabelas, RLS e funções (F02 a F04).
- Workflows de deploy e backup (F05, F06).
- Rotação automática de senhas.

## 5. Considerações técnicas e riscos

- `CREATE ROLE` / `ALTER ROLE ... NOBYPASSRLS` precisam ser permitidos ao `postgres` do Supabase (validar no projeto real).
- Formato de usuário do Supavisor (`usuario.<project-ref>`) a confirmar.
- **Pergunta aberta:** nenhuma.
