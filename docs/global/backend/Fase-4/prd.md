# PRD Global (Backend) — Fase 4: Documentos e Dashboard

**Status:** aprovado (entrevista de descoberta respondida em 2026-10-27: 1A–11A, todas as respostas padrão). Spec global aprovada em 2026-10-27; breakdown em `docs/features/backend/Fase-4/`.

> **Nota de divergências (2026-10-27): onde este PRD e a `spec.md` divergem, vale a spec** (seção 10 dela).
> 1. O CRO é gravado por `PUT /me/professional-registry` (só dentista; recepção recebe 403), não por `PUT /me` (I8, US-P4-01, FR-1). `GET /me` continua trazendo `cro` e `croState`.
> 2. `CR012` (emissor sem CRO) devolve **409 `conflict`**, não "409 `business-rule`" (`business-rule` é 422).
> 3. `23514` residual de `ck_cdoc_*` vira 500 (defeito do backend); a validação prévia devolve 400, 422 ou 409.
> 4. O módulo `commercial` **não muda**: `BudgetLookup.approvedTotal/approvedCount` já existem.
> 5. O rodapé do PDF usa nome e CRO atuais do dentista da clínica, via `ProfessionalIdentity.of(created_by)`, sem snapshot.
**Escopo:** API REST do módulo novo `document` (atestado, receita e encaminhamento), do módulo novo `dashboard` (KPIs e gráfico semanal) e os ajustes em `auth`, `record`, `scheduling`, `patient`, `commercial` e `shared` que a fase exige, sobre as Fases 0 a 3.
**Fontes:** `docs/arq_inicial.md` (módulos Documentos e Dashboard, Fase 4), `docs/global/backend/Fase-{0,1,2,3}/{prd,spec}.md`, `docs/global/banco/Fase-4/{prd,spec}.md` e `docs/features/banco/Fase-4/F01..F06` (contrato do banco), código atual de `back-java/` e `database/` (V001–V035; **a V031–V035 já está aplicada no Supabase real desde 2026-10-27**). O `AGENTS.md` da raiz continua **vazio**: as premissas vêm do `arq_inicial.md` e dos PRDs anteriores.
**Função no pipeline:** `backend`. O backend **não** cria nem altera objetos de banco; consome o contrato da função `banco`. Este PRD é a conciliação prevista no PRD do banco da Fase 4 (risco 10): o código `CR012` e a divisão "banco impõe × backend decide" ficam fechados aqui.

## Decisões fechadas

Herdam D1–D8 (Fase 0), E1–E6 (Fase 1), G1–G4 (Fase 2) e H1–H4 (Fase 3): monólito modular, Spring Boot 4.1.1 / Java 21, pacote `crm_data`, JWT 15 min + refresh rotativo, filtro Hibernate **+** RLS, papel e tenant só do JWT. Herdam também as decisões do banco D1–D13 e S1–S16 (Fase 4). Novas, confirmadas em 2026-10-27:

| # | Decisão | Valor |
|---|---|---|
| I1 | Receita | `content` é **texto livre** de até 4.000 caracteres, cifrado inteiro (sem lista estruturada de medicamentos) |
| I2 | Texto dos PDFs | O PDF compõe a **frase padrão** do tipo (atestado com dias de afastamento; encaminhamento com a especialidade) e o texto livre cifrado entra como complemento. Complemento **opcional** no atestado; **obrigatório** na receita e no encaminhamento |
| I3 | Entrega do PDF | `GET /clinical-documents/{id}/pdf` devolve o **binário** (`application/pdf`, `Content-Disposition: inline`, `Cache-Control: no-store`); o navegador abre e imprime. **Sem Storage** e sem URL assinada (D1 do banco). Cada chamada registra um `DOWNLOAD` na mesma transação |
| I4 | Data de emissão | `issuedOn` opcional; o padrão é **hoje no fuso da clínica**; aceita data passada (≥ 2000-01-01) e **recusa data futura** com 422 |
| I5 | Dashboard | **Um** endpoint `GET /api/v1/dashboard` com KPIs e gráfico semanal; parâmetro opcional `weekOf=YYYY-MM-DD` (qualquer data da semana desejada); `Cache-Control: no-store` |
| I6 | Papéis no dashboard | A recepção recebe a mesma resposta **sem o campo `monthlyRevenue`** (ausente). O corte é do **backend** (a recepção lê `budgets` no banco, S16 do banco) |
| I7 | Faturamento do mês | Só **competência** (orçamentos `APPROVED` por `decided_at`), igual a `/finance/revenue`; o caixa continua em `/finance/cash-flow` |
| I8 | CRO | **Opcional** no cadastro; `GET /me` e `PUT /me` passam a trazer `cro` e `croState` (só o dentista os preenche). Emitir sem CRO retorna **409** (`CR012`) com mensagem pedindo o cadastro |
| I9 | Limite de uso | **30 emissões/min** e **30 PDFs/min** por usuário (429 com `Retry-After`), no padrão do `DocumentRateLimiter` |
| I10 | Rotas | `/patients/{id}/clinical-documents` (criar e listar), `/clinical-documents/{id}` (detalhe), `/clinical-documents/{id}/void` e `/clinical-documents/{id}/pdf` |
| I11 | Conteúdo do PDF | Cabeçalho com **nome, endereço e telefone da clínica**; título com número ("Atestado nº 12"); **nome do paciente, sem CPF e sem data de nascimento**; data de emissão; rodapé com o nome do dentista e "CRO nnnnn/UF". Documento anulado sai com a marca diagonal **"ANULADO"**. Documento de paciente anonimizado: **409**, sem PDF |

