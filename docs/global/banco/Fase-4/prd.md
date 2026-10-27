# PRD Global (Banco) — Fase 4: Documentos e Dashboard

**Escopo:** modelo de dados, papéis, RLS, auditoria, anonimização, índices e migrations da **Fase 4**: documentos clínicos gerados (atestado, receita, encaminhamento) e a base de consulta do dashboard (KPIs e gráfico semanal), sobre as Fases 0 a 3.
**Fontes:** `docs/arq_inicial.md` (módulos Documentos e Dashboard, Fase 4), `docs/global/banco/Fase-{0,1,2,3}/{prd,spec}.md`, `docs/global/backend/Fase-{0,1,2,3}/`, `database/` (migrations V001–V030, bootstrap, rollback, testes 1–121) e `back-java/` (módulos `auth`, `clinic`, `patient`, `record`, `scheduling`, `catalog`, `commercial`, `finance`). O `AGENTS.md` da raiz está vazio.
**Função no pipeline:** `banco`. O backend não cria nem altera objetos de banco. **Não há docs de backend da Fase 4 ainda:** o contrato é a proposta do banco e deve ser conciliado quando o backend planejar a fase.

## Decisões fechadas

Herdam B1–B8 (Fase 0), P1–P5 (Fase 1), A1–A12 (Fase 2) e C1–C5 (Fase 3). Novas, confirmadas em 2026-10-27 na entrevista de descoberta (respostas do usuário: 1A, 2A, 3A, 4A, 5A, 6A, 7A, 8A + download, 9A, 10A, 11A, 12A, 13A):

| # | Decisão | Valor |
|---|---|---|
| D1 | Persistência | Cada documento emitido vira uma linha **imutável** em `core.clinical_documents`. O **PDF não é guardado**: o backend o gera sob demanda a partir da linha (sem Storage, sem `budget_documents`-like) |
| D2 | Conteúdo | **Campos estruturados mínimos por tipo** (colunas não sensíveis e anuláveis) + **texto livre cifrado** em AES-256-GCM pela aplicação (Base64 em `varchar`, mesmo `CHECK` das notas). O banco nunca vê o texto claro |
| D3 | Papéis | **Só `DENTIST_ADMIN`** emite, lê, baixa e anula. A recepção não vê documento nenhum (dado clínico). Reforçado no banco por RLS, como notas e prontuário |
| D4 | Profissional | `users` ganha `cro_number` e `cro_state` (opcionais, os dois ou nenhum). **A emissão exige os dois preenchidos no emissor**; o banco reforça. Sem snapshot de cabeçalho no documento: o PDF reimpresso usa os dados atuais da clínica e do dentista |
| D5 | Numeração e integridade | `number` sequencial **por clínica e por tipo**, atribuído pelo banco sob serialização (`max()+1` sob lock: um `ROLLBACK` devolve o número, então a série confirmada não tem buracos). `sha256` do conteúdo armazenado, calculado pelo backend, para detectar adulteração da linha |
| D6 | Correção | Documento **nunca é editado nem apagado**. "Cancelar" = anular (`voided_at`, `voided_by`, motivo curto), uma só vez, só `DENTIST_ADMIN`. Corrigir = emitir outro |
| D7 | LGPD | Na anonimização do paciente, o conteúdo cifrado, os campos estruturados clínicos e o motivo de anulação são **zerados**; a linha (número, tipo, datas, emissor) permanece sem dado pessoal. Não bloqueia a anonimização |
| D8 | Auditoria | `clinical_documents` entra no `audit_log` com `INSERT`, `VOID`, `ANONYMIZE` **e `DOWNLOAD`** (cada download ou impressão, pois ambos passam pelo PDF gerado pelo backend). Sem valores, só identificadores. Leitura em tela (lista e detalhe) não é auditada |
| D9 | Dashboard | **Sem view e sem tabela de resumo**: o backend consulta as tabelas existentes sobre índices; o banco só ganha os índices que o `EXPLAIN` com 100 mil linhas e RLS ativa exigir |
| D10 | Taxa de retorno | Entre os pacientes com atendimento `COMPLETED` no mês (fuso da clínica), a **% que já tinha outro `COMPLETED` nos 12 meses anteriores**. Cálculo no backend sobre índice parcial |
| D11 | Papéis no dashboard | Recepção vê os KPIs **não financeiros** (total de pacientes, consultas hoje, taxa de retorno, gráfico semanal). **Faturamento do mês restrito ao `DENTIST_ADMIN` pelo backend** (correção da spec, S16: a recepção lê `budgets` pela C2 da Fase 3; só `installments` e `payments` são vedados a ela no banco, então o corte do KPI é regra de produto, não fronteira de segurança do banco) |
| D12 | Atendimento real | **Sem** `started_at`/`finished_at` em `appointments`: KPIs usam o horário agendado e o status final (decisão S8 da Fase 2 mantida) |
| D13 | Pendências herdadas | **Entram como critério de verificação** (domínio de verificação da fase): conflito `23P01` e `CR004` no Supabase real, smoke da Fase 3 refeito depois da V030, e a V030 documentada na spec e no README do banco |

