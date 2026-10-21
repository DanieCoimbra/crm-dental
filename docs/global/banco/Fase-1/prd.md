# PRD Global (Banco) — Fase 1: Núcleo clínico

**Escopo:** modelo de dados, papéis, RLS, auditoria e migrations da **Fase 1** (pacientes, prontuário simplificado e metadados de exames), sobre a base da Fase 0.
**Fontes:** `docs/arq_inicial.md` (Fase 1), `docs/global/banco/Fase-0/{prd,spec}.md`, `docs/global/backend/Fase-0/spec.md` (seção 2), `database/` (migrations V001–V006). O `AGENTS.md` da raiz está vazio.
**Função no pipeline:** `banco`. O backend não cria nem altera objetos de banco. Não há docs de backend da Fase 1 ainda: o contrato abaixo é a proposta do banco e deve ser conciliado quando o backend planejar a fase.

## Decisões fechadas

Herdam as decisões B1–B8 da Fase 0 (Supabase só como PostgreSQL, migrations por psql (Flyway abandonado em 2026-10-21), `crm_app` único, RLS `ENABLE` + `FORCE`, cifra na aplicação). Novas, confirmadas em 2026-10-21:

| # | Decisão | Valor |
|---|---|---|
| P1 | Recepcionista × prontuário e exames | **Prontuário (histórico médico e notas): nenhum acesso**; a RLS nega `SELECT/INSERT/UPDATE` a `RECEPTIONIST` (reforço no banco por `app.role`). **Exames: só `INSERT`** de metadados; não lê, não lista e não altera (leitura e alteração só `DENTIST_ADMIN`) |
| P5 | Armazenamento de exames | **Supabase Storage** (bucket privado), acessado só pelo backend (`CRM_STORAGE_*`). Revisa a B2 da Fase 0 (ver risco 4) |
| P2 | Campos cifrados (AES-256-GCM, na aplicação) | **Só o prontuário**: histórico médico e notas. Dados cadastrais do paciente (nome, CPF, telefone, e-mail, convênio) ficam em texto claro, protegidos por RLS |
| P3 | Exclusão de paciente | **Inativação lógica** (`active`, `deactivated_at`). `crm_app` não tem `DELETE` em nenhuma tabela clínica |
| P4 | Auditoria | `audit_log` por trigger para as tabelas clínicas: quem, quando, qual tabela, qual registro, qual ação. **Sem valores** das colunas |

## Padrões adotados (sem pergunta; contestar se discordar)

- **Schema `core`** para as tabelas novas (confirmado em 2026-10-21; a visão da Fase 0 já dizia "`core` + pacientes, prontuário"). Mantém `search_path` e grants.
- **Cadastro do paciente** (confirmado): além de nome, CPF, nascimento, contato e convênio, entram **sexo**, **profissão** e **contato de um conhecido** (nome e telefone, para emergência). Não há responsável legal.
- **Limites de tamanho** (texto do prontuário cifrado, `size_bytes` do arquivo): definidos pela `spec`, coordenados com o backend; o PRD não fixa números.
- **LGPD e retenção** (decisão "siga a LGPD", 2026-10-21): base legal de tutela da saúde (art. 11, II, "f") e de obrigação legal de guarda; prontuário guardado por no mínimo 20 anos a contar do último registro (Lei 13.787/2018, a confirmar com assessoria jurídica antes de produção real); depois disso, anonimização por função própria (US-110).
- Convenções da Fase 0: `snake_case`, tabelas no plural, `uuid` gerado pela aplicação, `varchar(n)` + `CHECK`, `timestamptz`, `updated_at` por trigger, `version bigint` para `@Version`, FKs compostas `(x_id, clinic_id)` para impedir vínculo entre clínicas.
- **Exames:** o arquivo vive no Supabase Storage (bucket privado, configurado no backend por `CRM_STORAGE_*`); o banco guarda **só metadados**. O banco nunca guarda o binário.
- **Exames, por papel:** `DENTIST_ADMIN` lê, insere e altera metadados; `RECEPTIONIST` só insere (anexa o exame) e não enxerga o que anexou depois. Consequência para o backend: o `INSERT` da recepção não pode usar `RETURNING` nem recarregar a linha (a RLS de `SELECT` nega); com `id` gerado pela aplicação isso já é o padrão.
- **Notas de atendimento** nascem sem vínculo com agendamento; a coluna `appointment_id` entra na Fase 2, nula e aditiva.
- **Busca por nome:** extensão `pg_trgm` com índice GIN sobre `lower(name)`; busca por CPF por igualdade (índice único parcial).
- **Valores de sessão:** o backend passa a definir também `app.role` (`DENTIST_ADMIN` | `RECEPTIONIST`) e `app.user_id`, por `set_config(..., true)` na mesma transação de `app.clinic_id`. Ausentes, as políticas clínicas negam (falha fechada).
- Notas clínicas são **editáveis** pelo dentista (com `version` e auditoria); não há `DELETE`.

