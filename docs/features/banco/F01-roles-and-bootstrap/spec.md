# Spec — F01 Papéis e Bootstrap

Complementa `docs/global/banco/spec.md` (seções 1.1, 1.2, 3.1, 3.2, 5.2 e 7).

## 1. Visão técnica
- **Feature:** F01 Papéis e Bootstrap.
- **Stack:** PostgreSQL (Supabase), `psql` com variáveis (`-v`), GitHub Actions (`db-bootstrap.yml`), Flyway (para `V001`).
- **Abordagem:** script SQL idempotente fora do Flyway (precisa de privilégio de administrador) + uma migration versionada para os privilégios de `core`.

## 2. Dados e objetos

### 2.1 `database/bootstrap/00_roles_and_schema.sql` (como `postgres`)
Ordem:
1. Receber os verificadores SCRAM (`crm_owner_scram`, `crm_app_scram`, `crm_backup_scram`), validar o formato e recusar qualquer valor que não seja `SCRAM-SHA-256$...`.
2. Criar `crm_definer` se não existir: `NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS`.
2. Criar `crm_owner`, `crm_app`, `crm_backup` se não existirem (`LOGIN`, mesmos atributos), com `PASSWORD` das variáveis `:crm_owner_pw`, `:crm_app_pw`, `:crm_backup_pw`. Se já existirem, apenas `ALTER ROLE ... PASSWORD` (permite rotacionar). Os atributos de privilégio (`SUPERUSER`, `BYPASSRLS`, `CREATEROLE`, `CREATEDB`, `REPLICATION`) são definidos **só na criação**, porque alterar `BYPASSRLS`/`REPLICATION` exige privilégios que o `postgres` do Supabase pode não ter; a verificação do passo 6 **aborta** se algum papel existente divergir, em vez de corrigi-lo em silêncio.
3. `GRANT crm_definer TO crm_owner`.
4. `CREATE SCHEMA IF NOT EXISTS core AUTHORIZATION crm_owner`.
5. `ALTER ROLE crm_app SET statement_timeout = '15s'`, `idle_in_transaction_session_timeout = '30s'`, `search_path = core, pg_catalog`; `crm_backup`: `statement_timeout = '300s'`, `search_path = core, pg_catalog`.
6. Verificação (abaixo), em arquivo próprio `bootstrap/10_verify_roles.sql`, incluído por `\ir` e executável sozinho (somente leitura no catálogo).
7. Antes de criar o schema, `GRANT crm_owner TO <admin>` quando o administrador não for membro (necessário para `CREATE SCHEMA ... AUTHORIZATION` e `ALTER SCHEMA ... OWNER TO` quando o administrador não é superusuário). `REVOKE CREATE ON SCHEMA public FROM PUBLIC` também fica no bootstrap (o `crm_owner` não é dono de `public`).

Ilustrativo:
```sql
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'crm_app') THEN
    CREATE ROLE crm_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
  END IF;
END $$;
```

### 2.2 Bloco de verificação (aborta com `RAISE EXCEPTION`)
- Consulta a `pg_roles`: nenhum dos 4 papéis com `rolsuper`, `rolbypassrls`, `rolcreaterole` ou `rolcreatedb`.
- Consulta a `pg_auth_members`: nenhum dos 4 é membro de `anon`, `authenticated`, `service_role`, `supabase_admin` ou de qualquer papel com `rolbypassrls` (exceto a associação intencional `crm_owner` → `crm_definer`).
- `core` pertence a `crm_owner`.

### 2.3 `V001__core_privileges.sql` (como `crm_owner`)
- `REVOKE ALL ON SCHEMA core FROM PUBLIC` e `FROM anon, authenticated, service_role` (cada `REVOKE` protegido contra papel inexistente no banco de testes).
- `REVOKE CREATE ON SCHEMA public FROM PUBLIC` (quando permitido ao owner; senão feito no bootstrap).
- `GRANT USAGE ON SCHEMA core TO crm_app, crm_backup, crm_definer`.
- Funções futuras: `ALTER DEFAULT PRIVILEGES FOR ROLE crm_owner REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC` **sem** `IN SCHEMA`: o `EXECUTE` para `PUBLIC` é um padrão global do PostgreSQL e um `REVOKE` com escopo de schema não o remove (verificado em teste). Vale para todos os schemas do `crm_owner`, que só possui `core`.
- Tabelas e sequências futuras: `ALTER DEFAULT PRIVILEGES ... IN SCHEMA core REVOKE ALL ... FROM PUBLIC` (explícito; já nascem sem acesso).
- `REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA core FROM PUBLIC` para funções que já existam.

### 2.4 Estado
Sem estado em memória. Credenciais: variáveis do psql / secrets do GitHub.

## 3. Componentes
| Artefato | Responsabilidade |
|---|---|
| `bootstrap/00_roles_and_schema.sql` | Validação de variáveis, papéis, schema e parâmetros de papel |
| `bootstrap/10_verify_roles.sql` | Verificações de segurança (aborta) |
| `tests/bootstrap_checks.sh`, `tests/roles_and_privileges.sql`, `tests/support/supabase_stubs.sql`, `tests/run_all.sh`, `docker-compose.yml` | Testes e harness local (precursor do `db-pr.yml` da F05) |
| `migrations/V001__core_privileges.sql` | Privilégios de `core` |
| `.github/workflows/db-bootstrap.yml` | `workflow_dispatch`; ambiente `production`; lê `SUPABASE_ADMIN_URL` e as 3 senhas; executa `psql -v ON_ERROR_STOP=1` com `-v` para as senhas |
| `docs/…/supabase-checklist` (seção do contract) | Passos manuais de painel |

## 4. Lógica
1. Operador dispara `db-bootstrap.yml` (aprovação do ambiente).
2. Workflow mascara os segredos (`::add-mask::`), conecta como `postgres` pelo pooler *session* e executa o script.
3. O script cria/atualiza papéis, schema e roda as verificações.
4. `V001` é aplicado depois pelo pipeline de migrations (F05).

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Papel já existe | `ALTER ROLE` só atualiza senha/login; atributos divergentes fazem a verificação abortar |
| `CREATE ROLE` não permitido ao `postgres` | Falha clara; documentar plano B (criar papéis pelo painel e rodar só as verificações) |
| Papel nasce membro de papel privilegiado | Verificação aborta; revogar a associação manualmente |
| `anon`/`authenticated`/`service_role` inexistentes (banco de teste) | `REVOKE` protegido por `IF EXISTS (SELECT 1 FROM pg_roles ...)` |
| Senha aparece em log | Proibido; sem `set -x`, com mascaramento |
| Rotacionar senha | Reexecutar o bootstrap com nova senha e atualizar os secrets |

## 6. Segurança e desempenho
- Menor privilégio; nenhum `GRANT ALL`; nenhum papel com `BYPASSRLS`.
- `postgres` usado somente neste workflow.
- Timeouts de `crm_app` evitam consultas e transações presas no plano gratuito.
