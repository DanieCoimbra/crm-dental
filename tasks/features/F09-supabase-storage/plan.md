# Action Plan: F09-supabase-storage

## 1. Local Scope
- **Derivation**: Extracted from [PRD Dental CRM Fullstack](../../prd-dental-crm-fullstack.md) (US-008) e [Spec](../../spec-dental-crm-fullstack.md).
- **Responsibility**: Migrar a persistência de binários e anexos do prontuário eletrônico do disco local para a nuvem da AWS/Supabase Storage (API S3 Compatible).

## 2. External Dependencies (Before starting)
- Requires `F02-patient-emr` que detém o handler atual de upload `PatientFileHandler`.
- Depende de chaves de ambiente `SUPABASE_STORAGE_URL`, `SUPABASE_ACCESS_KEY` e `SUPABASE_SECRET_KEY`.

## 3. Execution Phases
- **Phase 1**: [Setup & Contracts]
  - Instalar dependência `github.com/aws/aws-sdk-go-v2` no backend Go.
  - Adaptar a base de dados (tabela de arquivos) para guardar paths/urls invés de caminhos locais.
- **Phase 2**: [Local Spec & Logic]
  - Refatorar o `PatientFileService` para parsear o `multipart.File` recebido e subir via `PutObject` da AWS SDK.
  - Implementar lógica de geração de "Signed URLs" para proteger a privacidade dos exames, se necessário, ou usar proxy pelo Go.
- **Phase 3**: [Integration]
  - Ajustar o Flutter `Dio` client caso headers de multipart precisem de tracking de progresso (Progress bar).

## 4. Next Steps
- Run `/spec-write` to map the exact AWS SDK logic and bucket rules.
- Run `/contract` se houver mudança nas chaves do JSON retornado no upload.