---

## 1. Resumo executivo

**Problema:** a Fase 1 introduz os primeiros dados de saúde (prontuário, exames). Além do isolamento entre clínicas, é preciso separar o que a recepção pode ver do que só o dentista vê, e deixar rastro de quem tocou nos dados.

**Solução:** quatro tabelas clínicas (`patients`, `medical_histories`, `clinical_notes`, `patient_files`) mais `audit_log`, com RLS por clínica e por papel, campos de prontuário opacos para o banco (cifrados pela aplicação), inativação lógica e trilha de auditoria sem valores.

**Metas e métricas:**
1. Sob `app.clinic_id = A`, 0 linhas das clínicas B nas 5 tabelas novas para `SELECT`, `UPDATE` e `INSERT` (testes de isolamento, como na Fase 0).
2. Com `app.role = RECEPTIONIST`, 0 linhas legíveis e 0 gravações possíveis em `medical_histories` e `clinical_notes`; em `patient_files`, 0 linhas legíveis, 0 `UPDATE`, e `INSERT` permitido só para a própria clínica. Com `app.role` ausente ou inválido, tudo negado.
3. 100% dos `INSERT/UPDATE/DELETE` nas tabelas clínicas geram 1 linha em `audit_log`, sem nenhuma coluna de conteúdo; `crm_app` não consegue alterar nem apagar `audit_log`.
4. Busca de pacientes por trecho de nome: ≤ 20 ms (`EXPLAIN ANALYZE`, índice GIN) com 10 mil pacientes na clínica; listagem paginada por keyset ou `LIMIT` com teto, sem `Seq Scan`.
5. Nenhuma coluna de prontuário contém texto claro: o teste de migração confirma que as colunas cifradas são `varchar` opacas e que o banco não tem `pgcrypto` nem chave.
6. Todas as migrations da Fase 1 são aditivas, aplicam do zero; o `pg_dump` do `crm_backup` traz as 5 tabelas novas.

---

## 2. User stories

### US-101: Tabela de pacientes
**Descrição:** Como backend, quero armazenar o cadastro de pacientes por clínica para a recepção e o dentista usarem.

**Critérios de aceite:**
- [ ] `core.patients` com dados demográficos (nome, nascimento, `sex` em lista fixa com `NOT_INFORMED`, profissão), contato (telefone, e-mail, endereço), contato de um conhecido (`acquaintance_name`, `acquaintance_phone`, ambos nulos ou ambos preenchidos), convênio (nome e número da carteirinha), `active`, `deactivated_at`, `created_by`, `created_at`, `updated_at`, `version`.
- [ ] CPF opcional, só dígitos, com `CHECK` de formato e **único por clínica** (índice parcial `WHERE cpf IS NOT NULL`); duplicado falha com `23505`.
- [ ] RLS `ENABLE` + `FORCE`, política por operação com `clinic_id = core.current_clinic_id()`; `DELETE` sem política.
- [ ] `clinic_id`, `id` e `created_at` imutáveis (trigger); `updated_at` por trigger.
- [ ] Leitura e escrita permitidas a `DENTIST_ADMIN` **e** `RECEPTIONIST`.

