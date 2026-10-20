# Spec — F02 Base de Tenant e RLS

Complementa `docs/global/banco/spec.md` (seções 2.5, 2.6, 2.7 e 3.3).

## 1. Visão técnica
- **Feature:** F02 Base de Tenant e RLS.
- **Stack:** PL/pgSQL, SQL, Flyway (`V002`).
- **Abordagem:** uma migration com a função de tenant e as funções de trigger genéricas; o padrão de RLS é documentação executável (testada por catálogo).

## 2. Objetos (migration `V002__core_tenant_function.sql`, como `crm_owner`)

```sql
CREATE FUNCTION core.current_clinic_id() RETURNS uuid
  LANGUAGE sql STABLE PARALLEL SAFE
  SET search_path = pg_catalog
AS $$ SELECT NULLIF(current_setting('app.clinic_id', true), '')::uuid $$;
REVOKE EXECUTE ON FUNCTION core.current_clinic_id() FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION core.current_clinic_id() TO crm_app;
```

| Função | Tipo | Comportamento |
|---|---|---|
| `core.set_updated_at()` | trigger `BEFORE UPDATE` | `NEW.updated_at := now()`; retorna `NEW` |
| `core.forbid_immutable_columns()` | trigger `BEFORE UPDATE`, argumentos = nomes de colunas | Compara `to_jsonb(OLD)->col` com `to_jsonb(NEW)->col`; se diferirem, `RAISE EXCEPTION` com SQLSTATE `23514` e o nome da coluna (sem valores); se o nome não existir na tabela, `42703` |

Ambas com `SET search_path = pg_catalog` (mais restritivo que o previsto: nada de `core` no caminho), sem SQL dinâmico, sem `SECURITY DEFINER`.

### Padrão de tabela de negócio (receita)
| # | Item |
|---|---|
| 1 | `clinic_id uuid NOT NULL REFERENCES core.clinics(id)`, indexado (e à frente nos índices compostos) |
| 2 | `ENABLE` e `FORCE ROW LEVEL SECURITY` |
| 3 | Políticas `_sel`, `_ins`, `_upd` (e `_del` só se a operação for permitida) com `clinic_id = core.current_clinic_id()` em `USING` / `WITH CHECK` |
| 4 | Política `_backup_sel` `FOR SELECT TO crm_backup USING (true)` |
| 5 | `GRANT` explícitos e mínimos a `crm_app`; `GRANT SELECT` a `crm_backup` |
| 6 | Colunas de auditoria (`created_at`, `updated_at`, `version`) e triggers da seção 2 |
| 7 | Colunas imutáveis protegidas por `core.forbid_immutable_columns` |

### Contrato de sessão
| Variável | Uso na Fase 0 |
|---|---|
| `app.clinic_id` | Obrigatória; lida por `core.current_clinic_id()` |
| `app.user_id`, `app.role` | Reservadas (Fase 1+); nenhuma política as lê hoje |

## 3. Componentes
`V002`, `rollback/U002`, `tests/tenant_basics.sql` (testes 4 e 5, função, privilégios, triggers e receita aplicada a uma tabela-sonda revertida), `tests/catalog_rls_pattern.sql` (verificador de catálogo com auto-teste) e a documentação da receita (neste spec e no contract).

## 4. Lógica
- Política avalia `clinic_id = core.current_clinic_id()`: com `NULL`, a comparação não é verdadeira e a linha é negada.
- O teste de catálogo consulta `pg_class`/`pg_attribute`/`pg_policy`: toda tabela de `core` com coluna `clinic_id` (ou `clinics` por `id`) deve ter `relrowsecurity` e `relforcerowsecurity` verdadeiros e uma política de `SELECT` para `crm_backup`.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| `app.clinic_id` não é UUID | Erro de conversão; transação aborta (falha fechada) |
| `app.clinic_id` vazio | Tratado como `NULL` |
| Variável definida fora de transação | Descartada ao fim do comando; tenant ausente |
| Trigger genérico com coluna inexistente | A função verifica que o nome existe em `to_jsonb(NEW)` e, se não existir, falha com `42703` (nunca silencioso) |
| Tabela nova sem RLS | Teste de catálogo falha o PR |

## 6. Segurança e desempenho
- Função de tenant `STABLE`: avaliada uma vez por consulta.
- Triggers genéricos: sobrecarga desprezível em tabelas pequenas; sem SQL dinâmico (sem injeção).
- A função de tenant não é `SECURITY DEFINER`.
