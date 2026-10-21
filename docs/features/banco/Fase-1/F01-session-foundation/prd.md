# PRD — F01 Fundação de Sessão e Extensão (Fase 1)

**Derivação:** PRD global da Fase 1, US-107 (reforço de papel) e FR-11; spec global da Fase 1, seções 1.1, 2.5 (V007) e 5.2.
**Dependências externas:** Fase 0 completa (F02 `core.current_clinic_id()`, F01 papéis). Consumida por F02 a F06 da Fase 1 e pelo backend (aspecto de tenant).

## 1. Introdução

- **Problema:** a Fase 0 reservou `app.role` e `app.user_id` mas nenhuma política os lê; as tabelas clínicas precisam deles, além da busca por trecho de nome (`pg_trgm`).
- **Solução:** funções de leitura da sessão que falham fechado, o parâmetro único de retenção, a permissão do `crm_definer` para a função de tenant e a instalação da extensão `pg_trgm`.
- **Metas:**
  1. `current_app_role()` devolve `NULL` para valor ausente, vazio ou fora de `DENTIST_ADMIN`/`RECEPTIONIST`.
  2. `current_user_id()` devolve `NULL` sem a variável e `uuid` válido com ela.
  3. O `crm_definer` avalia `current_clinic_id()` dentro das políticas das features seguintes.
  4. `pg_trgm` disponível para o `crm_owner` criar o índice GIN.

## 2. User stories

### US-001: Papel da sessão que falha fechado
**Descrição:** Como política RLS, quero ler o papel do usuário na clínica sem nunca aceitar valor inválido.

**Critérios de aceite:**
- [ ] `core.current_app_role()` devolve `DENTIST_ADMIN` ou `RECEPTIONIST` só para esses valores exatos; qualquer outro, ausente ou vazio → `NULL`.
- [ ] `STABLE`, `PARALLEL SAFE`, `search_path = pg_catalog`, sem `SECURITY DEFINER`.

### US-002: Usuário da sessão
**Descrição:** Como trigger de autoria e auditoria, quero ler o id do usuário autenticado.

**Critérios de aceite:**
- [ ] `core.current_user_id()` devolve o `uuid` de `app.user_id`; ausente ou vazio → `NULL`; valor que não é UUID aborta com `22P02`.

### US-003: Parâmetro de retenção
**Descrição:** Como responsável pela conformidade, quero o prazo de guarda em um único lugar.

**Critérios de aceite:**
- [ ] `core.clinical_retention_period()` devolve `interval '20 years'`, `IMMUTABLE`.

### US-004: Permissões para o `crm_definer`
**Descrição:** Como função `SECURITY DEFINER`, quero avaliar as políticas escopadas ao tenant.

**Critérios de aceite:**
- [ ] `EXECUTE` de `current_clinic_id()`, `current_app_role()` e `current_user_id()` concedido a `crm_app` e `crm_definer`; revogado de `PUBLIC`.

### US-005: Extensão `pg_trgm`
**Descrição:** Como responsável pelo banco, quero a extensão instalada pelo bootstrap, fora do alcance do `crm_app`.

**Critérios de aceite:**
- [ ] `bootstrap/20_extensions.sql` idempotente cria o schema `extensions`, a extensão `pg_trgm` nele e dá `USAGE` a `crm_owner`.
- [ ] `crm_app` não recebe `USAGE` em `extensions`.
- [ ] Se a extensão não puder ser criada, o script falha com mensagem que aponta o plano B (só busca por prefixo).

## 3. Requisitos funcionais

- **FR-1:** Migration `V007__core_session_functions.sql` com as três funções e os `GRANT EXECUTE`.
- **FR-2:** Arquivo `database/bootstrap/20_extensions.sql`, executado como `postgres` uma vez.
- **FR-3:** O harness local (`bootstrap_checks.sh`) executa o `20_extensions.sql` depois do `00_`.
- **FR-4:** Documentar em `database/README.md` o contrato das três variáveis de sessão.

## 4. Fora do escopo

- Tabelas e políticas (F02 em diante).
- Qualquer leitura de `app.role` nas tabelas da Fase 0 (continuam só por tenant).
- Instalar outras extensões (`pgcrypto`, `unaccent`, `btree_gist`).

## 5. Considerações técnicas e riscos

- O banco confia no `app.role` informado pelo backend; limite aceito (risco 1 do PRD global).
- No Supabase o schema `extensions` já existe e a criação da extensão pode exigir o painel; validar (spec global, seção 10).
- **Pergunta aberta:** nenhuma.