## Padrões adotados (sem pergunta; contestar se discordar)

- **Dois módulos novos, sem ciclo.** `document` depende de `patient` (`PatientLookup`), `clinic` (`ClinicIdentity`, `ClinicZones`, `UserNames`), `auth` (nova interface pública `ProfessionalIdentity`) e `scheduling` (`AppointmentLookup`, vínculo opcional); `dashboard` depende de `patient`, `scheduling`, `commercial` e `clinic`, **só por interfaces públicas de leitura** (nunca pelo repositório de outro módulo). Nenhum módulo depende de `dashboard` ou de `document`. `patient` **não** depende de `document`: a anonimização zera os documentos dentro do banco (V034) e o backend não tem nada a apagar no Storage.
- **Criptografia explícita, não por conversor.** Como o `sha256` cobre o conteúdo **cifrado** (S4 do banco), o service cifra com `AesGcmCipher` **antes** de calcular o hash e grava o texto cifrado numa coluna mapeada como `String` simples (sem `@Convert`); a leitura decifra no service. O `EncryptedStringConverter` não serve aqui. Formato já conhecido: `base64(versão[1] | IV[12] | cifrado + tag[16])`; 4.000 caracteres claros (≤ 16.000 bytes UTF-8, validados por `@MaxUtf8Bytes`) viram ≤ 21.372 caracteres, abaixo do `varchar(22000)`.
- **Hash de integridade (S4 do banco):** `sha256` hex minúsculo de `v1|document_type|patient_id|issued_on|rest_days|target_specialty|content` (campos nulos = vazio; `content` = Base64 cifrado). O banco só confere o formato; o **backend recalcula ao ler** (detalhe e PDF). Divergência: 500 com `type` próprio (`integrity`) e log só com o `id` do documento, sem conteúdo.
- **Colunas do banco nunca vêm do cliente:** `number`, `voidedAt`, `voidedBy`, `anonymizedAt`, `sha256`, `createdBy`, `createdAt`, `version` na criação. Após o `INSERT` o backend **relê a linha** (`number`) e devolve o recurso. `number`, `voided*` e `anonymized_at` ficam `insertable=false`; `UPDATE` **só** de `voided_at`, `voided_by`, `void_reason`, `version` (`@DynamicUpdate`, demais colunas `updatable=false`), senão o grant por coluna dá `42501`.
- **Anular:** `POST /clinical-documents/{id}/void` com `{version, reason?}` (motivo ≤ 200 caracteres, texto livre **em claro**: aviso no contrato). Uma vez só; anular de novo ou um documento anonimizado retorna 409. Anulado continua legível e baixável (PDF com a marca).
- **Download auditado = função do banco.** `GET .../pdf` abre a transação, lê o documento (recepção: 403 antes do banco), chama `SELECT core.record_document_download(:id)`, gera o PDF **em memória** e só então responde; se a gravação falha, não entrega. Impressão pelo navegador do PDF conta como download; imprimir a tela de detalhe não é auditável.
- **Códigos do banco → HTTP:** `CR012` → **409** `business-rule` (`reason: PROFESSIONAL_REGISTRY_MISSING`); `CR003`/`CR005` já existem (409); `23514` de `ck_cdoc_*` na emissão → 422 (dado); `23514` do trigger de imutabilidade (anular de novo, campo imutável) → 409; `23503` → 422/404; `42501` num `INSERT`/`UPDATE` é bug do backend (o 403 sai antes); `28000` → 500; `P0002` (download de documento inexistente ou de outra clínica) → 404. Nenhum `DETAIL` do driver é logado nem devolvido.
- **Lista sem conteúdo.** `GET /patients/{id}/clinical-documents` devolve um resumo (id, tipo, número, `issuedOn`, `restDays`, `targetSpecialty`, `voidedAt`, `anonymized`, `createdAt`, nome do emissor), paginado (`page`, `size` ≤ 50, ordenado por `createdAt DESC, id DESC`, filtro opcional `type`). O texto decifrado só vai no **detalhe**.
- **Documento de paciente anonimizado.** O detalhe responde 200 com `anonymized: true` e `content`, `restDays`, `targetSpecialty` e `voidReason` nulos (número, tipo e datas permanecem); o PDF responde 409. Paciente inativo: lista, detalhe e PDF seguem permitidos; **emitir** retorna 409 (`CR005`).
- **Dashboard em uma transação somente leitura**, uma conexão, consultas sequenciais (K1 a K5 do banco). Janelas calculadas pelo backend no fuso de `clinics.timezone`: hoje `[00:00, 00:00 do dia seguinte)`, mês corrente `[dia 1, dia 1 do mês seguinte)`, 12 meses anteriores para o retorno e semana **segunda a domingo** `[segunda 00:00, próxima segunda 00:00)`. O gráfico devolve sempre os 7 dias, com zeros nos dias vazios e todos os 6 status por dia.
- **Taxa de retorno (D10 do banco):** entre os pacientes com atendimento `COMPLETED` no mês corrente, a % que já tinha outro `COMPLETED` nos 12 meses anteriores ao início do mês. Devolvida como `attended`, `returning` e `percent` (texto decimal com 1 casa, ou `null` se `attended = 0`; o cálculo da divisão é do backend).
- **Dinheiro e porcentagem na API como texto decimal**, como nas Fases 3 (`"1234.50"`, `"62.5"`).
- **Convenções das Fases 0 a 3 mantidas:** Lombok sem `@Data`/`@ToString`/`@EqualsAndHashCode`; `ProblemDetail` (RFC 9457); `@PreAuthorize` nos services como segunda barreira; DTOs sem `clinicId`, `createdBy`, `role` ou `id` vindos do cliente; `@Version Long` com 409 em conflito; UUID gerado pela aplicação e `persist` sem `merge` com `SELECT` prévio; `READ COMMITTED`; logs só com identificadores (nunca conteúdo, motivo da anulação, nome do paciente, nome do dentista ou CRO).