### US-102: Busca paginada de pacientes
**Descrição:** Como recepcionista, quero buscar pacientes por parte do nome ou por CPF com rapidez.

**Critérios de aceite:**
- [ ] Extensão `pg_trgm` habilitada pelo bootstrap/migration, com índice GIN em `lower(name)`.
- [ ] Índice `(clinic_id, name, id)` para listagem ordenada com paginação por keyset.
- [ ] `EXPLAIN` documentado para a busca por nome e por CPF; sem `Seq Scan` com 10 mil linhas.

### US-103: Inativação de paciente
**Descrição:** Como dentista, quero inativar um paciente sem perder o histórico.

**Critérios de aceite:**
- [ ] `CHECK ((active AND deactivated_at IS NULL) OR (NOT active AND deactivated_at IS NOT NULL))`.
- [ ] `crm_app` sem `DELETE` em `patients` e nas demais tabelas clínicas (teste de permissão).
- [ ] FKs filhas `ON DELETE RESTRICT`.

### US-104: Histórico médico cifrado
**Descrição:** Como dentista, quero um histórico médico por paciente, ilegível para quem acessa o banco por fora da aplicação.

**Critérios de aceite:**
- [ ] `core.medical_histories`: no máximo 1 linha por paciente (`UNIQUE (patient_id)`); conteúdo em coluna `varchar` opaca (texto cifrado em Base64 com identificador de versão da chave).
- [ ] FK composta `(patient_id, clinic_id)` → `patients(id, clinic_id)`.
- [ ] Políticas exigem `clinic_id = tenant` **e** `core.current_app_role() = 'DENTIST_ADMIN'`.
- [ ] O banco não valida nem decifra o conteúdo; só limita o tamanho.

### US-105: Notas por atendimento
**Descrição:** Como dentista, quero registrar notas clínicas datadas de cada atendimento do paciente.

**Critérios de aceite:**
- [ ] `core.clinical_notes` com `patient_id`, `noted_at timestamptz`, conteúdo cifrado opaco, `created_by`, `version`.
- [ ] Índice `(clinic_id, patient_id, noted_at DESC, id)` para a linha do tempo.
- [ ] Mesmas exigências de clínica e papel da US-104; edição permitida, `DELETE` negado.

### US-106: Metadados de exames
**Descrição:** Como dentista, quero vincular radiografias e fotos ao paciente, guardando só o registro do arquivo.

**Critérios de aceite:**
- [ ] `core.patient_files`: `patient_id`, `kind` (`RADIOGRAPH`, `INTRAORAL_PHOTO`, `OTHER`), `storage_key` único, `original_filename`, `content_type` (`CHECK` em lista permitida), `size_bytes` (`CHECK` > 0 e ≤ limite do backend), `created_by`.
- [ ] Políticas: `SELECT` e `UPDATE` só para `DENTIST_ADMIN`; `INSERT` para `DENTIST_ADMIN` e `RECEPTIONIST`; tudo com `clinic_id = tenant`. Chave de armazenamento prefixada por `clinic_id` (validada por `CHECK`).
- [ ] Teste: sob `RECEPTIONIST`, `INSERT` em `patient_files` passa, `SELECT` retorna 0 linhas e `UPDATE` afeta 0 linhas.
- [ ] Remover o registro é inativação (`deleted_at`), nunca `DELETE`; o objeto no Storage é problema do backend.

### US-107: Reforço de papel no banco
**Descrição:** Como titular da clínica, quero que a recepção não leia o prontuário nem se o backend errar.

**Critérios de aceite:**
- [ ] Função `core.current_app_role()` (`STABLE`, não `SECURITY DEFINER`) devolve `NULL` se `app.role` estiver ausente ou fora de `DENTIST_ADMIN`/`RECEPTIONIST`.
- [ ] Teste: sob `RECEPTIONIST`, `SELECT` em `medical_histories`, `clinical_notes` e `patient_files` retorna 0 linhas; `INSERT`/`UPDATE` em `medical_histories` e `clinical_notes` falham; em `patient_files`, só o `INSERT` é permitido.
- [ ] Teste: sob `DENTIST_ADMIN` da clínica A, nada da clínica B (papel não substitui tenant).