## Padrões adotados (sem pergunta; contestar se discordar)

- **Convenções** das Fases 0 a 3 (`snake_case`, plural, `uuid`, `varchar(n)` + `CHECK`, `timestamptz`, `updated_at` por trigger, `version bigint`, FKs compostas `(x_id, clinic_id)` `ON DELETE RESTRICT`, `created_by` = `app.user_id`, política `SELECT TO crm_backup USING (true)`, funções de sessão como `(SELECT core.current_app_role())`, `OR` de igualdades em vez de `IN`). Schema segue `core`; extensões novas: nenhuma.
- **Tipos** (`document_type`): `CERTIFICATE` (atestado), `PRESCRIPTION` (receita), `REFERRAL` (encaminhamento). Lista fixa por `CHECK`; sem modelos por clínica.
- **Campos estruturados por tipo** (anuláveis, `CHECK` amarra ao tipo):
  - `CERTIFICATE`: `rest_days smallint` (1 a 90; dias de afastamento).
  - `REFERRAL`: `target_specialty varchar(60)` (especialidade de destino).
  - `PRESCRIPTION`: nenhum; os medicamentos vão no texto cifrado. Receita de controle especial (talonário azul/amarelo) está fora do escopo.
- **Texto livre cifrado:** `content` (Base64 do AES-256-GCM, mesmo padrão e chave versionada das notas); tamanho máximo definido na spec. Não pesquisável nem indexável; a busca de documentos é por paciente, tipo e data (colunas claras).
- **Campos claros e não sensíveis:** `document_type`, `number`, `issued_on` (`date`, data civil no fuso da clínica, calculada pelo backend), `patient_id`, `appointment_id` (opcional), `created_by`, `created_at`.
- **Vínculo com a consulta:** `appointment_id` opcional, FK composta `(appointment_id, patient_id, clinic_id)` (usa o `UNIQUE (id, patient_id, clinic_id)` da V015), garantindo que consulta e documento sejam do mesmo paciente.
- **Guardas de paciente** (mesmo padrão de `budgets`): paciente anonimizado não recebe documento novo (`CR003`); inativo também não (`CR005`). Documento já emitido permanece legível e baixável.
- **Emissor sem CRO:** nova falha **`CR012`** (nome provisório, a conciliar com o backend) quando `created_by` não tem `cro_number` e `cro_state`. Códigos `CR001`–`CR011` já existem.
- **Anulação:** `UPDATE` só nas colunas `voided_at`, `voided_by`, `void_reason` (até 200 caracteres), uma única vez (de `NULL` para valor); trigger de guarda proíbe qualquer outra mudança, e anular de novo falha. O motivo é texto livre (aviso no contrato) e é zerado na anonimização.
- **Download auditado:** como `DOWNLOAD` não é DML, o registro vem de uma função `SECURITY DEFINER` mínima (`record_document_download(document_id)`, só `DENTIST_ADMIN`, só para a clínica da sessão, só para documento existente), chamada pelo backend na mesma transação que gera o PDF. Documento anulado pode ser baixado e impresso, com marca visível de anulado no PDF (regra do backend).
- **Dashboard (consultas, sem objeto novo no banco além de índices):**
  - *Total de pacientes:* contagem de `patients` ativos da clínica.
  - *Consultas hoje:* `appointments` com `starts_at` no dia no fuso da clínica, excluídas `CANCELED` e `NO_SHOW`; usa `ix_appt_clinic_start`.
  - *Faturamento do mês:* definição da Fase 3 (soma de orçamentos `APPROVED` por `decided_at`, competência); só `DENTIST_ADMIN`.
  - *Taxa de retorno:* D10; índice parcial `(clinic_id, starts_at, patient_id) WHERE status = 'COMPLETED'` se o plano exigir.
  - *Gráfico semanal:* contagem de agendamentos por dia da semana corrente, por status, no fuso da clínica.
