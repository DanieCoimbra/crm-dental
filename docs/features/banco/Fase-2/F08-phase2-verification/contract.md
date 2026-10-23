# Contract — F08 Verificação da Fase 2

## 1. Resumo
- **Feature:** F08-phase2-verification (Fase 2)
- **Versão:** 1.0.0
- **Consumidores:** responsável pelo banco (portão de qualidade); QA (`qa-feature`); backend da Fase 2 (teste 84).

## 2. Entradas

| Item | Valor |
|---|---|
| Comando | `docker compose -f database/docker-compose.yml --profile test run --rm tests` com `POSTGRES_PASSWORD` exportada |
| Pré-condições | Docker ligado; bootstrap de teste cria `btree_gist` e `pg_trgm` |
| Migrations | V001–V021 aplicadas do zero por `scripts/apply_migrations.sh` |
| Variáveis | `MIGRATIONS_EXPECTED=21` (exportada por `run_all.sh`); `CI` e o limite de desempenho opcionais |

## 3. Saídas

- Código de saída 0 e relatório "todos os testes passaram" se, e somente se, os testes 59–85 (exceto o 84 enquanto o backend não existir) e os da Fase 1 passarem.
- `EXPLAIN` impresso do teste 81.
- Dump de `crm_backup` restaurado com as mesmas contagens das 4 tabelas novas.

### Erros
| Situação | Resposta |
|---|---|
| Teste falha | `psql` sai com ≠ 0; `run_all.sh` aborta e mostra o arquivo e o número do teste |
| Plano com `Seq Scan` ou limite estourado (teste 81) | Reprova a fase |
| Contagem diferente após restauração (teste 85) | Reprova a fase |

## 4. Regras e limites
- Cada critério de aceite de F01–F07 aparece em pelo menos um teste numerado; a tabela de rastreabilidade da spec é a referência.
- Os testes só usam os papéis `crm_app`, `crm_definer` e `crm_backup` sob RLS; `postgres`/`crm_owner` apenas preparam dados no banco descartável.
- Metas de desempenho: agenda da semana ≤ 10 ms, conflito ≤ 5 ms, com 20 mil agendamentos por clínica e 200 mil no total, sem `Seq Scan`.
- Migration aplicada é imutável: correção vai em nova versão; o rollback `U014…U021` vale antes de haver dados (depois, restaurar do `pg_dump`).
- O teste 84 (`ddl-auto=validate`) depende do backend da Fase 2.
- O CI/CD está adiado: tudo roda manualmente.

## 5. Eventos emitidos
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] `run_all.sh` passa do zero sobre V001–V021.
- [ ] Testes 59–83 e 85 verdes; teste 84 documentado como pendente do backend.
- [ ] `rollback_chain.sh` reverte `U021 → U014` e reaplica.
- [ ] Catálogo: toda tabela nova com `ENABLE` + `FORCE`, política por operação e `SELECT TO crm_backup`; funções novas com `search_path` fixo e sem `EXECUTE` público.
- [ ] `database/README.md` e `database/rollback/README.md` atualizados para a Fase 2.