### US-108: Trilha de auditoria
**Descrição:** Como dentista, quero saber quem criou ou alterou dados clínicos.

**Critérios de aceite:**
- [ ] `core.audit_log` (`id`, `clinic_id`, `user_id`, `table_name`, `record_id`, `action`, `occurred_at`); **sem** coluna de valores antigos ou novos.
- [ ] Triggers `AFTER INSERT/UPDATE/DELETE` em `patients`, `medical_histories`, `clinical_notes`, `patient_files` gravam via função `SECURITY DEFINER` (dono `crm_definer`, `search_path` fixo); `user_id` vem de `app.user_id`.
- [ ] `crm_app` tem `SELECT` (só `DENTIST_ADMIN`) e **nenhum** `INSERT/UPDATE/DELETE/TRUNCATE` em `audit_log`.
- [ ] Sem `app.user_id`, a operação clínica falha (auditoria não pode ser anônima).

### US-110: Retenção e direitos do titular (LGPD)
**Descrição:** Como titular de uma clínica, quero cumprir a guarda legal do prontuário e, passado o prazo, ou quando o titular exigir, anonimizar os dados pessoais sem apagar o rastro de auditoria.

**Critérios de aceite:**
- [ ] O fim da retenção (último registro clínico + 20 anos) é **calculado** pela função de anonimização, sem coluna própria (spec, S2); o prazo de 20 anos fica em uma função-constante documentada. Paciente sem nenhum registro clínico não tem retenção (spec, S4).
- [ ] Função `core.anonymize_patient(p_patient_id uuid)` (`SECURITY DEFINER`, `search_path` fixo, sem SQL dinâmico, `EXECUTE` só para `crm_app`): exige `app.role = DENTIST_ADMIN`, paciente inativo da própria clínica e retenção vencida; caso contrário, erro explícito.
- [ ] A anonimização zera ou substitui nome, CPF, contato, convênio, conhecido, endereço e profissão, apaga `medical_histories` e `clinical_notes` do paciente, marca os `patient_files` como removidos (a limpeza dos objetos no Storage é do backend) e grava a ação em `audit_log`.
- [ ] `audit_log` não é apagado nem anonimizado: guarda só identificadores, sem dado pessoal.
- [ ] Correção de dados do titular é `UPDATE` comum; acesso e portabilidade são exportações feitas pelo backend (o banco não precisa de objeto novo).
- [ ] Documentado: dumps do `crm_backup` guardam o dado por até 30 dias após a anonimização (retenção do artefato); essa janela consta da política de privacidade.
- [ ] Teste: antes do prazo a função falha; depois do prazo anonimiza e nenhuma coluna pessoal permanece com valor original.

### US-109: Backup e testes da fase
**Descrição:** Como responsável pelo banco, quero que as tabelas novas entrem no backup e nos testes existentes.

**Critérios de aceite:**
- [ ] Cada tabela nova tem a política `SELECT TO crm_backup USING (true)`.
- [ ] `database/tests/` ganha testes de isolamento, de papel, de constraints, de auditoria e de desempenho; `run_all.sh` passa do zero.
- [ ] Rollback documentado em `database/rollback/` para cada migration nova.

---

## 3. Requisitos funcionais