- **Sem seed:** nenhum documento nem modelo pré-cadastrado.
- **Divisão sugerida para o breakdown** (a confirmar): F01 CRO do profissional (`users`) · F02 `clinical_documents` · F03 auditoria de documentos e `DOWNLOAD` · F04 anonimização · F05 índices do dashboard · F06 verificação da fase.

---

## 1. Resumo executivo

**Problema:** o dentista solo ainda emite atestado, receita e encaminhamento fora do sistema, sem registro, numeração ou rastreio de quem emitiu e baixou; e a tela inicial não mostra o estado do consultório (pacientes, consultas do dia, retorno, faturamento) sem abrir cada módulo.

**Solução:** uma tabela imutável e cifrada de documentos emitidos, sob RLS por clínica e por papel, com número, hash, anulação e trilha de auditoria (inclusive download), anonimizável conforme a LGPD; e os índices necessários para o backend calcular os KPIs do dashboard em milissegundos sem objetos novos de resumo.

**Metas e métricas:**
1. 100% das tabelas novas com `ENABLE` e `FORCE ROW LEVEL SECURITY`, políticas por operação e teste de isolamento entre duas clínicas (0 linhas visíveis de outra clínica e sem `app.clinic_id`).
2. Recepção com 0 linhas visíveis em `clinical_documents` e 0 execuções permitidas de `record_document_download`.
3. Emissão de documento (`INSERT` com número atribuído pelo banco) com p95 ≤ 20 ms com 100 mil documentos na tabela.
4. Cada KPI do dashboard com p95 ≤ 50 ms no banco com 100 mil agendamentos, 100 mil parcelas e 100 mil pagamentos, com plano via índice (sem `Seq Scan` nas tabelas grandes) e RLS ativa.
5. Anonimização de um paciente com documentos: 0 colunas com dado pessoal ou clínico remanescentes em `clinical_documents` e linha de auditoria `ANONYMIZE` gravada.
6. Harness completo (`tests/run_all.sh`) verde, 0 falhas, `MIGRATIONS_EXPECTED` atualizado.

## 2. User stories

### US-401: CRO do profissional
**Description:** As a dentista, I want registrar meu CRO e UF para que atestados e receitas saiam identificados.

**Acceptance Criteria:**
- [ ] `users.cro_number varchar(15)` e `users.cro_state char(2)`-equivalente (`varchar(2)`, maiúsculas, UF válida por `CHECK`), ambos anuláveis, com `CHECK` "os dois preenchidos ou os dois nulos"
- [ ] Só usuário com `role = 'DENTIST_ADMIN'` pode ter os campos preenchidos (`CHECK`)
- [ ] **Só o próprio usuário** altera o seu CRO (política de `UPDATE` com `id = app.user_id`) por `UPDATE` de colunas, sem ampliar o `GRANT` das demais
- [ ] Migration aditiva (colunas nulas, sem reescrever a tabela) e rollback `U031`
- [ ] Teste: UF inválida, só um campo preenchido e recepcionista com CRO falham

### US-402: Emitir documento
**Description:** As a dentista, I want emitir atestado, receita ou encaminhamento de um paciente para entregá-lo e manter o registro.

**Acceptance Criteria:**
- [ ] `core.clinical_documents` com os campos de D1–D6 e `CHECK`s por tipo (`rest_days` só em `CERTIFICATE`, `target_specialty` só em `REFERRAL`; `CERTIFICATE` exige `rest_days`, `REFERRAL` exige `target_specialty`)
- [ ] `number` por clínica e tipo atribuído pelo banco sob serialização, `UNIQUE (clinic_id, document_type, number)`; o backend não o envia
- [ ] `content` com `CHECK` de Base64 e tamanho mínimo/máximo; `sha256` `varchar(64)` hex minúsculo
- [ ] `INSERT` só por `DENTIST_ADMIN` com `created_by = app.user_id`; emissor sem CRO falha com `CR012`; paciente anonimizado `CR003`, inativo `CR005`
- [ ] `appointment_id` de outro paciente ou de outra clínica é rejeitado pela FK composta
- [ ] Sem política de `DELETE` e sem `UPDATE` de conteúdo (imutável); `TRUNCATE` bloqueado para `crm_app`
- [ ] Teste de propriedade: numeração sem repetição com duas conexões concorrentes na mesma clínica