---

## 1. Resumo executivo

**Problema:** o dentista solo ainda emite atestado, receita e encaminhamento fora do sistema, sem número, sem registro de quem emitiu e baixou, e a tela inicial não mostra o estado do consultório sem abrir cada módulo.

**Solução:** o módulo `document` expõe a emissão, a consulta, a anulação e o PDF sob demanda dos três documentos, cifrando o texto no servidor, calculando o hash de integridade e chamando as funções do banco que numeram, auditam e anonimizam; o módulo `dashboard` entrega, numa única chamada, os quatro KPIs e o gráfico semanal, com o faturamento visível só ao dentista.

**Metas e métricas (backend, Fase 4):**
1. 100% dos endpoints novos têm teste da matriz papel × endpoint (401/403/2xx); `RECEPTIONIST` recebe 403 em **todas** as rotas de documento e nenhuma resposta do dashboard da recepção contém o campo `monthlyRevenue` (teste de contrato).
2. **Cifra e integridade:** o texto nunca aparece em claro no banco (verificado por consulta direta como administrador do teste), no log, em exceção ou no `toString`; trocar um byte do `content` ou do `sha256` no banco faz o detalhe e o PDF falharem com 500 `integrity` (nunca devolvem texto adulterado).
3. **Numeração e concorrência:** 2 `POST` simultâneos do mesmo tipo na mesma clínica recebem números distintos e consecutivos, em 20 repetições, sem nenhum 500; tipos e clínicas diferentes nunca se esperam. Anular × anular ao mesmo tempo: um 200 e um 409, em 20 repetições.
4. **Auditoria completa:** cada emissão, anulação e PDF gera exatamente uma linha (`INSERT`, `VOID`, `DOWNLOAD`) e a anonimização gera `ANONYMIZE` por documento, todas legíveis em `GET /audit-log?tableName=clinical_documents`; com falha injetada na geração do PDF depois do `record_document_download`, **nenhum** `DOWNLOAD` fica gravado (rollback da transação).
5. **Dashboard correto:** com massa semeada (agendamentos de todos os status, pacientes ativos e inativos, orçamentos aprovados em meses diferentes), K1 a K5 batem com contagens independentes feitas no teste; semana e mês corretos na virada de mês/ano e em fuso diferente de UTC.
6. Com o filtro Hibernate desligado e `app.role = RECEPTIONIST` forjado, a RLS sozinha esconde `clinical_documents` e impede qualquer escrita; nenhuma linha de outra clínica aparece (defesa em profundidade provada em Testcontainers).
7. **Desempenho:** com 100 mil documentos e 100 mil agendamentos no banco, `GET /dashboard` p95 ≤ 150 ms com 4 requisições concorrentes em ambiente local; emissão p95 ≤ 100 ms; PDF (geração + registro do download) p95 ≤ 300 ms (`PerfLimits` dobra o limite com `CI`).
8. `ddl-auto=validate` passa contra V001–V035 (fecha o teste 143 do banco) e `mvn verify` continua verde em máquina limpa; a suíte das Fases 1 a 3 segue verde.
9. Nenhum conteúdo de documento, motivo de anulação, nome de paciente, nome de dentista, CRO ou conteúdo de PDF em log, `toString`, metadado do PDF ou mensagem de erro (teste de higiene de log, validado por mutação como nas fases anteriores).
10. Cobertura ≥ 80% nas classes de serviço de `document` e `dashboard` e nas classes de serviço alteradas de `auth`, `record`, `scheduling`, `patient` e `commercial`.

