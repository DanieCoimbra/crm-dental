# Action Plan: Domínio de Dados do Odontograma (F02-odontogram-db)

## 1. Local Scope
- **Derivação**: Extraído dos documentos `docs/global/prd-fase1-bd.md` e `docs/global/spec-fase1-bd.md`.
- **Responsabilidade**: Gestão do mapa dental do paciente (`teeth_status`) e histórico de alterações (`teeth_history`), incluindo restrição do padrão anatômico FDI (dentes 11-48 / 51-85), controle de faces e gatilho de imutabilidade.

## 2. External Dependencies
- Requer a tabela `patients` (`F01-patients-db`) criada e referenciada como FK `patient_id`.
- Requer funções RLS `current_clinic_id()` e `is_admin()`.

## 3. Execution Phases
- **Fase 1 (Tipos & DDL)**: Definir os ENUMs `tooth_face_enum` e `tooth_condition_enum`, tabelas `teeth_status` e `teeth_history` com restrições `CHECK` FDI.
- **Fase 2 (Imutabilidade & Triggers)**: Implementar a função e trigger `prevent_teeth_history_mutation()` para bloqueio de `UPDATE` e `DELETE` no histórico.
- **Fase 3 (Segurança RLS)**: Configurar políticas RLS liberando acesso exclusivo para o papel `admin` e bloqueando a `receptionist`.

## 4. Next Steps
- Elaborar `docs/features/F02-odontogram-db/spec.md` e `docs/features/F02-odontogram-db/contract.md`.