### US-403: Consultar documentos
**Description:** As a dentista, I want listar os documentos de um paciente por data para reabrir ou reimprimir.

**Acceptance Criteria:**
- [ ] Índice `(clinic_id, patient_id, created_at DESC, id DESC)` serve a lista paginada por keyset e a FK composta
- [ ] Recepção: 0 linhas em `SELECT` (RLS por papel)
- [ ] Plano de `EXPLAIN` com RLS mostra uso do índice com 100 mil documentos

### US-404: Anular documento
**Description:** As a dentista, I want anular um documento emitido por engano sem apagar o histórico.

**Acceptance Criteria:**
- [ ] `UPDATE` só de `voided_at`, `voided_by` (FK composta para `users`) e `void_reason` (≤ 200 caracteres), de `NULL` para valor, uma vez
- [ ] Qualquer outra coluna alterada, ou nova anulação, falha por trigger de guarda
- [ ] Recepção não consegue anular (0 linhas)
- [ ] Auditoria grava `VOID`

### US-405: Auditar emissão, anulação e download
**Description:** As a dentista, I want saber quem emitiu, anulou e baixou cada documento para prestar contas.

**Acceptance Criteria:**
- [ ] `audit_log.table_name` aceita `clinical_documents` e `audit_log.action` aceita `VOID` e `DOWNLOAD` (constraints recriadas em migration aditiva)
- [ ] Trigger grava `INSERT` e `VOID`; sem valores, só `clinic_id`, `user_id`, `record_id`, `occurred_at`
- [ ] `record_document_download(document_id uuid)`: `SECURITY DEFINER` com `search_path` fixo, exige `DENTIST_ADMIN` e documento da clínica da sessão, grava `DOWNLOAD`; `EXECUTE` só para `crm_app`
- [ ] Chamada por recepção ou com documento de outra clínica falha sem gravar nada
- [ ] `audit_log` continua sem `UPDATE`/`DELETE` para `crm_app`

### US-406: Anonimização com documentos
**Description:** As a titular de dados, I want ter meus dados removidos dos documentos quando for anonimizado, para cumprir a LGPD.

**Acceptance Criteria:**
- [ ] `anonymize_patient` (recriada em nova migration, sobre a versão da V029) zera `content`, `rest_days`, `target_specialty` e `void_reason` dos documentos do paciente e marca `anonymized_at`
- [ ] `content` e os campos estruturados passam a aceitar nulo **apenas** quando `anonymized_at IS NOT NULL` (`CHECK`)
- [ ] A linha permanece (número, tipo, datas, emissor); não há bloqueio por documento
- [ ] Auditoria grava `ANONYMIZE`; a exceção de imutabilidade só vale para `crm_definer` dentro da função (mesma proteção da V029, sem confiar só no GUC)
- [ ] Teste: após anonimizar, nenhuma coluna com dado pessoal ou clínico sobra; função anterior restaurável pelo rollback

### US-407: Base de consulta do dashboard
**Description:** As a dentista ou recepcionista, I want ver os KPIs e o gráfico semanal rapidamente para decidir o dia.

**Acceptance Criteria:**
- [ ] Cada KPI de D9–D11 tem sua consulta de referência e o índice que a atende documentados na spec, com `EXPLAIN` registrado
- [ ] Índices novos (se necessários) criados sem lock longo: tabelas pequenas na migration transacional; se o volume pedir, `CREATE INDEX CONCURRENTLY` manual documentado
- [ ] Recepção: leitura de `patients` e `appointments` continua permitida; `installments`/`payments` seguem vedados e `budgets` segue legível (C2; teste de matriz de papéis)
- [ ] Medição com 100 mil agendamentos, 100 mil parcelas e 100 mil pagamentos: p95 ≤ 50 ms por KPI, RLS ativa
- [ ] Nenhuma view, view materializada ou tabela de resumo criada

