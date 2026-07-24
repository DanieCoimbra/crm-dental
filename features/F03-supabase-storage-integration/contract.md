# Feature Contract: F03 - Supabase Storage Cloud Integration

**Feature:** `F03-supabase-storage-integration`  
**Versão:** 1.0.0  
**Consumidores Primários:** Handlers de Backend Go (`PatientFileHandler`, `AuthHandler`), Frontend Flutter Web.  
**Localização:** `features/F03-supabase-storage-integration/contract.md`  

---

### 1. Contract Summary

Este contrato define o protocolo de upload de arquivos de exames e avatares, estabelecendo a garantia de persistência durável no Supabase Storage e o formato da resposta das URLs públicas.

---

### 2. Inputs (Multipart Form Upload)

- **Endpoint:** `POST /api/v1/patients/:patient_id/files`
- **Autenticação:** Requerida (`Authorization: Bearer <jwt_token>`)
- **Headers:** `Content-Type: multipart/form-data`
- **Form Fields:**
  - `file`: `file (binary, máximo 50MB, obrigatório)`

---

### 3. Outputs (Responses & Schemas)

#### 3.1 Resposta de Sucesso (`201 Created`)
```json
{
  "id": "c1f2e3d4-5678-90ab-cdef-1234567890ab",
  "patient_id": "a9b8c7d6-e5f4-3210-fedc-ba9876543210",
  "file_name": "raio_x_panoramico.png",
  "file_url": "https://hecpazxguibzkcsibpjq.supabase.co/storage/v1/object/public/clinic-files/patients/a9b8c7d6-e5f4-3210-fedc-ba9876543210/uuid_raio_x_panoramico.png",
  "file_type": "image/png",
  "file_size": 2458900,
  "created_at": "2026-07-24T10:05:00Z"
}
```

#### 3.2 Resposta de Erro de Limite de Tamanho (`413 Payload Too Large`)
```json
{
  "error_code": "FILE_TOO_LARGE",
  "message": "O arquivo excede o limite máximo permitido de 50MB."
}
```

---

### 4. Business Rules & Limits

1. **Persistência Exclusiva em Nuvem:**
   - Nenhum arquivo enviado pode ser salvo em armazenamento efêmero local (`/uploads`) em ambiente de produção.
2. **URLs Públicas Permanentes:**
   - A propriedade `file_url` retornada deve ser acessível via protocolo HTTPS sem expiração prévia.

---

### 5. Events Emitted (Side Effects)

- **`PatientFile.Created`:** Evento interno auditável indicando inclusão de novo anexo médico no prontuário do paciente.

---

### 6. Acceptance Criteria (Integration Tests)

- [ ] Upload de arquivo `.png`/`.pdf` de até 50MB retorna status `201 Created` e URL iniciando com `${SUPABASE_URL}/storage/v1/object/public/`.
- [ ] Tentativa de upload de arquivo com mais de 50MB retorna HTTP 413.
