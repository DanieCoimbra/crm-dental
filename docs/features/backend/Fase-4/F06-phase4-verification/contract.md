# Contract — F06 Verificação da Fase 4

## 1. Resumo
- **Feature:** F06-phase4-verification
- **Versão:** 1.0.0
- **Consumidores:** QA da Fase 4, arquiteto de banco (fecha o teste 143 e o lock `cdoc:`), responsável pela operação. Não expõe endpoint.

## 2. Entradas
- Código das features F01 a F05 compilando e com seus testes verdes.
- Banco: harness local com V001–V035 e Supabase real (`prod`), este só com confirmação do usuário.
- Contratos `docs/features/backend/Fase-4/F01..F05/contract.md` (soberanos).

## 3. Saídas
- Suíte de testes listada em `spec.md` (todos verdes).
- `docs/features/backend/Fase-4/F06-phase4-verification/traceability.md`: cláusula → teste/evidência.
- `.../report.md`: resultado por teste, cobertura por pacote, desempenho medido, resultado do real, pendências.
- `.../smoke_phase4.py` (somente API).
- Atualização do relatório do banco (`docs/features/banco/Fase-4/F06-phase4-verification/report.md`): teste 143, lock `cdoc:` e `CR012`.

### Erros / bloqueios
| Situação | Resultado |
|---|---|
| Cláusula de F01 a F05 sem teste ou evidência | Fase 4 não é concluída |
| Qualquer teste vermelho ou meta de desempenho estourada | Fase 4 não é concluída; achado vai para a feature dona |
| Smoke do real falha | Registrar, limpar e corrigir antes de nova rodada |

## 4. Regras e limites
- Metas (p95, `PerfLimits` dobra com `CI`): dashboard 150 ms (4 concorrentes), emissão 100 ms, detalhe 50 ms, PDF 300 ms, lista 50 ms.
- Cobertura de linhas ≥ 80% em `document`, `dashboard` e nas partes novas de `auth`/`record`.
- O smoke usa clínica fictícia `teste-smoke-f3-p4-<timestamp>`, não imprime tokens ou segredos e grava dados no real: **só roda com confirmação do usuário**; a limpeza é do usuário, como `postgres`.
- Não altera código de produção nem contrato; achado vira correção na feature dona.

### Interfaces internas
```text
Testes:  Phase4SchemaValidateIT, Phase4MatrixIT, Phase4IsolationIT, Phase4RlsOnlyIT,
         Phase4ConcurrencyIT, Phase4IntegrityIT, Phase4AuditIT, Phase4HygieneTest,
         Phase4PerformanceIT, Phase4PoolIT, Phase4ArchitectureTest (F01)
Script:  smoke_phase4.py  (+ database/scripts/cleanup_smoke_phase3_phase4.sql)
```

## 5. Eventos emitidos (internos)
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] 100% das cláusulas de F01 a F05 em `traceability.md` com teste ou evidência.
- [ ] Suítes unitária/web e de integração verdes com V001–V035; `ddl-auto=validate` com `User` e `ClinicalDocument` (teste 143).
- [ ] Matriz papel × endpoint, isolamento, RLS sozinha, concorrência (20 repetições), integridade, atomicidade do PDF e auditoria (`INSERT`, `VOID`, `DOWNLOAD`, `ANONYMIZE`) verdes.
- [ ] Higiene de log validada por mutação; texto cifrado no banco.
- [ ] Metas de desempenho e IT de pool dentro dos limites.
- [ ] Smoke no Supabase real verde (inclui emissões simultâneas no pooler 6543 com números distintos) e dados limpos (0 clínicas `teste-smoke-%`).
- [ ] `report.md` registra resultados, cobertura e pendências; relatório do banco atualizado (teste 143, `cdoc:`, `CR012`).
