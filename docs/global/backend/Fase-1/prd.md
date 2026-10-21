# PRD Global (Backend) — Fase 1: Núcleo clínico

**Escopo:** API REST dos módulos `patient` e `record` (pacientes, prontuário simplificado cifrado, exames, auditoria e anonimização LGPD), sobre a base da Fase 0 (auth, tenant, criptografia, erros).
**Fontes:** `docs/arq_inicial.md` (Fase 1), `docs/global/backend/Fase-0/{prd,spec}.md`, `docs/global/banco/Fase-1/{prd,spec}.md` (contrato do banco, seção 6), código atual de `back-java/` e `database/` (migrations V001–V013). O `AGENTS.md` da raiz está **vazio**: as premissas vêm dos documentos acima e da memória do projeto.
**Função no pipeline:** `backend`. O backend **não** cria nem altera objetos de banco; consome o contrato da função `banco`. Este PRD é a conciliação prevista no FR-13 do PRD de banco da Fase 1.
**Data:** 2026-10-21.

## Decisões fechadas

Herdam D1–D8 da Fase 0 (monólito modular, Spring Boot 4.1.1 / Java 21, pacote `crm_data`, JWT 15 min + refresh rotativo, filtro Hibernate **+** RLS, sem trial, schema dono do banco). Novas, confirmadas em 2026-10-21:

| # | Decisão | Valor |
|---|---|---|
| E1 | Cifra dos arquivos de exame | **Não cifrar** o binário. Bucket privado do Supabase Storage, acesso só pelo backend; o dentista baixa por **URL assinada de curta duração** gerada depois de checar clínica e papel. Risco aceito (dados fictícios): o arquivo fica em claro no Storage |
| E2 | Avatar do perfil | **Fora da Fase 1** (backlog). O banco não tem coluna nem tabela para ele; exigiria migration nova |
| E3 | LGPD e auditoria expostas | O backend expõe **anonimização** do paciente (só `DENTIST_ADMIN`) e **consulta da trilha de auditoria** (só `DENTIST_ADMIN`, somente leitura) |
| E4 | Recepcionista × prontuário e exames | Herdado do banco (P1): recepção **sem acesso** a histórico médico e notas; em exames **só anexa** (`POST`), sem listar, baixar, alterar ou remover. O backend nega com 403 **antes** do banco; a RLS é a segunda barreira |
| E5 | Cifra do prontuário | AES-256-GCM na aplicação (`EncryptedStringConverter`, F03 da Fase 0), **só** `medical_histories.content` e `clinical_notes.content`. Dados cadastrais do paciente ficam em texto claro (P2 do banco) |
| E6 | Exclusão | Nenhum `DELETE` físico. Paciente: inativação lógica. Exame: remoção lógica (`deleted_at`). Eliminação só por anonimização após a retenção (20 anos) |

## Padrões adotados (sem pergunta; contestar se discordar)

- **Paginação:** reaproveita `PageResponse` da Fase 0 (`page`, `size`, `totalElements`), `size` máx. 50, ordenação fixa por nome e `id`. O keyset do banco fica disponível, mas o volume (poucos milhares por clínica) não o exige.
- **Busca de pacientes:** parâmetro `q` com mínimo de 2 caracteres. Se `q` tiver 11 dígitos (com ou sem máscara), busca por CPF (igualdade); senão, por trecho do nome em minúsculas (`lower(name) LIKE`). Sem busca insensível a acento (S8 do banco); o backend só normaliza caixa e espaços. Por padrão lista só pacientes **ativos**; filtro `status=active|inactive|all`.
- **Normalização no backend:** telefone só com dígitos (10–13), e-mail em minúsculas, CPF só com dígitos e **dígito verificador validado** (o banco só confere o formato), data de nascimento não pode ser futura, nome sem espaços nas pontas.
- **Limites (S5 do banco):** histórico até 10.000 caracteres; nota até 5.000; exame até 10 MB e só `image/jpeg`, `image/png`, `image/webp`, `application/pdf`. O tipo é conferido pelos **bytes iniciais** do arquivo, não só pelo `Content-Type` declarado.
- **Chave do objeto:** `<clinic_id>/<patient_id>/<uuid>`, gerada pelo backend a partir do `TenantContext`; nome original do arquivo nunca entra na chave.
- **Remoção de exame:** lógica (`deleted_at`). O **objeto continua no Storage** (faz parte do prontuário, sujeito à retenção) e só é apagado na anonimização.
- **Rate limit de upload:** 10 uploads por minuto por usuário (Bucket4j, em memória como na Fase 0), para proteger o Storage gratuito.
- **URL assinada:** validade de 60 segundos, gerada só para exame ativo da clínica do token e papel `DENTIST_ADMIN`; resposta com `Cache-Control: no-store`; a URL não é registrada em log.
- **IDs:** o `id` de toda entidade nova é gerado pela aplicação (UUID), o que evita `RETURNING` na inserção do exame pela recepção (ver risco 2).
- Convenções da Fase 0 mantidas: Lombok sem `@Data`/`@ToString`/`@EqualsAndHashCode`; `ProblemDetail` (RFC 9457); `@PreAuthorize` nos services como segunda barreira; DTOs sem `clinicId`, `createdBy`, `role` ou `id` vindos do cliente.

