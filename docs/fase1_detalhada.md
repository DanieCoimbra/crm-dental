# Especificação Detalhada: Fase 1 — Núcleo Clínico

**Projeto:** SaaS de Gestão — Clínica Dental (Dentista Solo)  
**Documento Relacionado:** [workflow.md](file:///C:/Users/progc/Trabalho/Dentista/Crm-Clinica/docs/workflow.md)  
**Data:** 2026-07-31  

---

## 1. Visão Geral da Fase 1

A **Fase 1 (Núcleo Clínico)** é a espinha dorsal da aplicação de atendimento. O objetivo é permitir que o dentista cadastre pacientes, mantenha o prontuário atualizado, registre evoluções clínicas, interaja de forma visual com o odontograma e faça gestão de anexos (radiografias e exames).

---

## 2. Modelagem de Banco de Dados & Criptografia (PostgreSQL / GORM)

Todas as entidades desta fase possuem obrigatoriamente a FK `clinic_id` para garantir o isolamento **Multi-tenant**.

### 2.1 Tabela `patients`
- `id` (UUID, Primary Key)
- `clinic_id` (UUID, FK -> `clinics.id`, Index)
- `full_name` (VARCHAR(150), NOT NULL)
- `cpf_encrypted` (TEXT, AES-256, NOT NULL)
- `phone_encrypted` (TEXT, AES-256, NOT NULL)
- `email` (VARCHAR(150))
- `birth_date` (DATE)
- `health_insurance` (VARCHAR(100)) — Convênio
- `notes_encrypted` (TEXT, AES-256) — Notas gerais de anamnese/alergias
- `created_at` (TIMESTAMPTZ)
- `updated_at` (TIMESTAMPTZ)
- `deleted_at` (TIMESTAMPTZ, Soft Delete Index)

### 2.2 Tabela `teeth_status` (Estado Atual do Odontograma)
- `id` (UUID, Primary Key)
- `clinic_id` (UUID, FK -> `clinics.id`)
- `patient_id` (UUID, FK -> `patients.id`, Index)
- `tooth_number` (INT, NOT NULL) — Padrão FDI (11 a 48 para dentes adultos / 51 a 85 para dentes decíduos)
- `face` (VARCHAR(20), NOT NULL) — Valores: `MESIAL`, `DISTAL`, `OCLUSAL`, `INCISAL`, `VESTIBULAR`, `PALATINA`, `LINGUAL`, `GERAL`
- `condition` (VARCHAR(50), NOT NULL) — Valores: `HIGIDO`, `CARIADO`, `RESTAURADO`, `EXTRAIDO`, `IMPLANTE`, `TRATAMENTO_CANAL`, `COROA_PROTESE`, `EM_TRATAMENTO`
- `updated_by` (UUID, FK -> `users.id`)
- `updated_at` (TIMESTAMPTZ)

### 2.3 Tabela `teeth_history` (Log Imutável do Odontograma)
- `id` (UUID, Primary Key)
- `clinic_id` (UUID, FK -> `clinics.id`)
- `patient_id` (UUID, FK -> `patients.id`, Index)
- `teeth_status_id` (UUID, FK -> `teeth_status.id`)
- `tooth_number` (INT, NOT NULL)
- `face` (VARCHAR(20))
- `previous_condition` (VARCHAR(50))
- `new_condition` (VARCHAR(50), NOT NULL)
- `notes` (TEXT)
- `created_by` (UUID, FK -> `users.id`)
- `created_at` (TIMESTAMPTZ)

### 2.4 Tabela `clinical_notes` (Prontuário / Evolução Clínica)
- `id` (UUID, Primary Key)
- `clinic_id` (UUID, FK -> `clinics.id`)
- `patient_id` (UUID, FK -> `patients.id`, Index)
- `dentist_id` (UUID, FK -> `users.id`)
- `attendance_date` (TIMESTAMPTZ, NOT NULL)
- `chief_complaint_encrypted` (TEXT, AES-256) — Queixa principal
- `diagnosis_encrypted` (TEXT, AES-256) — Diagnóstico
- `procedure_summary` (TEXT, NOT NULL) — Resumo de procedimentos realizados
- `created_at` (TIMESTAMPTZ)

### 2.5 Tabela `patient_files` (Radiografias e Exames)
- `id` (UUID, Primary Key)
- `clinic_id` (UUID, FK -> `clinics.id`)
- `patient_id` (UUID, FK -> `patients.id`, Index)
- `file_name` (VARCHAR(255), NOT NULL)
- `file_type` (VARCHAR(50), NOT NULL) — `RADIOGRAFIA`, `FOTO_INTRAORAL`, `EXAME_LABORATORIAL`, `OUTRO`
- `file_url` (TEXT, NOT NULL) — Caminho S3 / Object Storage
- `file_size` (BIGINT)
- `created_at` (TIMESTAMPTZ)

---

## 3. Endpoints Backend (Go / Fiber)

### Pacientes
| Método | Endpoint | Descrição |
|---|---|---|
| `POST` | `/api/v1/patients` | Cria novo paciente (valida e criptografa CPF/Telefone) |
| `GET` | `/api/v1/patients` | Lista pacientes com busca (`?search=`), filtro e paginação |
| `GET` | `/api/v1/patients/:id` | Retorna o paciente (campos descriptografados para usuário autorizado) |
| `PUT` | `/api/v1/patients/:id` | Atualiza dados do paciente |
| `DELETE` | `/api/v1/patients/:id` | Soft delete do paciente |

### Odontograma
| Método | Endpoint | Descrição |
|---|---|---|
| `GET` | `/api/v1/patients/:id/teeth` | Retorna o mapa de todos os dentes/faces alterados do paciente |
| `POST` | `/api/v1/patients/:id/teeth` | Atualiza status de um dente/face e grava na `teeth_history` |
| `GET` | `/api/v1/patients/:id/teeth/history` | Retorna a linha do tempo completa de edições do odontograma |
| `GET` | `/api/v1/patients/:id/teeth/:tooth_number` | Retorna o histórico específico de 1 dente |

### Prontuário & Arquivos
| Método | Endpoint | Descrição |
|---|---|---|
| `GET` | `/api/v1/patients/:id/notes` | Retorna evoluções clínicas do paciente ordenadas por data |
| `POST` | `/api/v1/patients/:id/notes` | Adiciona nova nota de evolução de atendimento |
| `POST` | `/api/v1/patients/:id/files` | Upload de radiografia/foto intraoral (`multipart/form-data`) |
| `DELETE` | `/api/v1/patients/:id/files/:file_id` | Exclui anexo do paciente |

---

## 4. Frontend & Telas (Flutter / Riverpod)

### 4.1 Lista e Cadastro de Pacientes
- **Tela de Listagem:** Tabela/Cards com avatar gerado por iniciais, busca com `Debounce` (300ms), filtro por convênio e botão de novo paciente.
- **Formulário de Cadastro:** Validação de CPF em tempo real, máscaras para telefone/data de nascimento e seleção de convênio.

### 4.2 Prontuário Completo do Paciente
Interface dividida em Top Bar com dados do paciente e 3 Abas Principais:

1. **Aba `Odontograma` (Interativo)**:
   - Renderização anatômica dos 32 dentes permanentes (Quadrantes 11-18, 21-28, 31-38, 41-48) com opção de alternar para Dentes Decíduos (Leite).
   - Interação: Toque no dente permite escolher faces específicas (Mesial, Distal, Oclusal, Vestibular, Lingual) ou marcar o dente como um todo.
   - Legenda de Cores:
     - 🔴 **Vermelho**: Cariado / Lesão ativa
     - 🔵 **Azul**: Restaurado / Procedimento concluído
     - ⚪ **Cinza**: Extraído / Ausente
     - 🟡 **Amarelo**: Necessita Canal / Tratamento endodôntico
     - 🟣 **Roxo**: Implante / Prótese
   - Painel lateral (Drawer) exibe a linha do tempo histórica do dente selecionado.

2. **Aba `Evolução Clínica`**:
   - Timeline cronológica das consultas realizadas.
   - Formulário rápido para adicionar nota do dia (Queixa Principal, Diagnóstico, Procedimento).

3. **Aba `Exames & Radiografias`**:
   - Galeria no estilo Grid com miniaturas de fotos e radiografias.
   - Suporte a upload por arraste ou seleção de arquivos.
   - Modal Lightbox para zoom e inspeção detalhada de radiografias intraorais.

---

## 5. Regras de Negócio e Segurança da Fase 1

1. **Escopo Tenant Ativo**: O middleware de autenticação intercepta todo token JWT, injeta o `clinic_id` no contexto da requisição e todas as operações de banco aplicam a cláusula obrigatoriamente.
2. **Audit Trail Dental**: O estado atual do odontograma (`teeth_status`) pode ser consultado rapidamente para renderizar a tela, mas qualquer modificação dispara um registro na `teeth_history` de forma imutável (sem `UPDATE` ou `DELETE` no histórico).
3. **Criptografia em Repouso**: Dados sensíveis do paciente (CPF, Telefone, Queixa, Diagnóstico) utilizam criptografia AES-256 na camada de serviço do backend antes do armazenamento no banco de dados.
