# Feature Technical Specification: F02-Patient EMR

## 1. Technical Overview
- **Feature**: F02-Patient EMR (Prontuário Eletrônico)
- **Tech Stack Used**: 
  - **Backend**: Go (Fiber, GORM), Render (Hosting / Nuvem).
  - **Infra/Cybersecurity**: Supabase (Database PostgreSQL e Supabase Storage para arquivos).
  - **Frontend**: Flutter (Riverpod), Quill/RichText (Flutter).
- **Architecture Approach**: API RESTful para CRUD. Como o backend será hospedado no Render (discos efêmeros), os arquivos anexados não podem ser salvos localmente. O Go backend vai receber os arquivos do Flutter e fará o proxy (ou enviará a URL assinada) direto para o **Supabase Storage**.

## 2. Data Models & Schema
- **Database Changes**:
  - `patients`: `id (PK)`, `clinic_id (FK)`, `name`, `cpf`, `birth_date`, `deleted_at (Soft Delete)`.
  - `clinical_evolutions`: `id`, `clinic_id`, `patient_id`, `content_html (TEXT)`, `created_by`.
  - `patient_files`: `id`, `clinic_id`, `patient_id`, `supabase_url`, `file_name`, `size_bytes`.
- **State Management**:
  - `PatientListNotifier`: Mantém lista cacheada de pacientes com paginação.

## 3. Component Architecture (UI)
- `PatientFormScreen`: Renderiza formulário de cadastro, validação de CPF.
- `RichTextEditorComponent`: Encapsula biblioteca de edição (`flutter_quill`) e gera saída HTML estruturada.

## 4. Core Logic & Algorithms
- **Operation: Armazenamento de Arquivos no Supabase Storage**
  - O Flutter submete o arquivo (`multipart/form-data`) para o endpoint `POST /api/v1/patients/:id/files` do backend.
  - O Backend, hospedado no Render, processa o arquivo na memória e o encaminha para o Bucket do Supabase Storage através da API HTTP do Supabase ou via S3-compatible API do Supabase.
  - A API em Go obtém a URL pública/assinada e armazena na tabela `patient_files`.

## 5. Error Handling & Edge Cases
- **Scenario**: Timeout ao conectar com o Supabase Storage.
  - **Handling**: A API no Render deve cancelar o contexto de requisição e retornar `503 Service Unavailable`. O Interceptor do Dio no Flutter tentará automaticamente o upload até 3 vezes (backoff exponencial).

## 6. Security & Performance
- **Sanitização XSS (Backend)**: É mandatório limpar o HTML recebido no campo `content_html` usando pacotes como o `microcosm-cc/bluemonday` antes da persistência para garantir imunidade total contra injeção de scripts (XSS).
- **Criptografia**: Evoluções clínicas terão criptografia em repouso (AES) aplicada diretamente no nível da aplicação em Go antes da persistência no banco Supabase. O Render forçará TLS em trânsito nativamente.
