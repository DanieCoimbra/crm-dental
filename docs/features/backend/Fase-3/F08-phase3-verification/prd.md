# PRD — F08 Verificação da Fase 3

**Derivação:** PRD global da Fase 3, metas 1 a 9 da seção 1, US-P3-15 e FR-16/FR-17; riscos 1 a 12 da seção 10. Spec global, seções 6, 8 e 9. Fecha o teste 120 do banco (`ddl-auto=validate` da Fase 3) e o teste 84 da Fase 2.
**Dependências externas:** F01 a F07 implementadas; `Phase3ItSupport` (V001–V029), `Phase3ArchitectureTest`, `Phase3SchemaValidateIT` e `Phase3Routes` (F01); suítes das Fases 1 e 2. Esta feature **não** altera código de produção: só testes, evidências e a rastreabilidade.

## 1. Introdução

- **Problema:** dinheiro sem homologação (CI/CD adiado) exige provar, por testes, que o isolamento, o papel, as invariantes monetárias, a concorrência e a atomicidade do envio valem mesmo com bug no backend.
- **Solução:** uma suíte de verificação de ponta a ponta contra PostgreSQL real (Testcontainers), com defesa em profundidade (filtro Hibernate desligado, papel forjado), propriedades de dinheiro, concorrência, falha injetada no Storage, desempenho e higiene de log.
- **Metas (critérios de saída da fase):**
  1. `mvn verify` verde em máquina limpa (Docker ligado), incluindo as suítes das Fases 1 e 2.
  2. Matriz papel × endpoint 100% (401/403/2xx) para todas as rotas novas e alteradas.
  3. Invariantes de dinheiro (a)–(d) verdes em ≥ 200 sequências aleatórias; concorrência (pagamento, numeração, plano, revisão, item × envio) verde em 20 repetições.
  4. Atomicidade do envio provada com falha em `save`, `delete`, `INSERT` e commit.
  5. Cobertura ≥ 80% nas classes de serviço de `catalog`, `commercial` e `finance` e nas alteradas de `scheduling`, `patient` e `record`.
  6. Nenhum valor, nome, observação, chave de objeto ou URL assinada em log, `toString` ou erro (validado por mutação).
  7. Histórias US-P3-01 a US-P3-15 rastreadas até um teste.

## 2. User stories

### US-001: Isolamento e papel provados
**Descrição:** Como responsável pelo produto, quero garantia técnica de que uma clínica não vê o dinheiro de outra e de que a recepção não alcança o financeiro.

**Critérios de aceite:**
- [ ] Com duas clínicas e o filtro Hibernate desligado, nenhuma consulta das seis tabelas novas e dos relatórios devolve linha da outra, inclusive por SQL nativo.
- [ ] Com `app.role = RECEPTIONIST` forjado e filtro desligado: `installments` e `payments` ficam invisíveis; nenhuma escrita em `procedure_types`, `budgets`, `budget_items` e `budget_documents`; `app.role` ausente ou inválido nega tudo.
- [ ] A matriz da seção 3 do PRD global está coberta por teste web e por IT (`Phase3RoleMatrixWebTest`, `Phase3RoleMatrixIT`), usando `Phase3Routes`.

### US-002: Invariantes de dinheiro
**Descrição:** Como dentista, quero a certeza de que o caixa nunca fica inconsistente.

**Critérios de aceite:**
- [ ] Teste de propriedade pela API: (a) `totalAmount = Σ linhas − desconto`; (b) Σ parcelas não canceladas = `totalAmount` do orçamento aprovado; (c) nenhuma parcela com líquido > valor; (d) `PAID` ⇔ líquido ≥ valor — ao fim de ≥ 200 sequências de criar, editar, enviar, aprovar, planejar, pagar e estornar.
- [ ] Operação inválida retorna 4xx e não altera dados.
- [ ] `CR011` forçado no commit → 409 e nada persistido.

### US-003: Concorrência e atomicidade
**Descrição:** Como responsável pela operação, quero provar que as corridas terminam em estados válidos e que o envio é tudo-ou-nada.

