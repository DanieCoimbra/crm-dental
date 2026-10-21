# Contract — F05 Pipeline de Migrations e CI/CD

> **CI/CD adiado (2026-10-21):** os workflows de `.github/workflows/` foram removidos do repositório por decisão do projeto e voltam no fim. Onde este documento cita workflows, ambientes ou checks do GitHub, é o desenho previsto; no momento nada disso executa. Os scripts em `database/scripts` e `database/tests` seguem válidos e rodam localmente. Os workflows removidos estão no commit `c823a47`.

## 1. Resumo
- **Feature:** F05-migration-pipeline
- **Versão:** 1.0.0
- **Consumidores:** todas as features de banco; desenvolvedores; backend (CI do `ddl-auto=validate` usa os mesmos scripts).

## 2. Entradas

### 2.1 Arquivos
| Caminho | Regra |
|---|---|
| `database/migrations/V<NNN>__<descricao>.sql` | NNN com 3 dígitos crescentes; descrição em `snake_case`; imutável depois de aplicada |
| `database/rollback/U<NNN>__<descricao>.sql` | Nota ou SQL manual por migration |
| `database/tests/*.sql` | Asserções em `DO`; terminam com erro se algo falhar |
| `database/flyway.conf` | Sem credenciais |

### 2.2 Secrets e ambientes do GitHub
| Ambiente | Secrets | Workflows |
|---|---|---|
| `production` (aprovação obrigatória) | `SUPABASE_OWNER_URL`, `SUPABASE_OWNER_PASSWORD`, `SUPABASE_ADMIN_URL` e senhas do bootstrap | deploy, bootstrap |
| `maintenance` (**sem** revisor: job agendado não pode esperar aprovação) | `SUPABASE_APP_URL`, `SUPABASE_APP_PASSWORD` (papel `crm_app`: só `SELECT 1` e a função de limpeza) | manutenção |
| (nenhum) | — | `db-pr.yml` |

## 3. Saídas

| Workflow | Resultado |
|---|---|
| `db-pr.yml` | Check obrigatório `db-validate` (verde/vermelho) no PR |
| `db-deploy.yml` | Schema de produção na versão da `main`; `flyway info` no log |
| `db-maintenance.yml` | Projeto ativo; contagem de tokens apagados no log |

As URLs dos segredos são URIs **sem senha** (`postgresql://usuario@host:porta/banco?sslmode=require`); a senha vem do segredo `*_PASSWORD`. TLS é obrigatório.

Versão do schema: `core.flyway_schema_history` (visível só a `crm_owner`).

## 4. Regras e limites
- Branch `main` protegida: o check `db-validate` é obrigatório. Como é obrigatório, `db-pr.yml` roda em todo PR (sem filtro de caminhos) e o job termina verde sem rodar o harness quando o PR não toca `database/**`.
- O ambiente `production` limita as *deployment branches* a `main`; o `db-deploy.yml` só roda na `main` e só aceita o papel `crm_owner`.
- Migration aplicada nunca é editada; correção = nova versão.
- `cleanDisabled=true`; `outOfOrder=false`.
- A API (backend) nunca roda migrations.
- Mudanças destrutivas sempre em passos.
- Nenhum segredo em arquivos, logs ou saídas.
- Workflows com `permissions: contents: read`.

## 5. Eventos emitidos
Nenhum (apenas status dos checks).

## 6. Critérios de aceite (integração)
- [ ] PR com migration válida e todos os testes verdes fica verde.
- [ ] PR com teste falhando é reprovado e não pode ser mesclado.
- [ ] Teste 23: todas as migrations aplicam do zero.
- [ ] Teste 24: alterar uma migration aplicada faz o `validate` falhar.
- [ ] Deploy só inicia após aprovação manual do ambiente `production`.
- [ ] Dois deploys simultâneos são executados em série.
- [ ] A manutenção roda a cada 3 dias, sem depender de aprovação manual, e retorna a contagem de tokens apagados.
- [ ] Nenhum segredo aparece nos logs.
