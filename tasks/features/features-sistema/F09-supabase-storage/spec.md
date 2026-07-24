# Technical Spec: F09-supabase-storage

## 1. Technical Overview
- **Feature**: Armazenamento de Arquivos no Supabase Storage
- **Tech Stack Used**: Go (`github.com/aws/aws-sdk-go-v2/service/s3`), GORM.
- **Architecture Approach**: Substituição do disco local (uploads/) por envio em stream via backend Go para o bucket S3 compatível do Supabase.

## 2. Data Models & Schema
- **Database Changes**:
  - `patient_files` (Alteração): A coluna `file_path` agora vai guardar a chave (Object Key) do S3, ex: `files/UUID.pdf`, e não o path físico `uploads/...`.

## 3. Component Architecture
- Nenhuma mudança estrutural no front, o `PatientFileUploader` manterá os mesmos inputs (MultipartForm).

## 4. Core Logic & Algorithms
- **Operation**: Upload de Arquivo
  - Step 1: Extrair `multipart.File` do Request HTTP.
  - Step 2: Instanciar `s3.Client` da AWS usando credenciais customizadas (Endpoint URL apontando pro Supabase).
  - Step 3: Usar `s3.PutObject` passando o stream (Reader).
  - Step 4: Salvar chave no banco e retornar OK.

## 5. Error Handling & Edge Cases
- **Scenario**: Arquivo excede tamanho permitido (ex: > 10MB).
  - **Handling**: Fiber BodyLimit Middleware recusa o upload antes de saturar a memória, retornando 413 Payload Too Large.

## 6. Security & Performance
- **Security Check**: Bucket configurado como PRIVADO no painel Supabase. 
- **Performance Targets**: Upload direto com buffer `io.Reader` no Go, sem carregar tudo na memória RAM simultaneamente (Streaming).