---

## 2. Módulos e mudanças nos módulos existentes

| Módulo | Responsabilidade / mudança |
|---|---|
| `document` (novo) | Emissão, lista, detalhe, anulação e PDF dos três documentos. Cifra/decifra o texto, calcula e confere o `sha256`, chama `record_document_download`, gera o PDF com OpenPDF (`DocumentPdfRenderer`, regra pura como o do orçamento), aplica os limites de uso. Sem interface pública de escrita; expõe só `DocumentLookup` se algum módulo precisar (hoje nenhum) |
| `dashboard` (novo) | `GET /dashboard`: K1 a K5, janelas no fuso da clínica, corte do faturamento por papel. Só leitura; sem entidades próprias |
| `auth` (alterado) | `User` mapeia `cro_number` e `cro_state`; `GET /me` e `PUT /me` trazem `cro` e `croState` (só dentista; recepção enviando CRO recebe 422); nova interface pública `ProfessionalIdentity` (nome do dentista, `cro`, `croState`, para o PDF e para a checagem prévia de `CR012`) |
| `record` (alterado) | `AuditAction` ganha `VOID` e `DOWNLOAD`; `AuditQueryService` aceita `clinical_documents` em `tableName` |
| `scheduling` (alterado) | Nova interface pública de leitura (`AppointmentStats`): contagem de hoje por status, contagem por dia/status numa janela, pacientes com atendimento concluído no mês e retorno. Sem mudança de entidade |
| `patient` (alterado) | Nova interface pública de leitura (`PatientStats`): total de pacientes ativos. `PatientLookup` já cobre "pode receber documento" (existe, ativo, não anonimizado) |
| `commercial` (alterado) | Nova consulta de leitura só-soma do faturamento por competência em `[ini, fim)` (reaproveita a definição de `/finance/revenue`, sem trazer a lista de orçamentos), exposta por `BudgetLookup` |
| `clinic` (alterado) | `ClinicIdentity` e `ClinicZones` já bastam; sem mudança de contrato |
| `shared` (alterado) | Tradução de `CR012` e de `23514` dos documentos no `GlobalExceptionHandler`; `PdfResponses` (cabeçalhos do PDF); limitador de uso de documentos; `Phase4ArchitectureTest` cobre os módulos novos |

**Direção de dependência (sem ciclo).** `document → {patient, clinic, auth, scheduling}`; `dashboard → {patient, scheduling, commercial, clinic}`; `auth`, `clinic`, `patient`, `scheduling` e `commercial` **não** dependem de `document` nem de `dashboard`. A checagem arquitetural (ArchUnit ou teste equivalente, como na Fase 3) cobre isso.

---

## 3. Matriz papel × endpoint

| Endpoint | `DENTIST_ADMIN` | `RECEPTIONIST` |
|---|---|---|
| `POST /api/v1/patients/{id}/clinical-documents` | sim | **403** |
| `GET /api/v1/patients/{id}/clinical-documents` | sim | **403** |
| `GET /api/v1/clinical-documents/{id}` | sim | **403** |
| `POST /api/v1/clinical-documents/{id}/void` | sim | **403** |
| `GET /api/v1/clinical-documents/{id}/pdf` | sim | **403** |
| `GET /api/v1/dashboard` | sim (com `monthlyRevenue`) | sim (**sem** `monthlyRevenue`) |
| `GET /api/v1/me`, `PUT /api/v1/me` | sim (com `cro`, `croState`) | sim (sem CRO; enviar CRO → 422) |
| `GET /api/v1/audit-log?tableName=clinical_documents` | sim | **403** (já é só do dentista) |

