# Contract — F06 Verificação da fase

## 1. Resumo
- **Feature:** F06-phase1-verification
- **Versão:** 1.0.0
- **Consumidores:** `qa-feature-legacy` (gate de qualidade), mantenedor do repositório. Não expõe endpoint; o contrato é o que a suíte garante.

## 2. Entradas
- Código das features F01–F05 compilando.
- Scripts do banco `database/bootstrap` e `database/migrations` (V001–V013) e Docker em execução.
- Contratos das features: `docs/features/backend/Fase-1/F01…F05/contract.md`.
- Opcional: `-Dperf.limit-ms=<ms>` e a variável `CI` para folga de desempenho.

## 3. Saídas
- Resultado `mvn verify` (verde/vermelho) com relatório de testes e cobertura.
- Relatório de rastreabilidade: cada meta e história do PRD global ligada ao teste que a prova.
- Lista de defeitos devolvidos às features donas, se houver.

### Matriz esperada (resumo)
| Rota | Sem token | R | D |
|---|---|---|---|
| `POST /patients`, `GET /patients`, `GET/PUT /patients/{id}` | 401 | 2xx | 2xx |
| `PATCH /patients/{id}/active`, `POST /patients/{id}/anonymize` | 401 | 403 | 2xx |
| `GET/PUT /patients/{id}/medical-history`, `POST/GET /patients/{id}/notes`, `PUT /patients/{id}/notes/{noteId}` | 401 | 403 | 2xx |
| `POST /patients/{id}/files` | 401 | 201 | 201 |
| `GET /patients/{id}/files`, `GET …/download-url`, `DELETE …/files/{fileId}` | 401 | 403 | 2xx |
| `GET /audit-log` | 401 | 403 | 200 |

## 4. Regras e limites
- A suíte não altera código de produção; defeito volta à feature dona.
- Limites de desempenho: busca e timeline p95 ≤ 100 ms; upload de 10 MB ≤ 5 s.
- Cobertura mínima de 80% nos services de `patient` e `record`.
- Fixtures de retenção só em banco descartável.
- Nenhum dado real; nenhum segredo no repositório.

## 5. Eventos emitidos
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] As 16 rotas × 3 perfis têm teste e status conforme a matriz.
- [ ] Isolamento entre clínicas provado nas 5 tabelas com o filtro desligado.
- [ ] Papel forjado, papel ausente e usuário ausente produzem os resultados do contrato da F01.
- [ ] Nenhum texto clínico em claro no banco; nenhum dado sensível em log ou em resposta de erro.
- [ ] `ddl-auto=validate` passa contra V001–V013.
- [ ] Metas de desempenho atendidas ou limite documentado.
- [ ] `mvn verify` verde em máquina limpa e relatório de rastreabilidade completo (US-P1-01 a US-P1-11).