---

## 1. Resumo executivo

**Problema:** a Fase 0 só tem login e clínica. O dentista ainda não consegue cadastrar pacientes, registrar o prontuário nem anexar exames, e esses são os primeiros dados de saúde do sistema (LGPD): além de isolar clínicas, é preciso separar o que a recepção vê do que só o dentista vê e deixar rastro de quem tocou nos dados.

**Solução:** módulos `patient` e `record` que expõem cadastro e busca de pacientes, histórico médico e notas cifrados, upload de exames no Supabase Storage com download por URL assinada, consulta de auditoria e anonimização, tudo com papel e tenant validados no servidor e reforçados pela RLS do banco.

**Metas e métricas (backend, Fase 1):**
1. 100% dos endpoints novos têm teste da matriz papel × endpoint (401/403/2xx); `RECEPTIONIST` recebe 403 em 100% das rotas de prontuário, listagem e download de exames, auditoria e anonimização.
2. Com o filtro Hibernate desligado e `app.role = RECEPTIONIST` forjado no teste, a RLS sozinha devolve 0 linhas de prontuário e de exames (defesa em profundidade provada em Testcontainers, como a US-007 da Fase 0).
3. Nenhum texto clínico em claro no banco nem nos logs: teste de integração lê `content` por SQL nativo e confere formato Base64 cifrado; teste de log confere ausência de conteúdo, CPF e URL assinada.
4. Busca de pacientes responde em ≤ 100 ms (p95) pela API, com 10 mil pacientes na clínica e 4 requisições concorrentes em ambiente local.
5. `ddl-auto=validate` passa contra as migrations V001–V013 (fecha o teste 57 do banco) e `mvn verify` continua verde em máquina limpa.
6. Cobertura ≥ 80% nas classes de serviço de `patient` e `record`.

---

## 2. Módulos e mudanças em `shared`

| Módulo | Responsabilidade |
|---|---|
| `patient` | Cadastro, busca, edição, inativação/reativação e **anonimização** do paciente |
| `record` | Histórico médico (1 por paciente), notas por atendimento, metadados e arquivos de exame, consulta de auditoria |
| `shared` (alterado) | **Tenant:** além de `app.clinic_id`, definir `app.role` e `app.user_id` em toda transação autenticada, em um único ponto (hoje só `app.clinic_id`). **Storage:** `SupabaseFileStorage` implementa `FileStorage`, que ganha geração de URL assinada; implementação em disco/memória para dev e teste. **Erros:** tradução dos SQLSTATE do banco (`CR001`, `CR002`, `CR003`, `42501`, `P0002`, `23505`) em `ProblemDetail`. **Config:** limite de multipart (10 MB) e propriedades `CRM_STORAGE_*` validadas |

Regra de dependência da Fase 0 mantida: `record` consulta `patient` apenas pela interface pública de `service` (para checar que o paciente existe, está ativo e não está anonimizado); a anonimização em `patient` pede ao `record` as chaves de armazenamento do paciente pela mesma via. Nenhum módulo usa o repositório do outro.

---

## 3. User stories

### US-P1-01: Cadastrar paciente
**Descrição:** Como recepcionista ou dentista, quero cadastrar um paciente com dados demográficos, contato, convênio e contato de um conhecido para iniciar o atendimento.