O backend nega com 403 **antes** do banco (`SecurityConfig` por rota e `@PreAuthorize` nos services); a RLS é a segunda barreira (recepção: 0 linhas e `42501` na escrita).

---

## 4. User stories

### US-P4-01: CRO no perfil do dentista
**Descrição:** Como dentista, quero informar meu CRO e UF no perfil para que meus documentos saiam identificados.

**Critérios de aceite:**
- [ ] `GET /me` e `PUT /me` incluem `cro` (1 a 15 dígitos) e `croState` (UF válida em maiúsculas), os dois juntos ou nenhum; ausentes = `null`.
- [ ] Recepção enviando `cro` ou `croState` retorna 422 `validation`; `PUT /me` do dentista sem esses campos preserva o CRO existente (omitir ≠ apagar; apagar = enviar ambos `null` explicitamente).
- [ ] Formato inválido retorna 400 com a lista de campos; `23514` residual do banco retorna 422.
- [ ] Atualização concorrente do perfil mantém o bloqueio otimista (`version`).

### US-P4-02: Emitir documento
**Descrição:** Como dentista, quero emitir atestado, receita ou encaminhamento de um paciente.

**Critérios de aceite:**
- [ ] `POST /patients/{id}/clinical-documents` com `documentType` (`CERTIFICATE`, `PRESCRIPTION`, `REFERRAL`), `issuedOn?`, `restDays?` (1 a 90, **obrigatório e só** em `CERTIFICATE`), `targetSpecialty?` (2 a 60, **obrigatório e só** em `REFERRAL`), `content` (texto, ≤ 4.000 caracteres e ≤ 16.000 bytes UTF-8; obrigatório e não vazio em `PRESCRIPTION` e `REFERRAL`; opcional em `CERTIFICATE`), `appointmentId?`.
- [ ] Retorna 201 com o documento (número atribuído pelo banco, `sha256` não exposto, `content` decifrado).
- [ ] `issuedOn` futuro retorna 422; omitido = hoje no fuso da clínica.
- [ ] Emissor sem CRO: 409 (`CR012`); paciente anonimizado ou inativo: 409 (`CR003`/`CR005`); paciente de outra clínica: 404; `appointmentId` de outro paciente: 422.
- [ ] O banco guarda o texto cifrado e o hash; campos do banco vindos do cliente são ignorados.

### US-P4-03: Consultar documentos
**Descrição:** Como dentista, quero listar os documentos de um paciente e abrir um deles.

**Critérios de aceite:**
- [ ] Lista paginada (`size` ≤ 50), mais novos primeiro, filtro por `type`, sem o texto; detalhe com o texto decifrado.
- [ ] Documento de outra clínica: 404; do paciente anonimizado: 200 com `anonymized: true` e textos nulos.
- [ ] Detalhe e PDF conferem o `sha256`; divergência retorna 500 `integrity` sem texto.

### US-P4-04: Baixar e imprimir o PDF
**Descrição:** Como dentista, quero abrir o PDF do documento para imprimir ou entregar ao paciente.

**Critérios de aceite:**
- [ ] `GET /clinical-documents/{id}/pdf` devolve `application/pdf` inline, `Cache-Control: no-store`, nome sugerido sem dado pessoal (`atestado-12.pdf`); metadados do PDF sem nome de paciente.
- [ ] Chama `record_document_download` na mesma transação; cada chamada gera uma linha `DOWNLOAD`.
- [ ] Conteúdo conforme I11; documento anulado leva a marca "ANULADO"; paciente anonimizado: 409; recepção: 403; limite de 30/min por usuário (429).

### US-P4-05: Anular documento
**Descrição:** Como dentista, quero anular um documento emitido por engano sem apagar o histórico.

**Critérios de aceite:**
- [ ] `POST /clinical-documents/{id}/void` com `{version, reason?}` retorna 200 com o documento anulado; `voidedBy` é o usuário do token.
- [ ] Anular de novo, documento anonimizado ou `version` velha retornam 409.
- [ ] O documento anulado continua listável, legível e baixável.

### US-P4-06: Trilha de auditoria dos documentos
**Descrição:** Como dentista, quero ver quem emitiu, anulou e baixou cada documento.

**Critérios de aceite:**
- [ ] `GET /audit-log?tableName=clinical_documents[&recordId=]` devolve `INSERT`, `VOID`, `DOWNLOAD` e `ANONYMIZE` com o nome do usuário, sem valores.
- [ ] Todas as ações novas são aceitas pelo enum e pelo filtro; ação desconhecida não derruba a listagem.

