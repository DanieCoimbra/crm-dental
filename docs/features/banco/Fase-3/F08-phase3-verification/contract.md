# Contract — F08 Verificação da Fase 3

## 1. Resumo
- **Feature:** F08-phase3-verification (Fase 3)
- **Versão:** 1.0.0
- **Consumidores:** responsável pelo banco (portão de qualidade); QA (`qa-feature`); backend da Fase 3 (teste 120).

## 2. Entradas

| Item | Valor |
|---|---|
| Comando | `docker compose -f database/docker-compose.yml --profile test run --rm tests` com `POSTGRES_PASSWORD` exportada |
| Pré-condições | Docker ligado; bootstrap de teste cria `btree_gist` e `pg_trgm` (nenhuma extensão nova na fase) |
| Migrations | V001–V029 aplicadas do zero por `scripts/apply_migrations.sh` |
| Variáveis | `MIGRATIONS_EXPECTED=29` (exportada por `run_all.sh`); `PERF_FACTOR` opcional para os limites do teste 116 |

## 3. Saídas

- Código de saída 0 e relatório "todos os testes passaram" se, e somente se, os testes 86–121 (exceto o 120 enquanto o backend da Fase 3 não existir) e os das Fases 1 e 2 passarem.
- `EXPLAIN` impresso do teste 116.
- Dump de `crm_backup` restaurado com as mesmas contagens das 6 tabelas novas.
- Na falha do teste 109, a semente e a iteração.

### Erros
| Situação | Resposta |
|---|---|
| Teste falha | `psql` sai com ≠ 0; `run_all.sh` aborta e mostra o arquivo e o número do teste |
| Plano com `Seq Scan` ou limite estourado (116) | Reprova a fase |
| Invariante de dinheiro violada (109) | Reprova a fase |
| Contagem diferente após restauração (119) | Reprova a fase |

## 4. Regras e limites
- Cada critério de aceite de F01–F07 aparece em pelo menos um teste numerado (seção 6 de cada `contract.md` e a tabela da spec).
- Os testes só usam os papéis `crm_app`, `crm_definer` e `crm_backup` sob RLS; `postgres`/`crm_owner` apenas preparam dados no banco descartável.
- Metas de desempenho (teste 116, com 20 clínicas × 5 mil parcelas e 5 mil pagamentos, sem `Seq Scan`): parcelas vencidas ≤ 10 ms; recebimentos do mês ≤ 10 ms; faturamento do período ≤ 10 ms; orçamentos do paciente ≤ 5 ms; `INSERT` de pagamento ≤ 15 ms; orçamento de 20 itens ≤ 100 ms.
- Migration aplicada é imutável: correção vai em nova versão; o rollback `U022…U029` vale antes de haver dados (depois, restaurar do `pg_dump`).
- O teste 120 depende do backend da Fase 3; o teste 84 da Fase 2 passa a rodar.
- O CI/CD está adiado: tudo roda manualmente. A validação no Supabase real é checklist manual (spec global, seção 10).

## 5. Eventos emitidos
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] `run_all.sh` passa do zero sobre V001–V029.
- [ ] Testes 86–119, 121 verdes; teste 120 documentado como pendente do backend da Fase 3.
- [ ] `rollback_chain.sh` reverte `U029 → U022` e reaplica; a função restaurada se comporta como a da V021.
- [ ] Catálogo: toda tabela nova com `ENABLE` + `FORCE`, política por operação e `SELECT TO crm_backup`; funções novas com `search_path` fixo, sem `EXECUTE` público e nenhuma `SECURITY DEFINER` nova.
- [ ] `payments` imutável por `crm_app` e por `crm_owner`; `TRUNCATE` negado.
- [ ] `database/README.md` e `database/rollback/README.md` atualizados para a Fase 3.