- **FR-1:** Criar em `core` as tabelas `patients`, `medical_histories`, `clinical_notes`, `patient_files` e `audit_log`, conforme dicionário de dados da spec.
- **FR-2:** Toda tabela nova tem `clinic_id uuid NOT NULL`, FK, índice com `clinic_id` à frente, RLS `ENABLE` + `FORCE` e uma política por operação (`USING` e `WITH CHECK`).
- **FR-3:** Filhas de `patients` usam FK composta `(patient_id, clinic_id)`; um registro nunca aponta para paciente de outra clínica.
- **FR-4:** `medical_histories` e `clinical_notes` exigem `core.current_app_role() = 'DENTIST_ADMIN'` em todas as operações. `patient_files` exige `DENTIST_ADMIN` para `SELECT` e `UPDATE`, e aceita `DENTIST_ADMIN` ou `RECEPTIONIST` no `INSERT`.
- **FR-5:** `patients` aceita `SELECT/INSERT/UPDATE` para os dois papéis. Nenhuma tabela clínica tem política de `DELETE` para `crm_app`.
- **FR-6:** Colunas de prontuário são `varchar` opacas; nenhum `pgcrypto`, nenhuma chave no banco.
- **FR-7:** CPF único por clínica; e-mail do paciente em minúsculas quando informado; texto com limites de tamanho por `CHECK`.
- **FR-8:** Triggers: `updated_at`, bloqueio de colunas imutáveis (`id`, `clinic_id`, `patient_id`, `created_at`) e auditoria.
- **FR-9:** `audit_log` é append-only para a aplicação, preenchido apenas por função `SECURITY DEFINER`.
- **FR-10:** Sem `app.clinic_id`, `app.role` ou `app.user_id` válidos, as tabelas clínicas negam (0 linhas, gravações falham).
- **FR-11:** Habilitar `pg_trgm` e criar os índices de busca da US-102.
- **FR-12:** `crm_app` recebe `SELECT/INSERT/UPDATE` por tabela (sem `GRANT ALL`); `EXECUTE` só nas funções necessárias; `crm_backup` recebe `SELECT` e política nomeada.
- **FR-13:** Publicar o contrato (nomes, tipos, `app.role`, `app.user_id`) para o backend da Fase 1 e conciliar divergências.
- **FR-14:** Migrations novas, aditivas e numeradas a partir de `V007`.
- **FR-15:** `patients` tem `sex`, `profession`, `acquaintance_name`, `acquaintance_phone` e `anonymized_at`; limites de tamanho dos textos cifrados e do arquivo são definidos na spec.
- **FR-16:** Nenhum dado de prontuário é apagado fisicamente antes do fim da retenção; depois, só a função `core.anonymize_patient` o remove, e a ação é auditada.

## 4. Non-goals (fora de escopo)

- Agenda, agendamento, feriados, `btree_gist` (Fase 2); `appointment_id` nas notas entra lá.
- Orçamentos, procedimentos, financeiro e documentos (Fases 3–4).
- Odontograma estruturado, evolução por dente, assinatura digital e consentimento LGPD detalhado.
- Armazenar o binário dos exames no banco; antivírus, miniaturas e geração de URL assinada (backend).
- Busca em campos cifrados e busca por telefone ou e-mail.
- Exclusão física de paciente; a eliminação é anonimização após a retenção (US-110). Pedido do titular **dentro** do prazo de guarda é negado pela regra de obrigação legal.
- Responsável legal de menor, consentimento LGPD detalhado e registro de consentimento.
- Auditoria de **leitura** do prontuário e comparação de valores antigo/novo.
- Particionamento e réplicas.
- Criptografia no banco e gestão/rotação de chaves AES (backend/operação).

## 5. Requisitos de IA

Não se aplica.

## 6. Especificações técnicas e considerações de design

**Arquitetura (dados):**
```
React ─▶ API (JWT: clinic_id, role, user_id) ─▶ transação: set_config(app.clinic_id, app.role, app.user_id, local)
                                                    │
                       ┌────────────────────────────┴───────────────────────────┐
                       ▼                                                        ▼
        core.patients (DENTIST_ADMIN, RECEPTIONIST)      core.medical_histories / clinical_notes / patient_files
                       │                                   (só DENTIST_ADMIN; texto cifrado pela aplicação)
                       └──── triggers ──▶ core.audit_log (append-only, SECURITY DEFINER)
   (patient_files: RECEPTIONIST só INSERT)       arquivos ─▶ Supabase Storage (bucket privado, só o backend)
```

**Pontos de integração:**
- Backend (módulos `patient`, `record`): entidades JPA com `ddl-auto=validate`; define as três variáveis de sessão em toda transação; cifra/decifra os campos de prontuário (F03 do backend), guardando o identificador da versão da chave junto ao texto cifrado.
- Armazenamento de objetos: acessado só pelo backend; o banco guarda `storage_key`.
- Operação: `scripts/apply_migrations.sh`, `database/tests`, workflows de backup e restauração já existentes.

