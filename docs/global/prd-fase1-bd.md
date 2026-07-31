# PRD de Banco de Dados & Cibersegurança — Fase 1 (Núcleo Clínico)

**Documento:** Product Requirements Document (PRD) de Banco de Dados & Segurança (Supabase / PostgreSQL)  
**Projeto:** SaaS Clínica Dental Solo (CRM Clínica Dental)  
**Fase:** Fase 1 — Núcleo Clínico  
**Autor:** Arquiteto de Banco de Dados e Cibersegurança Sênior (`supabase-sec-dba`)  
**Status:** Em Revisão / Aguardando Aprovação Humana  
**Documentos de Origem:** `docs/workflow.md`, `docs/fase1_detalhada.md`, `docs/Fase-0.md`, `docs/global/prd.md`, `docs/global/spec.md`  

---

## 1. Resumo Executivo & Declaração do Problema

### 1.1 Problema
A **Fase 0** do projeto estabeleceu o modelo base multi-tenant (`clinics`, `users`) com 2 papéis estáticos (`admin` e `receptionist`). A **Fase 1 (Núcleo Clínico)** introduz o gerenciamento de dados altamente sensíveis de pacientes (prontuário médico, odontograma, evoluções clínicas e exames radiográficos).

Sem um planejamento rigoroso na camada de banco de dados:
1. **Risco de Vazamento de Dados Médicos/LGPD:** Dados de prontuário, CPF, diagnósticos e queixas em texto claro podem expor a clínica a penalidades da LGPD e violações de privacidade.
2. **Bypass de Tenant (Cross-Clinic Access):** Falhas em queries ou falta de Row Level Security (RLS) no PostgreSQL podem permitir que uma clínica acesse prontuários de outra.
3. **Violação de Privilégios Internos (RBAC):** Usuários do papel `receptionist` (Recepcionista) não podem ter acesso de leitura nem escrita às anotações médicas (`clinical_notes`) e ao odontograma (`teeth_status`/`teeth_history`), devendo visualizar apenas dados demográficos essenciais.
4. **Violabilidade do Histórico do Odontograma:** O histórico de evoluções e alterações dentárias não pode ser sobrescrito nem apagado (`UPDATE`/`DELETE` indevidos), exigindo imutabilidade por design no banco.

### 1.2 Solução Proposta
Engenhar a arquitetura de dados da Fase 1 no Supabase/PostgreSQL com:
- **Tabelas de Domínio Clínico:** `patients`, `teeth_status`, `teeth_history`, `clinical_notes`, `patient_files`.
- **Row Level Security (RLS) Absoluto:** Habilitação obrigatória de RLS em 100% das tabelas da Fase 1, reforçando o isolamento por `clinic_id` extraído do JWT (`auth.jwt() -> 'app_metadata' ->> 'clinic_id'`).
- **RBAC Estrito na Camada de Dados:**
  - `admin` (Dentista Solo): Acesso total (CRUD) aos pacientes, odontograma, notas clínicas e exames.
  - `receptionist` (Recepcionista): Acesso exclusivo de leitura/criação/edição em dados cadastrais de `patients` (nome, telefone, convênio). Bloqueio total em `clinical_notes`, `teeth_status`, `teeth_history` e `patient_files`.
- **Imutabilidade Auditada no Odontograma:** Restrição de RLS/Triggers que impedem `UPDATE` e `DELETE` na tabela `teeth_history`, forçando modelo *append-only*.
- **Estratégia de Criptografia (Camada de Dados / PGCRYPTO / Backend):** Definir suporte no esquema para colunas criptografadas (AES-256 / PGP) e restrições de permissão.