### US-408: Menor privilégio, backup e isolamento
**Description:** As a responsável pela plataforma, I want que a fase respeite o isolamento e os backups existentes.

**Acceptance Criteria:**
- [ ] `crm_app` sem `DROP`/`TRUNCATE`/`ALTER`, sem `BYPASSRLS`, sem `GRANT ALL`; `crm_backup` com política de `SELECT` em `clinical_documents`
- [ ] Teste de isolamento entre duas clínicas e sem `app.clinic_id` (0 linhas)
- [ ] `catalog_rls_pattern.sql` verde para a tabela nova
- [ ] `backup.sh` e `restore_check.sh` cobrem a tabela nova; o `restore_check` valida a carga com os triggers de imutabilidade
- [ ] Rollback `U031`+ testado em cadeia (reversões e função de anonimização restaurada)

### US-409: Verificação da fase e pendências herdadas
**Description:** As a arquiteto, I want fechar a fase com evidência, inclusive as pendências que vieram das Fases 2 e 3.

**Acceptance Criteria:**
- [ ] `MIGRATIONS_EXPECTED` e o harness atualizados; suíte completa verde, 0 falhas (testes novos a partir do 122)
- [ ] Aplicação das novas migrations no Supabase real como `crm_owner`, com conferência no SQL Editor
- [ ] Smoke da Fase 3 refeito depois da V030 (backend `prod`, `ddl-auto=validate`), sem regressão
- [ ] Conflito de horário `23P01` e `CR004` exercitados no Supabase real
- [ ] V030 documentada na spec (§4.2 e §8) e no `database/README.md`; spec 4.6 da Fase 2 (pré-checagem de conflito) decidida ou formalmente adiada
- [ ] Teste de `ddl-auto=validate` da Fase 4 depende do backend; registrado como dependência, não como falha

## 3. Requisitos funcionais

- **FR-1:** O banco deve ter a tabela `core.clinical_documents` com `clinic_id`, RLS `ENABLE` + `FORCE`, políticas por operação com `USING`/`WITH CHECK` e política `SELECT` para `crm_backup`.
- **FR-2:** Só `DENTIST_ADMIN` deve conseguir `SELECT`, `INSERT` e anular (`UPDATE` restrito) em `clinical_documents`; recepção vê 0 linhas.
- **FR-3:** O banco deve atribuir `number` por clínica e tipo sob serialização, sem repetição sob concorrência.
- **FR-4:** O `INSERT` deve recusar emissor sem CRO (`CR012`), paciente anonimizado (`CR003`) e inativo (`CR005`).
- **FR-5:** O conteúdo livre deve ser aceito apenas como Base64 de AES-256-GCM, com tamanho limitado; o banco não recebe texto claro.
- **FR-6:** Os campos estruturados devem ser coerentes com o tipo do documento (`CHECK`).
- **FR-7:** O documento deve ser imutável; a única mudança permitida é a anulação, uma vez, e a anonimização feita por `crm_definer`.
- **FR-8:** `users` deve aceitar `cro_number` e `cro_state` opcionais, os dois ou nenhum, só para `DENTIST_ADMIN`.
- **FR-9:** `audit_log` deve registrar `INSERT`, `VOID`, `ANONYMIZE` e `DOWNLOAD` de `clinical_documents`, sem valores.
- **FR-10:** `record_document_download` deve gravar `DOWNLOAD` apenas para `DENTIST_ADMIN` e documento da clínica da sessão.
- **FR-11:** `anonymize_patient` deve zerar o conteúdo, os campos estruturados e o motivo de anulação dos documentos do paciente, sem bloquear a anonimização.
- **FR-12:** O banco deve oferecer os índices que atendam aos KPIs do dashboard (D9–D11) com plano via índice e RLS ativa, sem views nem tabelas de resumo.
- **FR-13:** As migrations devem ser versionadas a partir da `V031`, pequenas, aditivas e transacionais, cada uma com rollback manual (`U031`+) e teste.
- **FR-14:** Nenhum segredo, chave AES ou texto clínico em claro deve aparecer em log do PostgreSQL, função, comentário de migration ou dado de seed.

## 4. Non-goals (fora de escopo)

