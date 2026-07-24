# Technical Specification (Spec): F03 - Supabase Storage Cloud Integration

**Feature:** `F03-supabase-storage-integration`  
**Derivação:** Extensão do PRD [tasks/prd-deploy-render-vercel.md](file:///C:/Users/progc/Daniel/dental-clinic-crm/tasks/prd-deploy-render-vercel.md) e Spec Geral [tasks/spec-deploy-render-vercel.md](file:///C:/Users/progc/Daniel/dental-clinic-crm/tasks/spec-deploy-render-vercel.md)  
**Status:** Aprovado  
**Data:** 2026-07-24  
**Localização:** `features/F03-supabase-storage-integration/spec.md`  

---

### 1. Technical Overview

- **Feature**: Substituição do Armazenamento Efêmero Local por Supabase Storage API no Backend Go.
- **Tech Stack Used**: Go 1.26, Fiber v2 (`github.com/gofiber/fiber/v2`), GORM, Supabase Storage REST Client, `google/uuid`.
- **Architecture Approach**: Adaptador de armazenamento desacoplado (`internal/pkg/storage/supabase_storage.go`) utilizando a API REST do Supabase Storage (`/storage/v1/object/`) para salvar documentos e exames e retornar URLs públicas definitivas.

---

### 2. Data Models & Schema

#### 2.1 Modelo de Dados da Tabela `patient_files`

```go
type PatientFile struct {
    ID        string    `gorm:"primaryKey;type:uuid" json:"id"`
    PatientID string    `gorm:"type:uuid;not null;index" json:"patient_id"`
    ClinicID  string    `gorm:"type:uuid;not null;index" json:"clinic_id"`
    FileName  string    `gorm:"type:varchar(255);not null" json:"file_name"`
    FileURL   string    `gorm:"type:text;not null" json:"file_url"`
    FileType  string    `gorm:"type:varchar(100)" json:"file_type"`
    FileSize  int64     `gorm:"type:bigint" json:"file_size"`
    CreatedAt time.Time `json:"created_at"`
}
```

#### 2.2 Schema de Variáveis de Ambiente do Supabase Storage

- `SUPABASE_URL`: `https://hecpazxguibzkcsibpjq.supabase.co`
- `SUPABASE_KEY`: Chave de serviço ou anon
- `SUPABASE_STORAGE_BUCKET`: `clinic-files`

---

### 3. Component Architecture

#### 3.1 Adaptador `StorageService` (`backend-go/internal/pkg/storage/supabase_storage.go`)
- **Interface:**
  ```go
  type StorageService interface {
      UploadFile(bucket string, path string, fileBytes []byte, contentType string) (string, error)
      DeleteFile(bucket string, path string) error
  }
  ```
- **Responsabilidade**: Efetuar chamadas HTTP REST multipart para a API do Supabase Storage e retornar a URL pública em caso de sucesso.

#### 3.2 `PatientFileHandler` (`backend-go/internal/adapters/handlers/patient_file_handler.go`)
- **Responsabilidade**: Receber o upload multipart da requisição Fiber (`c.FormFile("file")`), validar o limite de 50MB, delegar o upload ao `StorageService` e persistir o registro no banco via GORM.

---

### 4. Core Logic & Algorithms

#### 4.1 Algoritmo de Upload para o Supabase Storage

1. **Passo 1:** Extrair o arquivo do cabeçalho `c.FormFile("file")`.
2. **Passo 2:** Validar tamanho do arquivo (`file.Size <= 50 * 1024 * 1024`).
3. **Passo 3:** Gerar nome único sanitizado: `path = fmt.Sprintf("patients/%s/%s_%s", patientID, uuid.New().String(), filepath.Base(file.Filename))`.
4. **Passo 4:** Enviar requisição `POST` HTTP para `${SUPABASE_URL}/storage/v1/object/${SUPABASE_STORAGE_BUCKET}/${path}` enviando os bytes do arquivo e o cabeçalho `Authorization: Bearer ${SUPABASE_KEY}`.
5. **Passo 5:** Se o retorno for HTTP 200/201, montar a URL pública:  
   `fileURL = fmt.Sprintf("%s/storage/v1/object/public/%s/%s", supabaseURL, bucket, path)`
6. **Passo 6:** Salvar no banco de dados e retornar JSON 201 Created para o cliente.

---

### 5. Error Handling & Edge Cases

- **Cenário 1: Arquivo Maior que 50MB**
  - **Tratamento:** Retornar HTTP 413 Payload Too Large imediatamente antes de processar o upload.
- **Cenário 2: Falha de Autenticação/Permissão no Supabase Storage**
  - **Tratamento:** Capturar resposta de erro da API do Supabase e retornar HTTP 502 Bad Gateway com mensagem `"Erro ao armazenar arquivo no Supabase Storage"`.

---

### 6. Security & Performance

- **Segurança:** Validação estrita de extensões permitidas e sanitização do nome do arquivo para prevenirPath Traversal.
- **Performance:** Envio direto via streaming de memória sem necessidade de salvar temporariamente em disco efêmero.
