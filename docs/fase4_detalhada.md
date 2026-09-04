# Especificação Detalhada: Fase 4 — Documentos Odontológicos & Dashboard

**Projeto:** SaaS de Gestão — Clínica Dental (Dentista Solo)  
**Documento Relacionado:** [workflow.md](file:///C:/IgnisPath/Crm-Clinica/docs/workflow.md)  
**Data:** 2026-09-04  

---

## 1. Visão Geral da Fase 4

A **Fase 4 (Documentos Odontológicos & Dashboard)** conclui as funcionalidades principais do MVP do dentista solo:
1. **Emissão Simplificada de Documentos Clínicos**:
   - **Atestado Odontológico**: Dias de afastamento, CID opcional, motivo e data.
   - **Receituário Médico**: Medicamentos, dosagem, posologia e orientações.
   - **Encaminhamento**: Encaminhamento para especialista (ex: Endodontista, Bucomaxilo) ou exames complementares.
   - Impressão e geração de PDF com cabeçalho da clínica.
2. **Dashboard Executivo da Clínica**:
   - KPIs de total de pacientes, consultas do dia, faturamento mensal (R$), taxa de retorno (%) e cancelamentos (%).
   - Gráfico de barras semanal de consultas e faturamento por dia.

---

## 2. Modelagem de Banco de Dados (PostgreSQL / GORM)

### Tabela `medical_documents` (Documentos Odontológicos)
- `id` (UUID / uint, PK)
- `clinic_id` (UUID / uint, FK -> `clinics.id`)
- `patient_id` (UUID / uint, FK -> `patients.id`)
- `dentist_id` (UUID / uint, FK -> `users.id`)
- `type` (VARCHAR(50), NOT NULL) — `ATESTADO`, `RECEITA`, `ENCAMINHAMENTO`
- `title` (VARCHAR(255), NOT NULL)
- `content_encrypted` (TEXT, NOT NULL) — Criptografia AES-256 no corpo do documento
- `created_at`, `updated_at`

---

## 3. Endpoints Backend (Go / Fiber)

| Método | Endpoint | Descrição |
|---|---|---|
| `GET` | `/api/v1/patients/:id/documents` | Lista documentos do paciente |
| `POST` | `/api/v1/patients/:id/documents` | Cria novo documento (atestado, receita, encaminhamento) |
| `GET` | `/api/v1/documents/:id` | Retorna o documento descriptografado para o usuário autorizado |
| `DELETE` | `/api/v1/documents/:id` | Exclui documento |
| `GET` | `/api/v1/dashboard/stats` | Retorna KPIs da clínica, faturamento, retorno e dados semanais |

---

## 4. Frontend & Telas (Flutter / Riverpod)

1. **Aba de Documentos Clínicos no Prontuário (`features/patients/presentation/widgets/patient_documents_tab.dart`)**:
   - Formulários com preenchimento rápido (templates de Atestado, Receita e Encaminhamento).
   - Gerador de PDF em tempo real com o pacote `printing` e `pdf`.
2. **Dashboard Principal (`features/dashboard/presentation/dashboard_screen.dart`)**:
   - Consumo do provider `dashboardStatsProvider`.
   - Cards de estatísticas clínicas/financeiras e gráfico de distribuição semanal.