- **Geração** do PDF (é do backend) e seu armazenamento: o PDF não é guardado (D1); sem Storage, sem assinatura digital, sem envio por e-mail ou WhatsApp.
- Modelos (templates) de documento por clínica, editor de modelos e biblioteca de medicamentos.
- Receita de controle especial, integração com sistemas de prescrição eletrônica, validação de CID e de medicamentos.
- Snapshot do cabeçalho (clínica e dentista) no documento (D4): reimpressão usa dados atuais.
- Edição de documento emitido, `DELETE` e versionamento.
- Auditoria de leitura em tela (lista e detalhe); só o download é auditado (D8).
- Múltiplos dentistas com CRO próprio por agenda (continua dentista solo).
- Views, views materializadas, tabelas de resumo, `security_invoker`, particionamento e réplicas (D9).
- `started_at`/`finished_at` e métricas de duração real de atendimento (D12).
- KPIs além dos quatro do `arq_inicial.md` (pacientes, consultas hoje, taxa de retorno, faturamento do mês) e o gráfico semanal; fluxo de caixa com saídas e metas.
- Numeração fiscal ou legal do documento.
- Auditoria de valores antigo/novo.

## 5. Requisitos de IA

Não se aplica.

## 6. Especificações técnicas e considerações de design

**Arquitetura (dados):**
```
React ─▶ API (JWT: clinic_id, role, user_id) ─▶ transação: set_config(app.clinic_id, app.role, app.user_id, local)
                                                    │
 core.users (+ cro_number, cro_state) ◀─ FK composta ─ core.clinical_documents ─▶ core.patients
                                                          │  (appointment_id opcional ─▶ core.appointments)
                                                          │  imutável · content AES (Base64) · número por clínica/tipo
                                                          ├─ trigger ─▶ core.audit_log (INSERT, VOID)
                                                          ├─ record_document_download() ─▶ audit_log (DOWNLOAD)
                                                          └─ anonymize_patient() ─▶ zera payload, ANONYMIZE
 Dashboard: backend ─▶ patients · appointments · budgets (índices; sem view)
```

**Pontos de integração:**
- Backend (novo módulo, a planejar): cifra e decifra `content` (chave versionada `CRM_AES_KEYS`), calcula `sha256`, gera o PDF sob demanda, chama `record_document_download` na mesma transação, calcula "hoje" e os limites do mês/semana no fuso da clínica, traduz `CR012` e demais erros em 4xx; entidades JPA com `ddl-auto=validate` (colunas preenchidas pelo banco, como `number`, mapeadas como não inseríveis e não atualizáveis; reler a linha).
- Módulo `auth`/`clinic`: perfil do dentista passa a ler e gravar `cro_number` e `cro_state` (hoje não mapeados).
- Módulo `patient`: `anonymize_patient` passa a tratar documentos; não há objetos no Storage a apagar para documentos.
- Módulos `scheduling`, `commercial` e `finance`: fornecem os dados dos KPIs; sem mudança de schema além de índices.
- Operação: `scripts/apply_migrations.sh` (como `crm_owner`), `tests/run_all.sh`, backup e restauração já existentes; operação do Supabase real segue manual.

**UI/UX:** não se aplica (banco). Textos livres do documento e o motivo de anulação exigem aviso no contrato do backend e do front (dado de saúde; o motivo não é cifrado).

**Segurança e privacidade:**
- Documento clínico é dado de saúde: cifrado na aplicação (D2), sob RLS por clínica e por papel (D3), com download auditado (D8) e anonimizável (D7).
- O `sha256` é do conteúdo **armazenado** (cifrado), não do texto claro, para não permitir confirmar conteúdo por dicionário; a regra exata fica na spec.
- `record_document_download` é a única função nova `SECURITY DEFINER`: `search_path` fixo, `EXECUTE` revogado de `PUBLIC`, validação de papel e tenant dentro da função.
- Menor privilégio: sem `GRANT ALL`, sem `BYPASSRLS`; `crm_definer` altera `clinical_documents` apenas na anonimização, com a mesma guarda `current_user = 'crm_definer'` adotada na V029 (o GUC `core.anonymizing` sozinho é forjável).
- Limite aceito (risco 1 da Fase 1): o banco confia no `app.role` informado pelo backend; papel errado no JWT abre os documentos.
- `motivo` da anulação é texto livre em claro (≤ 200 caracteres): aviso no contrato e zerado na anonimização.
- Chaves AES fora do banco e do repositório; perder `CRM_AES_KEYS` = perder o conteúdo dos documentos (risco já aceito para o prontuário).