### US-P4-07: Anonimização com documentos
**Descrição:** Como titular, quero que meus documentos sejam esvaziados quando eu for anonimizado.

**Critérios de aceite:**
- [ ] A rota existente `POST /patients/{id}/anonymize` não ganha erro novo; depois dela, o detalhe do documento mostra `anonymized: true` e textos nulos, e o PDF retorna 409.
- [ ] Nada a apagar no Storage para documentos; a contagem de objetos do Storage devolvida pela rota não muda.

### US-P4-08: KPIs do dashboard
**Descrição:** Como dentista ou recepcionista, quero ver o estado do consultório numa tela.

**Critérios de aceite:**
- [ ] `GET /dashboard` devolve `totalPatients` (ativos), `appointmentsToday` (total sem cancelados e faltas, mais por status), `returnRate` (`attended`, `returning`, `percent`) e, só para o dentista, `monthlyRevenue` (`month`, `totalAmount`, `approvedCount`).
- [ ] Hoje, mês e janela dos 12 meses seguem o fuso da clínica; faturamento por competência (I7).
- [ ] `percent` é `null` com `attended = 0`; resposta sempre com `Cache-Control: no-store`.

### US-P4-09: Gráfico semanal
**Descrição:** Como dentista ou recepcionista, quero ver os agendamentos da semana por dia.

**Critérios de aceite:**
- [ ] A resposta traz `weekly` com `weekStart` (segunda), `weekEnd` (domingo) e 7 dias, cada um com `total` e os 6 status (zeros incluídos).
- [ ] `weekOf` opcional (qualquer data da semana); data fora de [2000-01-01, hoje + 366 dias] retorna 422.
- [ ] Os agendamentos entram no dia **local** da clínica (um agendamento às 23:30 locais não vai para o dia seguinte em UTC).

### US-P4-10: Isolamento, papel e plataforma provados
**Descrição:** Como arquiteto, quero provas automatizadas de que a fase respeita o isolamento e o menor privilégio.

**Critérios de aceite:**
- [ ] Matriz papel × endpoint, isolamento entre duas clínicas, RLS sozinha (filtro desligado e papel forjado), concorrência, integridade, higiene de log e desempenho, conforme as metas 1 a 9.
- [ ] `ddl-auto=validate` contra V001–V035, `mvn verify` verde e smoke contra o Supabase real (emissão, PDF, anulação, anonimização e dashboard) sem regressão.

---

## 5. Máquinas de estado (validadas no servidor)

**Documento clínico** (estados derivados das colunas; sem coluna `status`):

| Estado | Condição | Transições |
|---|---|---|
| `ACTIVE` | `voided_at` nulo e `anonymized_at` nulo | → `VOIDED` (anular, dentista) · → `ANONYMIZED` (anonimização do paciente) |
| `VOIDED` | `voided_at` preenchido | → `ANONYMIZED` (anonimização do paciente) |
| `ANONYMIZED` | `anonymized_at` preenchido | terminal |

Anular só de `ACTIVE`; PDF e leitura em `ACTIVE` e `VOIDED`; leitura (sem texto) em `ANONYMIZED`; PDF em `ANONYMIZED` retorna 409. Não existe edição nem exclusão: corrigir = emitir outro.

---

## 6. Requisitos funcionais

- **FR-1:** O sistema deve permitir ao dentista informar `cro` e `croState` em `PUT /me` e devolvê-los em `GET /me`; a recepção nunca os recebe nem os envia.
- **FR-2:** O sistema deve emitir atestado, receita e encaminhamento apenas para o dentista, com os campos obrigatórios e proibidos por tipo validados na borda antes do banco.
- **FR-3:** O sistema deve cifrar o texto livre no servidor (AES-256-GCM, chave versionada), calcular o `sha256` sobre o conteúdo cifrado e gravar ambos; nenhum texto claro vai ao banco, ao log ou a mensagem de erro.
- **FR-4:** O sistema deve ler a linha após o `INSERT` para devolver o `number` atribuído pelo banco e nunca aceitar `number`, `voided*`, `anonymizedAt`, `sha256` ou `createdBy` do cliente.
- **FR-5:** O sistema deve recalcular o `sha256` a cada leitura de detalhe e de PDF e recusar com 500 `integrity` se divergir.
- **FR-6:** O sistema deve listar os documentos do paciente paginados e sem texto, e devolver o texto decifrado só no detalhe.
- **FR-7:** O sistema deve anular um documento uma única vez, em nome do usuário do token, com `version` otimista e motivo opcional de até 200 caracteres.
- **FR-8:** O sistema deve gerar o PDF sob demanda em memória, chamar `core.record_document_download` na mesma transação antes de responder e não entregar o PDF se o registro falhar.
- **FR-9:** O sistema deve compor o PDF conforme I11 (frase padrão do tipo, complemento livre, cabeçalho da clínica, rodapé com dentista e CRO, marca "ANULADO", sem CPF nem data de nascimento) e recusar o PDF de paciente anonimizado.
- **FR-10:** O sistema deve limitar a 30 emissões/min e 30 PDFs/min por usuário, respondendo 429 com `Retry-After`.
- **FR-11:** O sistema deve devolver em `GET /dashboard` os KPIs K1 a K5 do contrato do banco, com janelas calculadas no fuso da clínica e semana de segunda a domingo, sempre com `Cache-Control: no-store`.
- **FR-12:** O sistema deve omitir `monthlyRevenue` na resposta da recepção e nunca consultar o faturamento para esse papel.
- **FR-13:** O sistema deve traduzir `CR012`, `CR003`, `CR005`, `23514`, `23503`, `P0002` e `28000` conforme a seção "Códigos do banco → HTTP", sem expor `DETAIL` do driver.
- **FR-14:** O sistema deve registrar `VOID` e `DOWNLOAD` no enum de auditoria e aceitar `clinical_documents` no filtro de `GET /audit-log`.
- **FR-15:** O mapeamento JPA deve passar em `ddl-auto=validate` contra V001–V035 (`rest_days` como `smallint`, `content` e `sha256` como `varchar`, `number` como `integer`).

