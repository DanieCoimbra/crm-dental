# Contract — F01 Papéis e Bootstrap

## 1. Resumo
- **Feature:** F01-roles-and-bootstrap
- **Versão:** 1.0.0
- **Consumidores:** F02–F06 (banco), backend (conexão), GitHub Actions.

## 2. Entradas

### 2.1 Variáveis do `psql` (bootstrap)
| Variável | Regra |
|---|---|
| `crm_owner_scram`, `crm_app_scram`, `crm_backup_scram` | Verificadores `SCRAM-SHA-256$4096:...` gerados por `database/scripts/scram_verifier.sh` a partir das senhas (≥ 24 caracteres, aleatórias, distintas). **A senha em texto claro nunca vai numa instrução SQL** |

### 2.2 Secrets do GitHub (ambiente `production`)
`SUPABASE_ADMIN_URL` (usuário `postgres`, pooler *session*), `CRM_OWNER_PASSWORD`, `CRM_APP_PASSWORD`, `CRM_BACKUP_PASSWORD`.

## 3. Saídas

### 3.1 Papéis
| Papel | Login | Atributos | Privilégios entregues |
|---|---|---|---|
| `crm_owner` | sim | `NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS` | Dono de `core`; membro de `crm_definer` |
| `crm_app` | sim | idem | `USAGE` em `core` (demais grants vêm das features seguintes) |
| `crm_backup` | sim | idem | `USAGE` em `core` (leitura vem de F03/F06) |
| `crm_definer` | não | `NOLOGIN` + mesmos atributos | `USAGE` em `core` |

### 3.2 Objetos
Schema `core` (dono `crm_owner`). Parâmetros de papel: `crm_app` → `statement_timeout=15s`, `idle_in_transaction_session_timeout=30s`, `search_path=core,pg_catalog`.

### 3.3 Conexões (contrato com o backend e CI)
| Uso | Modo / porta | Usuário |
|---|---|---|
| API | transaction / 6543 | `crm_app.<project-ref>` |
| Migrations | session / 5432 | `crm_owner.<project-ref>` |
| Backup | session / 5432 | `crm_backup.<project-ref>` |
| Bootstrap | session / 5432 | `postgres.<project-ref>` |
TLS obrigatório em todas (o workflow recusa `SUPABASE_ADMIN_URL` sem `sslmode=require`/`verify-*`); driver da API com `prepareThreshold=0`.

### 3.4 Checklist manual do Supabase
- [ ] Data API desligada; `core` fora dos schemas expostos.
- [ ] Auth, Storage e Realtime não usados.
- [ ] Chaves `anon` e `service_role` fora do repositório e dos serviços.
- [ ] SSL obrigatório ativo.

## 4. Regras e limites
- Nenhum papel é superusuário nem tem `BYPASSRLS`, `CREATEROLE` ou `CREATEDB`.
- Nenhum papel recebe `anon`, `authenticated`, `service_role` ou `supabase_admin`.
- O `postgres` só é usado pelo `db-bootstrap.yml`.
- O bootstrap é idempotente e nunca grava senha em arquivo ou log (nem no log do servidor: só o verificador SCRAM trafega no SQL).
- O administrador usado no bootstrap permanece membro de `crm_owner` (necessário para criar o schema em seu nome); a verificação roda como esse mesmo administrador.

## 5. Eventos emitidos
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] Executar o bootstrap duas vezes seguidas termina sem erro e sem alterar o estado.
- [ ] Teste 17: `rolsuper`, `rolbypassrls`, `rolcreaterole`, `rolcreatedb` falsos para `crm_owner`, `crm_app` e `crm_backup`.
- [ ] Teste 19: `crm_app` não lê `core.flyway_schema_history`.
- [ ] Teste 20: `crm_app` não consegue `SET ROLE crm_definer` nem `crm_owner`.
- [ ] Teste 21: `anon`, `authenticated`, `service_role` sem `USAGE` em `core`.
- [ ] Forçar um papel com `BYPASSRLS`, ou uma associação a papel privilegiado, ou dono errado do schema, faz a verificação (e, portanto, o bootstrap) abortar.
- [ ] Nenhuma senha aparece nos logs do workflow nem na instrução SQL enviada ao servidor (só o verificador SCRAM).
- [ ] O login SCRAM de cada papel funciona com a senha original e falha com senha errada.
- [ ] `flyway_schema_history` inacessível a `crm_app` (teste 19 completo): verificado na F05, quando o Flyway real cria a tabela.
