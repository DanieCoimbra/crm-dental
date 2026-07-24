# Contract: F09-supabase-storage

## 1. Overview
Interface do upload persistente. Todo anexo de raio-x/documento vai trafegar pelo backend até bater no S3 (Supabase).

## 2. Inputs (API Requests)
- `POST /api/v1/patients/:id/files`
  - Tipo: `multipart/form-data`
  - Campo: `file` (Binário)

## 3. Outputs (API Responses)
- **200 OK**:
```json
{
  "id": 12,
  "file_url": "https://xyz.supabase.co/storage/v1/object/public/files/abc.pdf",
  "name": "raio-x.png"
}
```

## 4. Integration Rules
- A variável `SUPABASE_STORAGE_URL` deve estar preenchida no `.env`.
- As credenciais de acesso precisam bater com o Bucket do Supabase, formatadas para SDK de AWS S3 V2.

## 5. Boundaries
- Não usaremos upload direto pelo Client Frontend (Presigned Upload URL do AWS) por questões de complexidade agora. O arquivo subirá via backend.