### 1.3 Objetivos e Métricas de Sucesso
- **[DB-01] Cobertura de RLS:** 100% das tabelas da Fase 1 com `ENABLE ROW LEVEL SECURITY` e políticas para cada operação (`SELECT`, `INSERT`, `UPDATE`, `DELETE`).
- **[DB-02] Isolamento Tenant Zerotrust:** 0% de vazamento entre clínicas (`clinic_id`) em testes automatizados de políticas RLS.
- **[DB-03] Proteção RBAC:** 100% de bloqueio para o papel `receptionist` ao tentar acessar dados clínicos restritos (`clinical_notes`, `teeth_status`, `teeth_history`).
- **[DB-04] Imutabilidade do Odontograma:** 100% de rejeição pelo PostgreSQL em tentativas de alteração ou exclusão em `teeth_history`.

---

## 2. Histórias de Usuário (User Stories de Banco & Segurança)

### US-DB-101: Isolamento Tenant de Pacientes e Prontuários
**Descrição:** Como Dentista Admin, quero que todos os registros de pacientes e prontuários da minha clínica sejam visíveis apenas por usuários autenticados da minha própria clínica.

**Critérios de Aceite:**
- [ ] Tabela `patients` e tabelas filhas possuem coluna `clinic_id` indexada com constraint FK para `clinics(id)`.
- [ ] Política RLS garante que `SELECT`, `INSERT`, `UPDATE` e `DELETE` em `patients` filtrem estritamente por `auth.jwt() ->> 'clinic_id'`.
- [ ] Tentativas de acesso com token de outra clínica retornam resultado vazio ou erro de violação de RLS.

### US-DB-102: Proteção de Acesso ao Odontograma e Prontuário Clínico (RBAC)
**Descrição:** Como Dentista Admin, quero que recepcionistas consigam cadastrar e buscar pacientes, mas fiquem completamente impedidas de visualizar anotações médicas, odontogramas e radiografias.

**Critérios de Aceite:**
- [ ] Políticas RLS para `clinical_notes`, `teeth_status`, `teeth_history` e `patient_files` exigem explicitamente `auth.jwt() ->> 'role' = 'admin'`.
- [ ] Usuário com role `receptionist` recebe negação de acesso (0 linhas retornadas / HTTP 403 / RLS Violation) ao tentar ler ou alterar tabelas clínicas.
- [ ] Usuário com role `receptionist` consegue listar e cadastrar dados básicos na tabela `patients`.

### US-DB-103: Imutabilidade do Histórico do Odontograma (Append-Only)
**Descrição:** Como sistema/audit, quero que as alterações no mapa dental do paciente gerem registros históricos que nunca possam ser alterados ou apagados.

**Critérios de Aceite:**
- [ ] Tabela `teeth_history` possui política RLS permitindo apenas `SELECT` e `INSERT`.
- [ ] Operações de `UPDATE` e `DELETE` em `teeth_history` são bloqueadas via política RLS sem permissão ou via Trigger de Segurança que dispara exceção.

---

## 3. Requisitos Funcionais de Banco de Dados (Functional Requirements)

- **FR-DB-1:** O schema deve criar as 5 tabelas do domínio da Fase 1: `patients`, `teeth_status`, `teeth_history`, `clinical_notes`, `patient_files`.
- **FR-DB-2:** Todas as tabelas da Fase 1 devem conter chave primária do tipo `UUID DEFAULT gen_random_uuid()` e timestamping de `created_at` e `updated_at` com `TIMESTAMPTZ`.
- **FR-DB-3:** A tabela `patients` deve implementar Soft Delete através da coluna `deleted_at TIMESTAMPTZ`, e as políticas RLS de `SELECT` devem ocultar automaticamente registros deletados (`deleted_at IS NULL`).
- **FR-DB-4:** O padrão numérico dos dentes na tabela `teeth_status` e `teeth_history` deve validar a faixa da notação internacional FDI (11 a 48 para dentes permanentes e 51 a 85 para dentes decíduos) via `CHECK constraint`.
- **FR-DB-5:** Os campos `face` no odontograma devem validar os valores permitidos (`MESIAL`, `DISTAL`, `OCLUSAL`, `INCISAL`, `VESTIBULAR`, `PALATINA`, `LINGUAL`, `GERAL`) via `CHECK constraint` ou tipo `ENUM`.
- **FR-DB-6:** Os campos sensíveis `cpf_encrypted`, `phone_encrypted`, `notes_encrypted`, `chief_complaint_encrypted` e `diagnosis_encrypted` devem ser armazenados em tipo `TEXT` compatível com o padrão AES-256 / PGP.
- **FR-DB-7:** A integridade referencial com exclusão em cascata (`ON DELETE CASCADE`) deve ser aplicada a partir de `patients` para todas as tabelas clínicas filhas (`teeth_status`, `teeth_history`, `clinical_notes`, `patient_files`).