**Critérios de aceite:**
- [ ] `POST /api/v1/patients` (D, R) cria o paciente na clínica do token e devolve 201 com o recurso; `clinic_id`, `created_by` e `id` nunca vêm do corpo.
- [ ] Obrigatórios: nome (2–120), data de nascimento (≥ 1900-01-01, não futura) e telefone (10–13 dígitos após normalização). Opcionais: CPF, sexo (`FEMALE`, `MALE`, `OTHER`, `NOT_INFORMED`; padrão `NOT_INFORMED`), profissão, e-mail, endereço, convênio (nome e carteirinha; carteirinha exige nome), conhecido (nome e telefone, ambos ou nenhum).
- [ ] CPF com dígito verificador inválido retorna 400 com o campo; CPF já cadastrado na clínica retorna 409 **sem** revelar o outro paciente; o mesmo CPF em outra clínica é aceito.
- [ ] Erros de validação listam todos os campos inválidos (`errors[]`, como na Fase 0).
- [ ] Teste de matriz: `DENTIST_ADMIN` e `RECEPTIONIST` recebem 201; sem token, 401.

### US-P1-02: Buscar e listar pacientes
**Descrição:** Como recepcionista ou dentista, quero buscar pacientes por parte do nome ou por CPF, com paginação, para achar a ficha rápido.

**Critérios de aceite:**
- [ ] `GET /api/v1/patients?q&status&page&size` (D, R) retorna `PageResponse` com `size` máx. 50, ordenado por nome e `id`.
- [ ] `q` com menos de 2 caracteres retorna 400; `q` de 11 dígitos busca por CPF; senão por trecho do nome sem diferenciar maiúsculas.
- [ ] A listagem traz só campos de identificação (id, nome, telefone, nascimento, convênio, `active`); não traz prontuário nem contagem de exames.
- [ ] Meta 4 do resumo: p95 ≤ 100 ms com 10 mil pacientes (teste de desempenho em IT).
- [ ] Paciente de outra clínica nunca aparece, inclusive com o filtro Hibernate desligado.

### US-P1-03: Ver e editar paciente
**Descrição:** Como recepcionista ou dentista, quero ver e corrigir a ficha do paciente.

**Critérios de aceite:**
- [ ] `GET /api/v1/patients/{id}` e `PUT /api/v1/patients/{id}` (D, R); `{id}` de outra clínica retorna 404.
- [ ] `PUT` troca todos os campos editáveis e exige `version` para controle otimista; versão defasada retorna 409.
- [ ] Paciente anonimizado: `GET` devolve a ficha anonimizada e `PUT` retorna 409 (`CR003`).
- [ ] A resposta nunca contém campos de prontuário.

### US-P1-04: Inativar e reativar paciente
**Descrição:** Como dentista, quero inativar um paciente sem perder o histórico.

**Critérios de aceite:**
- [ ] `PATCH /api/v1/patients/{id}/active` `{active}` (só D; R recebe 403) grava `active` e `deactivated_at` de forma coerente.
- [ ] Paciente inativo continua legível e aparece em `status=inactive|all`; novos registros clínicos para paciente inativo são aceitos (a ficha antiga continua sendo atendida), mas o paciente anonimizado nunca é reativado (409).
- [ ] Nenhum endpoint de `DELETE` físico existe para paciente.

### US-P1-05: Histórico médico cifrado
**Descrição:** Como dentista, quero um histórico médico por paciente, ilegível para quem acessa o banco por fora da aplicação.

**Critérios de aceite:**
- [ ] `GET` e `PUT /api/v1/patients/{id}/medical-history` (só D); o `PUT` cria na primeira vez (upsert por paciente) e atualiza depois, com `version`.
- [ ] Texto até 10.000 caracteres (400 acima); gravado cifrado por AES-256-GCM com a versão da chave gravada junto.
- [ ] `GET` sem histórico cadastrado retorna 404; recepcionista recebe 403.
- [ ] Teste de integração: `SELECT content` por SQL nativo não contém o texto original e casa com `^[A-Za-z0-9+/]+={0,2}$`.
- [ ] O conteúdo não aparece em `toString`, log, exceção nem resposta de erro.

### US-P1-06: Notas por atendimento
**Descrição:** Como dentista, quero registrar e corrigir notas clínicas datadas de cada atendimento.

