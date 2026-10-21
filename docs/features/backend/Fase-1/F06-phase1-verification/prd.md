# PRD — F06 Verificação da fase

**Derivação:** PRD global da Fase 1, metas 1 a 6 da seção 1 e critérios de saída; spec global, seção 8. Fecha o teste 57 do banco (`ddl-auto=validate` contra V001–V013).
**Dependências externas:** F01 a F05 implementadas, o harness do banco (`database/bootstrap` e `database/migrations` via Testcontainers) e o Docker em execução.

## 1. Introdução

- **Problema:** cada feature tem testes próprios, mas as garantias da fase (papel, isolamento, cifra em repouso, higiene de log, desempenho) cruzam features e precisam de uma verificação única antes de declarar a Fase 1 pronta.
- **Solução:** uma suíte de verificação transversal, sem código de produção novo, que prova as metas do PRD global e serve de gate de qualidade.
- **Metas:**
  1. Matriz papel × endpoint cobre 100% das 16 rotas novas.
  2. Isolamento e papel forjado provados com o filtro Hibernate desligado, nas 5 tabelas.
  3. 0 texto clínico, CPF, chave de objeto ou URL assinada em banco ou log onde não deve estar.
  4. Busca de pacientes p95 ≤ 100 ms (10 mil pacientes, 4 concorrentes).
  5. `mvn verify` verde em máquina limpa; cobertura ≥ 80% nos services de `patient` e `record`.

## 2. User stories

### US-001: Matriz de papéis
**Descrição:** Como responsável pelo produto, quero uma prova de que cada rota respeita o papel.

**Critérios de aceite:**
- [ ] `@WebMvcTest` com `spring-security-test` cobre as 16 rotas × {sem token, R, D} com o status esperado do contrato.
- [ ] Rotas D-only chamadas por R retornam 403 sem executar nenhuma instrução no banco (contagem de statements = 0).
- [ ] O `POST /patients/{id}/files` aceita D e R; todas as outras rotas de exames exigem D.

### US-002: Isolamento e papel forjado no banco
**Descrição:** Como titular da clínica, quero que a RLS proteja os dados mesmo se o backend errar.

**Critérios de aceite:**
- [ ] Duas clínicas, filtro Hibernate desligado: consultas e SQL nativo da clínica A devolvem 0 linhas da B nas 5 tabelas.
- [ ] Com `app.role = RECEPTIONIST` forjado e filtro desligado: `medical_histories`, `clinical_notes`, `patient_files` e `audit_log` devolvem 0 linhas e as gravações clínicas falham; só o `INSERT` de exame e o CRUD de `patients` passam.
- [ ] `app.role` ausente ou inválido nega até `patients`; `app.user_id` ausente falha a gravação (`28000`).
- [ ] Pool de 1 conexão: nenhuma das três variáveis vaza entre requisições.

### US-003: Dados sensíveis fora de onde não devem estar
**Descrição:** Como responsável pelo produto, quero evidência de que o prontuário está cifrado e os logs estão limpos.

**Critérios de aceite:**
- [ ] `SELECT content` por SQL nativo (histórico e notas) casa com `^[A-Za-z0-9+/]+={0,2}$` e não contém o texto enviado.
- [ ] Captura de log durante os fluxos de CPF duplicado, falha de decifra, falha de Storage e download: nenhuma linha contém CPF, nome do paciente, conteúdo clínico, `storage_key`, nome de arquivo ou URL assinada.
- [ ] Respostas de erro (400–500) não contêm stack trace, SQL nem a mensagem do driver.

### US-004: Desempenho
**Descrição:** Como usuário, quero buscas e listagens rápidas.

**Critérios de aceite:**
- [ ] Busca por nome e por CPF com 10 mil pacientes: p95 ≤ 100 ms com 4 concorrentes (limite sobrescrevível por `-Dperf.limit-ms`, como o `LoginPerformanceIT`).
- [ ] Linha do tempo com 500 notas: p95 ≤ 100 ms.
- [ ] Upload de 10 MB com `LocalFileStorage` ≤ 5 s e sem `byte[]` do arquivo inteiro no heap.

### US-005: Saída da fase
**Descrição:** Como mantenedor, quero um critério objetivo de "Fase 1 pronta".

**Critérios de aceite:**
- [ ] `mvn verify` verde com Docker ligado, incluindo as 5 entidades com `ddl-auto=validate` contra V001–V013.
- [ ] Cobertura ≥ 80% nos services de `patient` e `record`.
- [ ] Testes da Fase 0 atualizados (instrução de sessão com três variáveis) e verdes.
- [ ] Histórias US-P1-01 a US-P1-11 do PRD global conferidas uma a uma contra os testes.

## 3. Requisitos funcionais

- **FR-1:** Testes novos não alteram código de produção; defeitos achados voltam à feature dona.
- **FR-2:** A fixture que recua a data de registros clínicos (para vencer a retenção) usa a conexão do dono em banco descartável, como o teste 47 do banco.
- **FR-3:** Os ITs usam os scripts reais do banco (`../database`), sem schema próprio.
- **FR-4:** O relatório final lista cada meta do PRD global com o teste que a prova.

## 4. Fora do escopo

- Testes de carga além dos limites acima; testes contra o Supabase real (dependem de ação do usuário).
- Testes de interface; testes do Storage real.
- Novas funcionalidades.

## 5. Considerações técnicas e riscos

- Os ITs exigem Docker Desktop; sem ele o `mvn verify` falha nos testes de integração.
- Medições de desempenho variam por máquina; o limite é configurável e o CI usa folga maior.
- O checklist manual do bucket do Supabase continua pendente do usuário e não é coberto aqui.
- **Pergunta aberta:** nenhuma.
