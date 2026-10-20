# PRD — F02 Base de Tenant e RLS

**Derivação:** PRD global do banco, US-003 e FR-3, FR-4, FR-7; spec global, seções 2.6, 2.7 e 3.3.
**Dependências externas:** F01 (papéis e schema `core`). Fornece a base usada por F03 e F04.

## 1. Introdução

- **Problema:** toda tabela de negócio precisa do mesmo mecanismo de isolamento; repetir a lógica em cada tabela gera divergência.
- **Solução:** uma função de tenant, funções de trigger reutilizáveis e um **padrão documentado** de RLS (por operação, com política de leitura para backup) que as demais features aplicam.
- **Metas:**
  1. Sem `app.clinic_id`, `core.current_clinic_id()` retorna `NULL` (negar por padrão).
  2. 100% das tabelas de negócio seguem o mesmo padrão (verificado por teste).
  3. Os triggers genéricos cobrem `updated_at` e colunas imutáveis sem SQL dinâmico.

## 2. User stories

### US-001: Função de tenant
**Descrição:** Como política RLS, preciso saber o tenant da transação.

**Critérios de aceite:**
- [ ] `core.current_clinic_id()` retorna o UUID de `app.clinic_id` ou `NULL` se ausente ou vazio.
- [ ] Valor que não seja UUID gera erro (nunca "todas as linhas").
- [ ] `EXECUTE` apenas para `crm_app`.

### US-002: Triggers reutilizáveis
**Descrição:** Como desenvolvedor de tabelas, quero triggers prontos de auditoria e imutabilidade.

**Critérios de aceite:**
- [ ] `core.set_updated_at()` atualiza `updated_at` em `BEFORE UPDATE`.
- [ ] `core.forbid_immutable_columns()` recebe a lista de colunas por argumento e falha se alguma mudar.

### US-003: Padrão de RLS documentado
**Descrição:** Como desenvolvedor das fases 1–4, quero uma receita única para criar tabelas isoladas.

**Critérios de aceite:**
- [ ] O contract lista o checklist obrigatório por tabela de negócio.
- [ ] Um teste de catálogo falha se existir tabela em `core` com `clinic_id` sem `ENABLE` + `FORCE` RLS ou sem política de leitura para `crm_backup`.

## 3. Requisitos funcionais

- **FR-1:** Criar `core.current_clinic_id()`, `STABLE`, `PARALLEL SAFE`, `search_path = pg_catalog`.
- **FR-2:** Criar `core.set_updated_at()` e `core.forbid_immutable_columns()` (plpgsql, sem SQL dinâmico).
- **FR-3:** Reservar `app.user_id` e `app.role` no contrato de sessão, sem usá-los em políticas na Fase 0.
- **FR-4:** Publicar o padrão de políticas (uma por operação; operações sem política são negadas).

## 4. Fora do escopo

- Criação de tabelas (F03).
- Políticas do `crm_definer` (F04).
- Políticas por `app.role` (Fase 1).

## 5. Considerações técnicas e riscos

- O pooler em modo *transaction* só mantém a variável durante a transação (por isso `set_config(..., true)` do backend).
- Funções que leem `current_setting` não devem ser marcadas `IMMUTABLE`.
- **Pergunta aberta:** nenhuma.