**Critérios de aceite:**
- [ ] `POST /api/v1/patients/{id}/notes` cria nota (`notedAt` opcional, padrão agora; não pode ser futuro; conteúdo até 5.000 caracteres, cifrado).
- [ ] `GET /api/v1/patients/{id}/notes?page&size` lista da mais recente para a mais antiga (`notedAt DESC, id DESC`), `size` máx. 50, já decifrada.
- [ ] `PUT /api/v1/patients/{id}/notes/{noteId}` edita conteúdo e `notedAt` com `version`; não existe `DELETE`.
- [ ] Só D (R recebe 403). Nota de outro paciente ou de outra clínica retorna 404.
- [ ] Editar o prontuário **reinicia** a contagem de retenção de 20 anos (contrato F06 do banco); a documentação da API registra isso.

### US-P1-07: Anexar exame
**Descrição:** Como recepcionista ou dentista, quero anexar uma radiografia ou foto ao paciente.

**Critérios de aceite:**
- [ ] `POST /api/v1/patients/{id}/files` (D, R), `multipart/form-data` com o arquivo e `kind` (`RADIOGRAPH`, `INTRAORAL_PHOTO`, `OTHER`).
- [ ] Rejeita com 400/413 arquivo vazio, acima de 10 MB, tipo fora da lista ou cujos bytes iniciais não correspondem ao tipo declarado.
- [ ] O backend gera a chave `<clinic_id>/<patient_id>/<uuid>`, envia o objeto ao Storage e só então grava os metadados; se a gravação do banco falhar, remove o objeto enviado (sem órfão).
- [ ] Nome original é sanitizado (sem caminho, sem caracteres de controle, até 255).
- [ ] Resposta 201 com **apenas** `{id}` para ambos os papéis: o `INSERT` da recepção não usa `RETURNING` nem relê a linha (RLS de `SELECT` nega).
- [ ] Paciente inexistente na clínica retorna 404; anonimizado, 409.
- [ ] 11º upload no mesmo minuto pelo mesmo usuário retorna 429 com `Retry-After`.

### US-P1-08: Listar, baixar e remover exames
**Descrição:** Como dentista, quero ver os exames do paciente, baixá-los e removê-los da lista.

**Critérios de aceite:**
- [ ] `GET /api/v1/patients/{id}/files` (só D) lista exames ativos (id, kind, nome original, tipo, tamanho, data), mais recentes primeiro, sem expor `storage_key`.
- [ ] `GET /api/v1/patients/{id}/files/{fileId}/download-url` (só D) retorna `{url, expiresIn}` com validade de 60 s; exame removido, de outro paciente ou de outra clínica retorna 404.
- [ ] `DELETE /api/v1/patients/{id}/files/{fileId}` (só D) faz remoção lógica (`deleted_at`) e responde 204; o objeto permanece no Storage.
- [ ] Antes de assinar, o backend confere que a chave do registro começa com `<clinic_id>/` do token.
- [ ] Recepcionista recebe 403 em lista, download e remoção.

### US-P1-09: Anonimizar paciente (LGPD)
**Descrição:** Como dentista, quero anonimizar os dados pessoais de um paciente quando a retenção legal vencer ou quando não houver prontuário.

**Critérios de aceite:**
- [ ] `POST /api/v1/patients/{id}/anonymize` (só D) chama `core.anonymize_patient`; sucesso retorna 200 com `{anonymizedAt, storageCleanup}` (`COMPLETED` ou `PENDING`).
- [ ] Erros do banco traduzidos: `CR001` (paciente ativo) → 409 "inative antes"; `CR002` (retenção vigente) → 409 com a data de liberação; `CR003` (já anonimizado) → 409; `P0002` → 404; `42501` → 403.
- [ ] Antes da chamada, o backend lista as `storage_key` do paciente; **depois do commit**, apaga os objetos no Storage. Falha no Storage não desfaz a anonimização: retorna `storageCleanup: PENDING` e registra evento de segurança (sem chave nem nome).
- [ ] Depois da operação nenhuma coluna pessoal mantém o valor original (conferido por IT) e o paciente não pode mais ser editado nem receber registros clínicos.

### US-P1-10: Consultar trilha de auditoria
**Descrição:** Como dentista, quero saber quem criou ou alterou dados clínicos de um paciente.

