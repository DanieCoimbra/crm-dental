# Action Plan: Domínio de Dados de Pacientes (F01-patients-db)

## 1. Local Scope
- **Derivação**: Extraído dos documentos `docs/global/prd-fase1-bd.md` e `docs/global/spec-fase1-bd.md`.
- **Responsabilidade**: Gestão das informações cadastrais e demográficas do paciente (`patients`), criptografia AES-256 (CPF e Telefone), controle de Soft Delete (`deleted_at`), e políticas de RLS que autorizam leitura/escrita para papéis `admin` e `receptionist`.

## 2. External Dependencies
- Depende da tabela `clinics` (Fase 0) para amarração da FK multi-tenant (`clinic_id`).
- Necessita da função auxiliar RLS `current_clinic_id()` configurada no PostgreSQL.

## 3. Execution Phases
- **Fase 1 (Contratos e DDL)**: Definir a DDL estrita da tabela `patients`, índices de busca por nome e soft delete.
- **Fase 2 (Segurança & RLS)**: Configurar as políticas de RLS `patients_select_policy`, `patients_insert_policy`, `patients_update_policy` e `patients_delete_policy`.
- **Fase 3 (Validação de Acesso)**: Testar isolamento por `clinic_id` e permissão do papel `receptionist`.

## 4. Next Steps
- Elaborar `docs/features/F01-patients-db/spec.md` e `docs/features/F01-patients-db/contract.md`.
