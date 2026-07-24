# Action Plan: F02-Patient EMR (Prontuário)

## 1. Local Scope
- **Derivation**: Extracted from PRD Fullstack MVP.
- **Responsibility**: Gerenciar o ciclo de vida clínico do paciente, incluindo cadastro de ficha completa, evolução clínica com Rich Text e upload/visualização de documentos (PDF, Imagens). O armazenamento de arquivos será feito diretamente no **Supabase Storage** e o Backend Go será hospedado no **Render**.

## 2. External Dependencies (Before starting)
- Requer `F01-multi-tenant-core` para assegurar que apenas médicos da respectiva clínica vejam os prontuários de seus pacientes.
- Bucket do Supabase Storage já deve estar criado e acessível via API Key/Token no backend.

## 3. Execution Phases
- **Phase 1**: [Setup & Contracts] 
  - Definir esquema de Tabelas (`patients`, `clinical_evolutions`, `patient_files`).
  - Estabelecer a comunicação da API Go com o Supabase Storage para envio e resgate de anexos (em substituição à AWS S3).
- **Phase 2**: [Local Spec & Logic]
  - Ajustar a API REST CRUD de Pacientes.
  - Implementar sanitização XSS nas evoluções clínicas usando a biblioteca `bluemonday` (Go).
  - Integrar editor de Rich Text (Flutter Quill) no Flutter.
- **Phase 3**: [Integration & Infra]
  - Conectar UI do Flutter à API Go.
  - Testar envio de uploads e garantir que não estão sendo salvos no disco local (já que os discos do Render são efêmeros), mas sim transmitidos ao Supabase Storage.
  - Configurar processo de deploy (ex: `Dockerfile` ou Render YAML) para a API no Render.

## 4. Next Steps
- Ajustar os endpoints em Go para usar o client oficial do Supabase ou requisições HTTP para a API do Supabase Storage.
- Refatorar a classe `ClinicalEvolutionService` para incluir sanitização HTML real (XSS protection).