**Critérios de aceite:**
- [ ] `GET /api/v1/audit-log?tableName&recordId&page&size` (só D), somente leitura, paginado (`size` máx. 50), mais recente primeiro.
- [ ] Cada item traz `userId`, `userName`, `tableName`, `recordId`, `action` (`INSERT`, `UPDATE`, `DELETE`, `ANONYMIZE`) e `occurredAt`; **nunca** valores de colunas.
- [ ] Não existe nenhum endpoint de escrita na auditoria; a entidade é `@Immutable`.
- [ ] Recepcionista recebe 403; registros de outra clínica nunca aparecem.

### US-P1-11: Papel e tenant na sessão do banco
**Descrição:** Como responsável pelo produto, quero que o banco reforce o papel e a autoria mesmo que o backend erre.

**Critérios de aceite:**
- [ ] Toda transação autenticada define `app.clinic_id`, `app.role` e `app.user_id` com `set_config(..., true)`, a partir do JWT validado, em um único componente.
- [ ] Teste com pool de 1 conexão: os três valores não vazam entre requisições.
- [ ] Toda gravação clínica carrega `created_by = app.user_id`; sem `app.user_id` a gravação falha (`28000`) e a API responde 500 `internal` sem detalhes.
- [ ] Teste com `app.role` ausente ou inválido: tudo negado nas tabelas clínicas, inclusive `patients`.

---

## 4. Requisitos funcionais

- **FR-1:** O sistema deve permitir cadastrar, buscar, editar, inativar e reativar pacientes por `DENTIST_ADMIN` e `RECEPTIONIST` (inativar/reativar só `DENTIST_ADMIN`).
- **FR-2:** O sistema deve buscar pacientes por trecho do nome ou por CPF, paginado, ordenado e com `size` ≤ 50.
- **FR-3:** O sistema deve validar no servidor CPF (dígito verificador), telefone, e-mail, data de nascimento, tamanhos de texto e coerência convênio/conhecido, devolvendo todos os erros de uma vez.
- **FR-4:** O sistema deve permitir histórico médico (um por paciente) e notas por atendimento **somente** a `DENTIST_ADMIN`, cifrados com AES-256-GCM antes de chegar ao banco.
- **FR-5:** O sistema deve permitir anexar exames a `DENTIST_ADMIN` e `RECEPTIONIST`, e listar, baixar e remover logicamente apenas a `DENTIST_ADMIN`.
- **FR-6:** O sistema deve validar o arquivo de exame por tamanho (≤ 10 MB), tipo permitido e assinatura de bytes, e gerar sozinho a chave `<clinic_id>/<patient_id>/<uuid>`.
- **FR-7:** O sistema deve guardar os arquivos no Supabase Storage (bucket privado) por trás de `FileStorage` e entregar o download por URL assinada de 60 s, nunca por bucket público.
- **FR-8:** O sistema não deve apagar fisicamente paciente, prontuário nem metadados de exame; a remoção é lógica e a eliminação é a anonimização.
- **FR-9:** O sistema deve expor a anonimização do paciente a `DENTIST_ADMIN`, apagar os objetos do Storage depois do commit e traduzir os erros `CR001`, `CR002`, `CR003`, `P0002` e `42501` em respostas `ProblemDetail`.
- **FR-10:** O sistema deve expor a consulta de auditoria, somente leitura, a `DENTIST_ADMIN`.
- **FR-11:** O sistema deve definir `app.clinic_id`, `app.role` e `app.user_id` em toda transação autenticada e falhar fechado quando faltar algum.
- **FR-12:** O sistema deve negar por papel no servidor (`SecurityConfig` e `@PreAuthorize`) antes de qualquer acesso ao banco, e nunca aceitar `clinicId`, `createdBy` ou `role` do cliente.
- **FR-13:** O sistema deve devolver `{id}` apenas na criação de exame e não usar `RETURNING`, `ON CONFLICT` nem recarga da linha nesse caminho.
- **FR-14:** O sistema deve aplicar controle otimista (`version`) em paciente, histórico e notas e responder 409 em conflito.
- **FR-15:** O sistema deve limitar uploads a 10 por minuto por usuário (429 com `Retry-After`).
- **FR-16:** O sistema deve manter conteúdo clínico, CPF, nome de paciente e URL assinada fora de logs, `toString` e mensagens de erro.
- **FR-17:** Plataforma: mapear as entidades com `ddl-auto=validate` contra V001–V013 (`@Version Long`, `@Enumerated(STRING)`, `@Convert(EncryptedStringConverter)`, `updatable=false` nas colunas imutáveis, `anonymized_at` com `insertable=false, updatable=false`, `audit_log` como `@Immutable`); configurar `CRM_STORAGE_URL`, `CRM_STORAGE_SERVICE_KEY` e `CRM_STORAGE_BUCKET` com falha na subida se ausentes em `prod`.