**UI/UX:** não se aplica (banco).

**Segurança e privacidade:**
- Dados de saúde são dados pessoais sensíveis (LGPD). O projeto é de portfólio com dados fictícios (risco de produção aceito); se passar a ter pacientes reais, revisar retenção, backup, base legal e plano pago antes.
- Menor privilégio: sem `GRANT ALL`, sem `DELETE`, sem `BYPASSRLS`; `audit_log` imutável para a aplicação.
- `original_filename` e `storage_key` não podem conter dados clínicos; a aplicação gera o `storage_key` (UUID).
- Logs do PostgreSQL sem parâmetros; o texto cifrado não é registrado em log.
- Trade-off aceito (P2): CPF, telefone e convênio ficam em texto claro, protegidos por RLS e papéis, não por cifra. Um dump do `crm_backup` os expõe; o backup já é cifrado com `age`.

## 7. Riscos e perguntas abertas

**Riscos técnicos**
1. **`app.role` forjável pela aplicação.** O banco confia no valor que o backend define; um bug ou injeção no backend que mude `app.role` contorna P1. Mitigação: o valor vem só do JWT validado, definido em um único ponto (aspecto transacional), com teste de integração; limite aceito da arquitetura de papel único `crm_app`.
2. **`pg_trgm` no Supabase** pode exigir `CREATE EXTENSION ... SCHEMA extensions` e ajuste do `search_path` de `crm_app`. Validar no projeto real; plano B: busca por prefixo com índice `text_pattern_ops`.
3. **Objetos do Storage fora do `pg_dump`.** O backup de banco não cobre os arquivos de exames; perda no Storage deixa metadados órfãos. Aceito para portfólio; documentar.
4. **Supabase Storage adotado (P5), revisando a B2 da Fase 0**, que dizia "não usar Storage". O Storage não passa pelo `crm_app`, mas mora no mesmo projeto Supabase. Condições: bucket **privado** (sem política pública); acesso só pelo backend com a chave de serviço, que tem poder equivalente a `service_role` e é segredo só do backend (nunca no frontend nem no repositório); download por URL assinada de curta duração gerada pelo backend depois de checar papel e clínica; objeto sob o prefixo `<clinic_id>/<patient_id>/<uuid>`. As regras de `storage.objects` (RLS do Supabase) ficam fora do `crm_app`; a segurança dos arquivos depende do backend. Ao aprovar este PRD, atualizar a B2 e as seções 7.2 e 7.3 da spec da Fase 0 ("não usar Auth, Storage nem Realtime" passa a "não usar Auth nem Realtime"). O bootstrap do bucket e suas políticas são manuais (painel), documentados em `database/README.md`.
5. **Perda da chave AES** torna histórico e notas irrecuperáveis (já listado na Fase 0).
6. **Auditoria que falha bloqueia a operação** (sem `app.user_id` a gravação clínica falha). Intencional; custa uma linha de log por escrita (volume baixo).
7. **Sem homologação:** migrations pequenas, aditivas e reversíveis; a validação é o contêiner do PR/ensaios locais (o CI/CD está adiado, os testes rodam manualmente com `database/tests/run_all.sh`).

**Perguntas abertas**
- Limites de tamanho do prontuário cifrado e do arquivo de exame: a spec define e o backend da Fase 1 confirma (respeitando o teto de upload do plano gratuito do Storage).
- Prazo de guarda de 20 anos: validar com assessoria jurídica/CFO antes de usar dados reais. O projeto usa dados fictícios, então o prazo fica como parâmetro documentado.
- A rotina de anonimização é chamada pelo backend a pedido do dentista; não há expurgo automático agendado na Fase 1.

**Lançamento em etapas**
- **MVP (Fase 1):** US-101 a US-110.
- **v1.1 (Fase 2):** `appointment_id` nas notas; vínculo de atendimento.
- **v2.0:** auditoria de leitura, expurgo agendado por retenção, registro de consentimento.
