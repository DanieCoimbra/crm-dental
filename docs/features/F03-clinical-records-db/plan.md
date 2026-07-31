# Action Plan: Domínio de Prontuários & Anexos (F03-clinical-records-db)

## 1. Local Scope
- **Derivação**: Extraído dos documentos `docs/global/prd-fase1-bd.md` e `docs/global/spec-fase1-bd.md`.
- **Responsabilidade**: Armazenamento de evoluções clínicas do prontuário (`clinical_notes`) e metadados de exames/radiografias (`patient_files`), incluindo suporte a texto criptografado AES-256 e políticas RLS de alto sigilo médico.

## 2. External Dependencies
- Requer a tabela `patients` (`F01-patients-db`) como FK `patient_id`.
- Requer a tabela `users` (Fase 0) como FK `dentist_id` e `uploaded_by`.
- Requer funções RLS `current_clinic_id()` e `is_admin()`.

## 3. Execution Phases
- **Fase 1 (DDL & Tipos)**: Criar o tipo ENUM `patient_file_type_enum`, tabelas `clinical_notes` e `patient_files`.
- **Fase 2 (Segurança RLS)**: Configurar políticas RLS liberando acesso de leitura/escrita estritamente para `admin` e negando para `receptionist`.
- **Fase 3 (Armazenamento de Anexos)**: Configurar referências de URLs de arquivos e tamanhos.

## 4. Next Steps
- Elaborar `docs/features/F03-clinical-records-db/spec.md` e `docs/features/F03-clinical-records-db/contract.md`.