---

## 7. Non-goals (fora de escopo)

- Guardar o PDF no Storage, URL assinada para documentos, assinatura digital, envio por e-mail ou WhatsApp.
- Modelos (templates) por clínica, editor de modelos, biblioteca de medicamentos, lista estruturada de medicamentos, receita de controle especial, CID.
- Editar ou apagar documento emitido; versionamento; snapshot do cabeçalho no documento (reimpressão usa os dados atuais da clínica e do dentista).
- CPF e data de nascimento no PDF; numeração fiscal ou legal.
- Auditoria de leitura em tela (lista e detalhe); só o PDF é auditado.
- No dashboard: entradas de caixa, fluxo de caixa com saídas, KPIs além dos quatro e do gráfico semanal, metas, cache no servidor, `started_at`/`finished_at` e métricas de duração real.
- Front-end (outra função), múltiplos dentistas com CRO por agenda, anonimização de CRO.

---

## 8. Requisitos de IA

Não se aplica.

---

## 9. Especificações técnicas e considerações de design

**Arquitetura (fluxo do PDF):**
```
React ─▶ GET /clinical-documents/{id}/pdf (JWT: clinic_id, role, user_id)
          ─▶ transação: set_config(app.clinic_id, app.role, app.user_id, local)
              ├─ SELECT clinical_documents (RLS: só DENTIST_ADMIN)         ── anonimizado? ─▶ 409
              ├─ confere sha256 (sobre o conteúdo cifrado)                  ── divergiu? ─▶ 500 integrity
              ├─ SELECT core.record_document_download(id)  (DOWNLOAD no audit_log)
              ├─ decifra o texto, lê clínica e dentista (nome, CRO), gera o PDF em memória
              └─ COMMIT ─▶ 200 application/pdf (inline, no-store)
   falha em qualquer passo ─▶ ROLLBACK (nenhum DOWNLOAD fica gravado)
```

**Pontos de integração:**
- Banco (contrato fechado): `core.clinical_documents`, `core.record_document_download(uuid)`, `users.cro_number/cro_state`, `ck_cdoc_*`, `ix_cdoc_patient`, `ix_appt_completed_patient`. O backend não conhece o lock consultivo `cdoc:`; só confia na numeração do banco.
- `AesGcmCipher`/`KeyRing` (`CRM_AES_KEYS`, `CRM_AES_ACTIVE_VERSION`): cifra explícita no service; rotação de chave não muda o hash (o hash cobre o texto cifrado já gravado).
- OpenPDF (já no `pom.xml`): `DocumentPdfRenderer` puro, Helvetica `CP1252` sem fonte embutida, A4, 1 a 2 páginas; tamanho de saída não é limitado pelo banco (nada é gravado), mas tem teto de **4 MB**, acima disso 422.
- `ClinicZones`, `ClinicIdentity`, `UserNames`, `PatientLookup`, `AppointmentLookup`, `BudgetLookup`: interfaces públicas já existentes (ou ampliadas) das fases anteriores.
- `GlobalExceptionHandler`/`DatabaseErrors`: novos mapeamentos; a tradução usa o nome da constraint (`ck_cdoc_*`) para separar 422 (dado) de 409 (imutabilidade).