**Critérios de aceite:**
- [ ] 2 pagamentos no último saldo: um 201 e um 409 (20 vezes); em parcelas diferentes, ambos 201.
- [ ] 2 `POST /budgets` na mesma clínica: números distintos e sequenciais (20 vezes); 2 planos simultâneos e 2 revisões simultâneas: um só vence ou ambos com números distintos, sem 500; item editado × envio: um vence e o outro recebe 409.
- [ ] Falha injetada em `save`, `delete`, `INSERT` de `budget_documents` e no commit: orçamento `DRAFT`, sem linha de documento, sem objeto órfão (ou evento de compensação).
- [ ] Com 3 envios lentos simulados, uma leitura de orçamento mantém p95 ≤ 200 ms.

### US-004: Desempenho, log limpo e plataforma
**Descrição:** Como responsável pelo produto, quero evidência de desempenho e de privacidade.

**Critérios de aceite:**
- [ ] Com 5 mil parcelas e 5 mil pagamentos na clínica (semeados por `JdbcTemplate` como dono), `receivables`, `overdue`, `cash-flow`, `revenue` e `GET /patients/{id}/budgets` p95 ≤ 100 ms (4 concorrentes) e planos sem `Seq Scan`; pagamento p95 ≤ 100 ms; criar orçamento de 20 itens p95 ≤ 250 ms; envio p95 ≤ 1,5 s (`PerfLimits` dobra com `CI`).
- [ ] Teste de higiene de log: nenhum valor monetário, nome, observação, chave de objeto ou URL em log, `toString` ou mensagem de erro, validado por mutação.
- [ ] `ddl-auto=validate` passa contra V001–V029 com todas as entidades (fecha o teste 120 e o 84).
- [ ] A suíte das Fases 1 e 2 reexecutada (os testes que inativam ou anonimizam paciente consideram o novo comportamento).

### US-005: Rastreabilidade
**Descrição:** Como auditor do projeto, quero ligar cada requisito a um teste.

**Critérios de aceite:**
- [ ] `traceability.md` desta pasta mapeia US-P3-01…15, FR-1…17 e metas 1…9 do PRD global a classes e métodos de teste, no padrão da Fase 2, **criado só quando a implementação terminar** (não faz parte do breakdown).
- [ ] Os 14 pontos da seção 9 da spec global estão marcados como resolvidos ou encaminhados (ponto 5 depende do Supabase real).
- [ ] Smoke manual no Supabase real (numeração e plano na porta 6543, envio de PDF e limpeza) registrado.

## 3. Requisitos funcionais

- **FR-1:** `Phase3RoleMatrixWebTest`, `Phase3RoleMatrixIT`, `Phase3ForgedRoleIT`, `Phase3TenantIsolationIT`.
- **FR-2:** `MoneyInvariantsIT` (propriedade), `Phase3ConcurrencyIT`, `BudgetSendAtomicityIT` (com `FaultyFileStorage`), `InstallmentCommitIT` (`CR011`).
- **FR-3:** `Phase3PerformanceIT` e `Phase3PoolIT` (3 envios lentos), com semente por SQL como dono.
- **FR-4:** `Phase3LogHygieneIT` (mutação) e `Phase3ArchitectureTest` completo.
- **FR-5:** `Phase3SchemaValidateIT` com todas as entidades; reexecução das suítes das Fases 1 e 2.
- **FR-6:** `AnonymizationCommercialIT` e `AuditCommercialIT` (cobrem a F07 ponta a ponta).

## 4. Fora do escopo

- Qualquer mudança em código de produção ou em `database/` (defeito achado vira correção na feature dona).
- Testes de interface (não há front) e teste de carga além das metas de p95.
- Teste automatizado contra o Supabase real (a validação é um smoke manual registrado).
- Reconciliação de objetos órfãos e teste de restauração de backup.

## 5. Considerações técnicas e riscos

- O ponto 5 da spec (lock consultivo e constraint triggers adiáveis atrás do pooler 6543) só se confirma contra o Supabase real; o IT local usa conexão direta.
- Concorrência exige conexões e transações reais separadas; evitar `@Transactional` de teste que as una.
- Testes de desempenho dependem do hardware: `PerfLimits` dobra o limite com `CI`; semeadura grande (5 mil + 5 mil) deve ser por SQL em lote.
- A higiene de log só vale se a mutação (inserir um valor em log) falhar o teste.
