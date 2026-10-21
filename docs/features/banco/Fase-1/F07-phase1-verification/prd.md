# PRD — F07 Verificação, Backup e Operação da Fase 1

**Derivação:** PRD global da Fase 1, US-109, metas 1 a 6 e FR-12 a FR-14; spec global, seções 5, 9 e 10.
**Dependências externas:** F01 a F06 (migrations V007–V013), Fase 0 (`run_all.sh`, `rollback_chain.sh`, testes de backup, `database/README.md`). Entrega a evidência de que a Fase 1 pode ser aprovada.

## 1. Introdução

- **Problema:** cada domínio da fase é testado isoladamente, mas o isolamento, a cadeia de migrations, o backup e a documentação operacional só se provam no conjunto.
- **Solução:** ampliar o harness local da Fase 0 com os testes 29 a 58 da spec, incluir as 5 tabelas nos testes de catálogo, backup e rollback, e atualizar a documentação operacional.
- **Metas:**
  1. `run_all.sh` passa do zero com as migrations V001–V013 e todos os testes.
  2. Backup de `crm_backup` traz as 5 tabelas e restaura com as mesmas contagens.
  3. Cadeia de rollback `U013 → U007` reverte e reaplica.
  4. `database/README.md` e a spec da Fase 0 refletem o Storage e as variáveis de sessão.

## 2. User stories

### US-001: Testes da fase no harness
**Descrição:** Como responsável pelo banco, quero que um único comando prove o isolamento, os papéis, as constraints e a auditoria da fase.

**Critérios de aceite:**
- [ ] Novos arquivos: `session_functions.sql`, `patients_constraints.sql`, `clinical_roles.sql`, `clinical_constraints.sql`, `audit_log.sql`, `anonymization.sql`, `performance_search.sql`, cobrindo os testes 29 a 55 da spec global.
- [ ] Testes de catálogo ampliados (54): RLS `ENABLE` + `FORCE`, política por operação e `SELECT TO crm_backup` nas 5 tabelas.
- [ ] Testes de privilégios ampliados (17, 18, 52, 53): sem `DELETE`/DDL; funções com `search_path` fixo e `EXECUTE` só aos papéis da matriz.

### US-002: Desempenho comprovado
**Descrição:** Como responsável pelo banco, quero planos e tempos reais.

**Critérios de aceite:**
- [ ] Massa de teste: 10 mil pacientes em uma clínica e 100 mil no total, notas e exames por amostra.
- [ ] Planos de busca por nome, CPF, listagem, notas, exames e auditoria sem `Seq Scan`; busca por trecho ≤ 20 ms e por CPF ≤ 5 ms.

### US-003: Backup e restauração da fase
**Descrição:** Como responsável pelo banco, quero que as tabelas novas entrem no backup.

**Critérios de aceite:**
- [ ] Teste 58: `pg_dump --enable-row-security` como `crm_backup` traz as 5 tabelas das clínicas A e B; restaura com as mesmas contagens (`table_counts.sql` ampliado).
- [ ] Teste de falha: tabela nova sem política para `crm_backup` quebra o teste 54.

### US-004: Migrations e rollback
**Descrição:** Como responsável pelo banco, quero migrations aplicáveis do zero e reversíveis.

**Critérios de aceite:**
- [ ] `rollback/U007…U013` (um por migration; as tabelas só se revertem sem dados).
- [ ] as migrations aplicam do zero por `apply_migrations.sh`; `rollback_chain.sh` reverte e reaplica V013 → V007.
- [ ] `ddl-auto=validate` do backend passa contra o schema (teste 57).

### US-005: Documentação operacional
**Descrição:** Como operador, quero o passo a passo da Fase 1.

**Critérios de aceite:**
- [ ] `database/README.md`: `20_extensions.sql`, contrato das variáveis de sessão, bucket do Storage, anonimização, janela de 30 dias dos backups.
- [ ] `docs/global/banco/Fase-0/spec.md` e `prd.md`: B2 e seções 7.1–7.2 revisadas (Storage permitido) com nota de que a revisão vem da Fase 1.

### US-006: Checklist manual do bucket
**Descrição:** Como responsável pelo banco, quero conferir a configuração do bucket de exames antes de aprovar a fase, já que nenhum teste automatizado a cobre.

**Critérios de aceite:**
- [ ] `database/README.md` traz o checklist da spec (seção 2.5), com data e responsável de cada execução.
- [ ] Todos os itens do checklist marcados antes da aprovação da fase e refeitos a cada troca de projeto Supabase, de chave ou de configuração do bucket.
- [ ] Qualquer item reprovado bloqueia a aprovação da fase (ou vira risco aceito, registrado no README).

## 3. Requisitos funcionais

- **FR-1:** Os testes usam o harness existente (`psql -v ON_ERROR_STOP=1`, asserções em blocos `DO`, papéis reais, bancos descartáveis).
- **FR-2:** O teste de relógio da retenção insere os registros já com `updated_at`/`noted_at` antigos, como `crm_app`; não altera relógio nem desliga triggers.
- **FR-3:** `table_counts.sql`, `catalog_snapshot.sql` e o teste de dicionário de dados incluem as 5 tabelas.
- **FR-4:** Nenhum teste deixa dados em bancos que não sejam descartáveis.

## 4. Fora do escopo

- CI/CD (continua adiado; execução manual do `run_all.sh`).
- Testes do backend.
- Testes automatizados contra o Storage real (upload, URL assinada): são do backend. A configuração do bucket é conferida manualmente (US-006) e especificada na F04.
- Deploy no Supabase de produção (manual, fora desta feature).

## 5. Considerações técnicas e riscos

- O teste de desempenho com 100 mil linhas aumenta o tempo do harness; isolar em arquivo próprio.
- Sem homologação: o harness é a única validação antes de produção.
- `pg_trgm` precisa estar no banco descartável (script `20_extensions.sql` no harness).
- **Pergunta aberta:** nenhuma.