**Segurança e privacidade:**
- Documento clínico é dado de saúde: cifrado na aplicação, só do dentista no backend **e** no banco (RLS por papel), com download auditado e anonimizável.
- O motivo de anulação é texto livre **em claro**: aviso no contrato, limite de 200 caracteres, zerado na anonimização; nunca vai a log.
- O PDF carrega o nome do paciente e do dentista: resposta `no-store`, nome de arquivo sem dado pessoal, metadados sem dado pessoal, nenhum cache de proxy.
- O dashboard da recepção nunca consulta faturamento; o corte é de produto, não fronteira de segurança do banco (S16).
- Menor privilégio e RLS: o backend conecta como `crm_app`; nada de `DELETE`, `TRUNCATE` ou DDL; a anulação usa `UPDATE` só das quatro colunas do grant.

**UI/UX:** não se aplica (backend). O contrato de cada feature documenta os campos e erros para o front.

---

## 10. Riscos e perguntas abertas

**Riscos técnicos**
1. **Mapeamento JPA fino.** `smallint` ↔ `Short`, colunas preenchidas por trigger (`number`), `@DynamicUpdate` com grant por coluna, `refresh()` fora do padrão: erro de mapeamento vira `42501` ou falha do `validate`. Mitigação: IT contra o PostgreSQL real de Testcontainers com as 35 migrations e teste de `ddl-auto=validate`.
2. **Hash sobre o texto cifrado.** Cifrar duas vezes o mesmo texto gera valores diferentes (IV novo); o hash só vale para o valor **gravado**. O service cifra uma única vez por emissão e não recifra ao ler. Teste: reler o documento não altera o hash.
3. **Conexão presa na geração do PDF.** A transação do PDF segura 1 conexão do pool de 10 durante a renderização (em memória, milissegundos). O limite de 30 PDFs/min por usuário e o teste de pool (como a `Phase3PoolIT`) provam que a leitura continua responsiva sob carga.
4. **Lock consultivo `cdoc:` atrás do pooler transaction (6543).** A numeração concorrente só se confirma no ambiente real; entra no smoke contra o Supabase (US-P4-10).
5. **Dashboard sob RLS.** As consultas usam `<>`/`OR` e as janelas calculadas pelo backend; plano e tempo já provados no banco (K1 a K5), mas o backend precisa passar `timestamptz` (não `date`) para não perder índice. Teste de plano via `EXPLAIN` no IT de desempenho.
6. **Fuso e virada de período.** Hoje, semana e mês dependem de `clinics.timezone`; testes com relógio fixo (`ClockConfig`) em UTC−3, na virada de mês/ano e com agendamento às 23:30 locais.
7. **Reimpressão muda o cabeçalho.** Sem snapshot, mudar nome da clínica, endereço ou CRO altera o PDF de documentos antigos (risco 1 do banco, aceito); o `sha256` cobre o conteúdo emitido, não o cabeçalho.
8. **Faturamento visível à recepção pelo banco (S16).** A recepção continua lendo `budgets`; o corte do KPI é regra do backend. Risco aceito e documentado.
9. **Chaves AES.** Perder `CRM_AES_KEYS` = perder o texto dos documentos (e do prontuário); a guarda da chave fora do repositório segue pendente (pendência de segurança herdada).
10. **`CR012` e o contrato de download** passam a ser definição do backend; qualquer mudança de nome exige uma nova migration do banco.

**Perguntas abertas** — *respondidas em 2026-10-27: teto do PDF de **4 MB** (e não 1 MB); as demais, conforme o padrão proposto. Lista mantida abaixo como registro.*
- Teto do PDF em **4 MB** e 2 páginas para 4.000 caracteres? (**Respondido: 4 MB**; acima disso 422.)
- Nome sugerido do arquivo do PDF: `atestado-12.pdf`, `receita-3.pdf`, `encaminhamento-1.pdf`? (Sim, confirmado.)
- `GET /dashboard` com `weekOf` fora do intervalo [2000-01-01, hoje + 366 dias] retorna 422? (Sim.)
- A resposta da lista usa `page`/`size` com `size` máximo 50, como em pacientes, e não keyset? (Sim; o índice cobre os dois e o volume por paciente é pequeno.)
- Reemitir o PDF de documento `VOIDED` conta `DOWNLOAD` como qualquer outro? (Sim.)

**Lançamento em etapas**
- **MVP (Fase 4):** US-P4-01 a US-P4-10.
- **v1.1:** snapshot do cabeçalho no documento, modelos por clínica, auditoria de leitura em tela, `started_at`/`finished_at` e métricas de duração.
- **v2.0:** assinatura digital (ICP-Brasil), receita de controle especial, múltiplos profissionais, cache e views de relatório se o volume justificar.