---

## 5. Non-goals (fora de escopo)

- Avatar do perfil (E2) e qualquer migration nova (a função `banco` não é acionada nesta fase).
- Agenda, agendamento e `appointment_id` nas notas (Fase 2); orçamento, financeiro e documentos (Fases 3–4).
- Odontograma estruturado, evolução por dente, assinatura digital, registro de consentimento LGPD e responsável legal de menor.
- Cifrar o binário dos exames (E1), antivírus, miniaturas, pré-visualização no servidor e arquivos DICOM.
- Busca em campos cifrados, busca por telefone ou e-mail e busca insensível a acentos.
- Exclusão física de paciente, prontuário ou exame; expurgo automático agendado por retenção.
- Auditoria de **leitura** do prontuário e comparação de valores antigo/novo.
- Rotação automática da chave AES (a tarefa de recriptografia gradual fica fora; o `KeyRing` já suporta versões).
- Exportação de dados do titular (acesso e portabilidade) como endpoint dedicado; fica como pergunta aberta.
- Frontend (consumidor desta API; ainda não iniciado) e conciliação automática de objetos órfãos do Storage.

## 6. Requisitos de IA

Não se aplica.

---

## 7. Especificações técnicas e considerações de design

**Arquitetura (fluxo):**
```
React ─▶ /api/v1/** ─▶ filtro JWT (clinic_id, user_id, role) ─▶ controller ─▶ service (@PreAuthorize, @Transactional)
                                                              │
                          transação: set_config(app.clinic_id, app.role, app.user_id, true) + filtro Hibernate
                                                              │
        patient ─▶ core.patients ◀─ RLS por clínica e papel ─▶ core.audit_log (trigger, append-only)
        record  ─▶ core.medical_histories / clinical_notes (conteúdo cifrado pela aplicação)
                ─▶ core.patient_files (metadados) ─▶ FileStorage ─▶ Supabase Storage (bucket privado)
```

**Pontos de integração:**
- **Banco (contrato, spec do banco seção 6):** nomes de tabelas, variáveis de sessão, `core.anonymize_patient(uuid)` e SQLSTATE. Mapeamento de entidades na seção 6.2 daquela spec. As migrations V001–V013 já estão aplicadas no Supabase real.
- **Fase 0:** `TenantContext`/`TenantHibernateJpaDialect` (estendido), `AesGcmCipher`/`EncryptedStringConverter`/`KeyRing`, `GlobalExceptionHandler`, `PageResponse`, `Role`, `TokenPrincipal`, `FileStorage` e `CrmProperties.Storage`.
- **Supabase Storage:** API REST com a service key; criação do bucket (privado, 10 MB, 4 tipos) é manual (checklist no `database/README.md`, ação do usuário).

**Segurança e privacidade:**
- Dados de saúde são dados pessoais sensíveis (LGPD). Projeto de portfólio com dados fictícios: riscos de produção real aceitos; se houver pacientes reais, revisar retenção (20 anos, a confirmar com assessoria), cifra de arquivos (E1), backup e base legal.
- O papel vem **só** do JWT validado; ponto único de definição das variáveis de sessão, com teste de integração (risco 1 do PRD do banco).
- A service key do Storage é segredo só do backend; o isolamento dos arquivos depende do prefixo `<clinic_id>/` montado pelo backend (a RLS não protege o Storage).
- Logs sem conteúdo clínico, CPF, nome do paciente, nome do arquivo, chave do objeto ou URL assinada; correlação por `X-Request-Id`.
- `Content-Disposition` e `Content-Type` do download vêm do registro e da lista permitida; nome do arquivo sanitizado.

**UI/UX:** não se aplica (API). O contrato de erro e de paginação segue a Fase 0 para o front consumir sem exceções.