---

## 4. Não-Objetivos (Fora do Escopo da Fase 1)

- **Modelagem de Agendamentos e Calendários:** Pertence à Fase 2 (Agenda).
- **Modelagem de Procedimentos, Tabela de Preços e Orçamentos:** Pertence à Fase 3 (Comercial/Financeiro).
- **Modelagem de Parcelamento, Contas a Receber e Fluxo de Caixa:** Pertence à Fase 3 (Financeiro).
- **Modelagem de Templates de Atestados e Receituários:** Pertence à Fase 4 (Documentos).
- **Triggers de integração automática com WhatsApp:** Pertence à Fase 2.

---

## 5. Especificações Técnicas e Design de Segurança (Supabase & PostgreSQL)

### 5.1 Arquitetura de RLS & Helper Functions
Para otimizar a legibilidade e performance das políticas RLS, serão especificadas funções auxiliares `STABLE` / `SECURITY DEFINER` registradas no PostgreSQL:

1. `current_clinic_id()`: Retorna o `UUID` da clínica contido no token JWT.
2. `current_user_role()`: Retorna a `role` (`admin` ou `receptionist`) contida no token JWT.
3. `is_admin()`: Retorna boolean informando se o usuário é `admin`.

### 5.2 Estratégia de Migração e Compatibilidade com a Fase 0
- As novas tabelas da Fase 1 conectam-se diretamente às tabelas `clinics` e `users` criadas na Fase 0.
- A migração deve criar os novos tipos ENUM (se aplicável), tabelas, índices de performance (`clinic_id`, `patient_id`, `tooth_number`), triggers de `updated_at` e habilitar o RLS de forma atômica.

---

## 6. Riscos & Questões Abertas

### 6.1 Riscos Técnicos
- **Risco:** Latência em consultas com RLS complexo ou chamadas repetidas a `auth.jwt()`.
  - *Mitigação:* Utilizar funções `STABLE` personalizadas para cache no escopo da transação SQL e garantir índices compostos `(clinic_id, patient_id)` em todas as tabelas filhas.
- **Risco:** Injeção de permissões indevidas via modificação de `app_metadata` no cliente.
  - *Mitigação:* O token JWT é assinado digitalmente pelo Supabase Auth. Quaisquer mutações no cliente invalidam a assinatura do JWT no PostgreSQL.

---

## 7. Próximos Passos (Workflow Supabase Sec DBA)

Conforme as regras inegociáveis e o workflow de skills do **Supabase Sec DBA**, a documentação e planejamento devem seguir a sequência obrigatória com **parada para aprovação humana entre cada etapa**:

1. **[ATUAL] Etapa 1 (`prd`):** Aprovação deste **PRD de Banco de Dados & Cibersegurança — Fase 1** (`docs/global/prd-fase1-bd.md`).
2. **Etapa 2 (`spec-write`):** Elaboração da Especificação Técnica Global de Banco de Dados da Fase 1 (`docs/global/spec-fase1-bd.md`).
3. **Etapa 3 (`breakdown`):** Fracionamento dos domínios de dados da Fase 1 em subpastas (`docs/features/patients`, `docs/features/odontogram`, `docs/features/clinical_records`).
4. **Etapa 4 (`spec-write`):** Especificação técnica detalhada por domínio de dados da Fase 1.
5. **Etapa 5 (`contract`):** Definição dos Contratos de Dados, DDLs SQL finalizados, Políticas RLS completas e Funções RPC (`contract.md`).
