# PRD — F01 CRO e plataforma da Fase 4

**Derivação:** PRD global da Fase 4, US-P4-01 (CRO) e os requisitos transversais (FR-1, FR-12 a FR-15, riscos); decisões I8 (corrigida por J1), I9 e I11. Spec global, seções 2.2, 4.6, 6, 7.1, 8 e 10 (divergências 1 a 3).
**Dependências externas:** Fase 0 (`User`, `ProfileService`, `MeController`, `SecurityConfig`, `GlobalExceptionHandler`, `ConflictMessages`, `CrmProperties`), Fase 1 (`AuditAction`, `AuditQueryService`), banco V031 (`users.cro_number`, `users.cro_state`, trigger `guard_user_cro`) e V033 (`ck_audit_action`/`ck_audit_table`). Requer o access token da Fase 0.

## 1. Introdução

- **Problema:** atestado, receita e encaminhamento exigem o CRO do dentista, e as demais features da Fase 4 precisam de rotas, mensagens de erro, auditoria e configuração comuns que ainda não existem.
- **Solução:** gravar o CRO em rota própria (`PUT /me/professional-registry`), expô-lo em `GET /me` e entregar a base compartilhada (identidade profissional, enums de auditoria, traduções de erro, propriedades, regras de rota e teste de arquitetura).
- **Metas:**
  1. Dentista grava, troca e apaga o CRO; recepção recebe 403 na rota e `null` em `GET /me`.
  2. Um `PUT /me` só com nome e e-mail nunca altera o CRO.
  3. `AuditAction.VOID`/`DOWNLOAD` e a tabela `clinical_documents` aparecem na consulta de auditoria.
  4. Cobertura ≥ 80% em `ProfileService` (parte nova), `ProfessionalIdentityService` e nas validações novas.

## 2. User stories

### US-001: Cadastrar o CRO
**Descrição:** Como dentista, quero informar meu CRO e a UF para que meus documentos clínicos saiam com o registro profissional.

**Critérios de aceite:**
- [ ] `PUT /api/v1/me/professional-registry` (só D) com `{cro, croState, version}`: `cro` com 1 a 15 dígitos, `croState` uma das 27 UFs em maiúsculas; `version` obrigatória.
- [ ] Os dois campos `null` apagam o CRO; um preenchido e o outro `null` → 400 com `errors[]`.
- [ ] `version` defasada → 409; devolve 200 com `UserResponse` atualizado (`cro`, `croState`, nova `version`).
- [ ] Recepção → 403; sem token → 401.
- [ ] `PUT /me` (nome, e-mail, senha) não lê nem altera `cro`/`croState`.
- [ ] `GET /me` devolve `cro` e `croState` (nulos para recepção e para dentista sem CRO).

### US-002: Identidade profissional para outros módulos
**Descrição:** Como módulo `document`, quero saber nome, CRO e UF do dentista sem tocar no repositório de `auth`.

**Critérios de aceite:**
- [ ] `ProfessionalIdentity.current()` e `of(userId)` devolvem `(name, cro, croState)` com `cro`/`croState` nulos quando não há CRO.
- [ ] `of(userId)` respeita o filtro de tenant: usuário de outra clínica → vazio.

### US-003: Base transversal
**Descrição:** Como as features seguintes, quero as peças comuns prontas.

**Critérios de aceite:**
- [ ] `AuditAction` ganha `VOID` e `DOWNLOAD`; `GET /audit-log?tableName=clinical_documents` é aceito.
- [ ] `CR012` → 409 `conflict` (`PROFESSIONAL_REGISTRY_MISSING`); `IntegrityException` → 500 `.../problems/integrity` com `detail` genérico; `23514` residual de `ck_cdoc_*` → 500 com a constraint só no log.
- [ ] `CrmProperties.ClinicalDocuments(issuesPerMinute, pdfsPerMinute)` com padrão 30/30; `StartupConfigValidator` rejeita fora de 1 a 600.
- [ ] `SecurityConfig` protege as rotas novas conforme o contrato; `Phase4ArchitectureTest` falha em ciclo ou acesso indevido a repositório de outro módulo.

## 3. Requisitos funcionais

- **FR-1:** `User` ganha `cro_number` (`length=15`) e `cro_state` (`length=2`), nulos, e `registerProfessional(cro, state)`.
- **FR-2:** `ProfessionalRegistryRequest` com `@Pattern`/`@ValidProfessionalRegistry` (ambos ou nenhum) e `ProfileService.registerProfessional` com `saveAndFlush`.
- **FR-3:** `UserResponse` ganha `cro` e `croState`; nenhuma resposta traz `clinicId`.
- **FR-4:** `ProfessionalIdentity` (interface pública em `auth.service`) + `ProfessionalIdentityService`.
- **FR-5:** `ConflictMessages` ganha `PROFESSIONAL_REGISTRY_MISSING`, `DOCUMENT_STATE`, `DOCUMENT_ANONYMIZED`; `IntegrityException` em `shared.error`.
- **FR-6:** `GlobalExceptionHandler` traduz `CR012`, `IntegrityException` e `23514` residual conforme o contrato.
- **FR-7:** `SecurityConfig`: `hasRole("DENTIST_ADMIN")` em `/api/v1/patients/*/clinical-documents`, `/api/v1/clinical-documents/**` e `PUT /api/v1/me/professional-registry`; `/api/v1/dashboard` para os dois papéis.
- **FR-8:** Teste de arquitetura `Phase4ArchitectureTest` e `.env.example` com as duas propriedades opcionais.

## 4. Fora do escopo

- Emissão, leitura, anulação e PDF de documentos (F02 a F04); dashboard (F05).
- Validação do CRO junto ao conselho profissional; vários dentistas por clínica.
- Histórico de alterações do CRO além da auditoria que já existe para `users`.

## 5. Considerações técnicas e riscos

- Campo omitido e `null` são indistinguíveis no JSON (`JsonStrictnessConfig`); por isso a rota própria (divergência 1 do PRD global).
- O trigger `guard_user_cro` só deixa o próprio usuário alterar o CRO; o id vem do token, então `42501` aqui é bug (500).
- `IntegrityException` fica em `shared.error` (não em `document`) para o handler global não depender do módulo de documentos.