---

## 8. Riscos e perguntas abertas

**Riscos técnicos**
1. **`app.role` forjável pelo backend.** O banco confia no valor informado; bug ou injeção que o altere contorna a separação. *Mitigação:* valor só do JWT, definido em um ponto, com IT que prova o comportamento (limite aceito da arquitetura de papel único `crm_app`).
2. **Inserção de exame pela recepção e o Hibernate.** Com `id` atribuído e `@Version`, `repository.save()` pode fazer `SELECT` prévio (merge) e `RETURNING`/recarga, que a RLS nega à recepção. *Mitigação:* persistir com `EntityManager.persist` (ou entidade `Persistable`), sem `flush` que releia, e IT cobrindo o papel `RECEPTIONIST`.
3. **Consistência Storage × banco.** Upload e gravação não são atômicos. *Mitigação:* enviar o objeto primeiro, gravar depois e compensar apagando o objeto em falha; na anonimização, apagar depois do commit. Objetos órfãos remanescentes não têm rotina de conciliação (non-goal), apenas evento de log.
4. **Cota do plano gratuito do Storage** (1 GB no total). Cada exame pode ter até 10 MB. Mitigação: limite de 10 MB, rate limit e monitoramento manual; sem cota por clínica nesta fase.
5. **`FileStorage` hoje não gera URL assinada** e o Javadoc diz "sem implementação nesta fase". É mudança de interface em `shared` (compatível, pois ainda não há implementação).
6. **Perda da chave AES** torna histórico e notas irrecuperáveis (risco 5 do PRD da Fase 0); backup das chaves fora do repositório e do banco.
7. **Auditoria que falha bloqueia a operação** (sem `app.user_id` a gravação clínica falha). Intencional; cada escrita custa uma linha de log.
8. **`statement_timeout` de 15 s** e a anonimização de paciente com muitas notas; volume esperado (dezenas de linhas) cabe com folga.
9. **Busca com `LIKE` sob RLS** não usa os índices trigram/prefixo (`LIKE` não é leakproof); medido ≈ 5 ms com 10 mil pacientes, dentro da meta de 100 ms. Reavaliar se o volume crescer.
10. **Reinício da retenção por edição.** `UPDATE` em registro clínico reinicia os 20 anos; isso é consequência do cálculo no banco (S2) e precisa constar na documentação da API.
11. **Dumps do `crm_backup`** guardam dados anonimizados por até 30 dias; a política de privacidade deve citar essa janela.

**Conflitos e lacunas sinalizados**
- O `AGENTS.md` da raiz está vazio; stack, papéis e fases foram lidos de `arq_inicial.md` e dos PRDs/specs. Vale preenchê-lo.
- O PRD da Fase 0 listava o avatar como "Fase 1 (depende do armazenamento)"; por decisão do usuário (E2) ele vai para o backlog, sem migration.
- O PRD do banco diz que o limite do arquivo e dos textos é "coordenado com o backend": este PRD confirma os valores da S5 (10.000 / 5.000 caracteres e 10 MB).

**Perguntas abertas**
- Exportação de dados do titular (acesso e portabilidade): endpoint dedicado agora ou só sob demanda manual?
- Prazo de 20 anos de guarda: validar com assessoria jurídica/CFO antes de qualquer uso com dados reais.
- Armazenamento do refresh token no React segue pendente da spec do frontend (herdado).

**Lançamento em etapas**
- **MVP (Fase 1):** US-P1-01 a US-P1-11.
- **v1.1:** vínculo da nota com agendamento (`appointment_id`, Fase 2); exportação de dados do titular; conciliação de objetos órfãos.
- **v2.0:** auditoria de leitura, expurgo agendado por retenção, registro de consentimento, cifra de arquivos se houver dados reais.

**Fatiamento sugerido para o `breakdown` (referência, a confirmar após aprovação):** (1) sessão do banco e plataforma (tenant com `app.role`/`app.user_id`, mapeamento `validate`, erros SQLSTATE); (2) pacientes (cadastro, busca, edição, inativação); (3) prontuário cifrado (histórico e notas); (4) exames e `SupabaseFileStorage`; (5) auditoria e anonimização; (6) verificação da fase (matriz de papéis, isolamento com filtro desligado, desempenho, log limpo).