## 7. Riscos e perguntas abertas

**Riscos técnicos**
1. **Reimpressão muda o cabeçalho.** Sem snapshot (D4), alterar nome da clínica, endereço ou CRO muda o PDF de documentos antigos, o que enfraquece o valor probatório. Mitigação: o `sha256` cobre o conteúdo emitido, não o cabeçalho; reavaliar snapshot na v1.1 se o produto precisar de prova.
2. **Numeração por serialização.** Lock consultivo por clínica e tipo (padrão da V024); atrás do pooler transaction (6543) só se confirma com o backend e no Supabase real. Buracos esperados.
3. **Imutabilidade × anonimização × restauração.** Os triggers que proíbem `UPDATE`/`DELETE`/`TRUNCATE` não podem barrar a carga do `pg_restore`, nem a função de anonimização; testar `restore_check` e `U03x` (padrão da `payments`, risco 5 da Fase 3).
4. **`audit_log` com nova ação e tabela.** Recriar `ck_audit_table` e `ck_audit_action` toma lock curto; a tabela é pequena. Conferir se a V030 e as funções de auditoria existentes continuam válidas após o `ALTER`.
5. **Função `SECURITY DEFINER` de download.** Superfície nova de escalada; validar papel e tenant dentro dela, `search_path` fixo e teste de recepção, clínica errada e documento inexistente.
6. **Download chamado sem PDF entregue.** O backend pode registrar `DOWNLOAD` e falhar ao gerar o PDF (ou o contrário). Mitigação: mesma transação; a ordem exata fica no contrato do backend.
7. **Plano dos KPIs sob RLS.** Lição das Fases 2 e 3: predicados com `IN` e `tstzrange &&` perdem índice sob RLS; usar `OR`/`<>` e conferir o `EXPLAIN` com RLS ativa. A taxa de retorno (janela de 12 meses por paciente) é a consulta mais cara.
8. **Fuso de datas.** "Hoje", "mês" e "semana" dependem do fuso da clínica (`clinics.timezone`, V014); o backend calcula os limites, o banco não decide.
9. **Dinheiro e saúde sem homologação** (CI/CD adiado, operação manual do Supabase): migrations pequenas e reversíveis; ensaio no harness antes de aplicar.
10. **Backend da Fase 4 não existe:** nomes, erros (`CR012`) e a divisão "banco impõe × backend decide" podem mudar; conciliar antes de implementar.
11. **Pendências de segurança herdadas** (senha do `postgres` vazada, bucket sem limite/MIME, `AES` fora do repositório): não bloqueiam a fase, mas continuam abertas fora do banco.

**Perguntas abertas** — *respondidas em 2026-10-27; mantidas como registro:*
- **Impressão:** o backend entrega o PDF que o navegador imprime. Como toda impressão e todo download passam pelo endpoint do PDF do backend, **os dois são auditados como `DOWNLOAD`**. Imprimir a tela de detalhe (sem o PDF) não é auditável.
- **Documento anulado** pode ser baixado/impresso, **com marca visível de anulado** no PDF (regra do backend; o banco expõe `voided_at`).
- **Texto livre:** limite de 4.000 caracteres claros antes de cifrar (teto do `varchar` calculado na spec). Aprovado.
- **Estruturados:** `rest_days` de 1 a 90 e `target_specialty` até 60 caracteres. Aprovado.
- **CRO:** quem edita é **o próprio dentista**, no próprio perfil (sem edição de CRO de outro usuário).
- **Código `CR012`** para "emissor sem CRO": aprovado.
- **Gráfico semanal:** semana civil, **segunda a domingo**, calculada pelo backend (fuso da clínica); o banco só fornece o índice.

**Lançamento em etapas**
- **MVP (Fase 4):** US-401 a US-409.
- **v1.1:** snapshot de cabeçalho no documento, modelos de documento por clínica, auditoria de leitura em tela, `started_at`/`finished_at` se a D12 for revista.
- **v2.0:** assinatura digital (ICP-Brasil), receita de controle especial, múltiplos profissionais com CRO por agenda, views de relatório com `security_invoker = true` se o volume justificar.
