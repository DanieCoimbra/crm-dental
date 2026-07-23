# Feature Contract: F02-Patient EMR

## 1. Contract Summary
- **Feature**: F02-Patient EMR
- **Version**: 1.1.0
- **Primary Consumer(s)**: Frontend Flutter (Telas de Gestão Médica).
- **Infrastructure Context**: Backend hospedado no Render, Banco e Arquivos via Supabase.

## 2. Inputs

### Criação de Evolução
- **Endpoint**: `POST /api/v1/patients/:patient_id/evolutions`
- **Payload Schema**:
  ```json
  {
    "content_html": "string (required, HTML format)",
    "date": "string (ISO 8601, required)"
  }
  ```

### Upload de Arquivo do Paciente
- **Endpoint**: `POST /api/v1/patients/:patient_id/files`
- **Content-Type**: `multipart/form-data`
- **Fields**: 
  - `file`: Arquivo físico (PDF, JPG, PNG). Max 10MB.
  - `category`: "string (Ex: Raio-X, Receituário)"

## 3. Outputs

### Success Response (Evolução - 201 Created)
```json
{
  "id": "integer",
  "content_html": "string (sanitized)",
  "created_at": "datetime"
}
```

### Success Response (Arquivo - 201 Created)
```json
{
  "id": "integer",
  "file_name": "string",
  "supabase_url": "string (URL direta ou pre-signed do Supabase Storage)",
  "created_at": "datetime"
}
```

## 4. Business Rules & Limits
- **Validation Rules**: `content_html` **deve** ser sanitizado de forma restrita no backend Go (remoção de tags dinâmicas ou maliciosas como `<script>`).
- **State Prerequisites**: O `clinic_id` originário do JWT deve ser cruzado para impedir acessos a pacientes alheios.

## 5. Events Emitted (Side Effects)
- Nenhum evento assíncrono emitido por este módulo no momento.

## 6. Acceptance Criteria (Integration)
- [ ] O DTO Go reconhece e mapeia `content_html` exatamente (e não apenas `content`).
- [ ] Payload contendo tag `<script>` maliciosa -> Script é neutralizado/removido pelo Backend antes do salvamento e criptografia.
- [ ] Arquivo de imagem enviado **NÃO** fica salvo na pasta `./uploads` do disco (hospedagem efêmera no Render), sendo transmitido diretamente para o Supabase Storage.
