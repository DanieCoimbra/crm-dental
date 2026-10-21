# Contract — F07 Verificação, Backup e Operação da Fase 1

## 1. Resumo
- **Feature:** F07-phase1-verification
- **Versão:** 1.0.0
- **Consumidores:** responsável pelo banco (gate de aprovação da fase), `qa-feature-legacy`, backend (teste 57).

## 2. Entradas
- PostgreSQL descartável com administrador (`PGHOST`, `PGPORT`, `PGUSER`, `PGPASSWORD`, `PGDATABASE`), `psql`, `openssl`, `age` (imagem de `database/tests/Dockerfile`).
- `database/migrations/V001…V013`, `database/bootstrap/{00,10,20}_*.sql`.

## 3. Saídas
- Código de saída `0` de `bash database/tests/run_all.sh` e uma linha por etapa/teste.
- Sumário de `table_counts.sql` (5 tabelas novas incluídas).
- Planos `EXPLAIN` e tempos de `performance_search.sql`.
- `database/README.md` e docs da Fase 0 atualizados.

### Erros
Qualquer asserção falha com `RAISE EXCEPTION` e nome do teste; o `run_all.sh` interrompe (`set -euo pipefail`).

## 4. Regras e limites
- Executar apenas em banco descartável; nunca contra o Supabase de produção.
- Testes 29 a 58 da spec global são o conjunto mínimo; novos casos podem ser acrescentados, nenhum removido.
- Cobertura obrigatória por tabela nova: isolamento, sem tenant, papel, constraints, grants, política de backup e auditoria.
- Migration aplicada não é editada; correção = nova versão.
- Aprovação da fase exige: `run_all.sh` verde, plano sem `Seq Scan` nas consultas críticas, as pendências da seção 10 da spec global registradas como validadas ou como risco aceito e o checklist do bucket (itens de configuração) concluído.
- A privacidade do bucket só é verificada manualmente; nenhum teste automatizado a revalida enquanto o CI/CD estiver adiado.

## 5. Eventos emitidos
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] `run_all.sh` verde do zero, incluindo `20_extensions.sql` e V001–V013.
- [ ] Testes 29 a 55 cobertos pelos arquivos da seção 2 da spec.
- [ ] Teste 56: migrations aplicam do zero e cadeia `U013 → U007` com reaplicação.
- [ ] Teste 57: `ddl-auto=validate` do backend passa contra o schema.
- [ ] Teste 58: dump/restauração com as mesmas contagens nas 5 tabelas.
- [ ] Busca por trecho ≤ 20 ms e por CPF ≤ 5 ms com 10 mil pacientes na clínica e 100 mil no total.
- [ ] `database/README.md` e `docs/global/banco/Fase-0/{prd,spec}.md` atualizados (Storage, variáveis de sessão, bucket, anonimização).
- [ ] Checklist manual do bucket (spec, seção 2.5, itens B1 a B12) executado e registrado em `database/README.md`; B1 a B6 e B11 concluídos antes da aprovação da fase, B7, B8, B10 e B12 marcados "pendente do backend" até o backend da Fase 1 existir.
