# Action Plan: F03 - Supabase Storage Cloud Integration

## 1. Local Scope
- **Derivation**: Extraído do PRD [tasks/prd-deploy-render-vercel.md](file:///C:/Users/progc/Daniel/dental-clinic-crm/tasks/prd-deploy-render-vercel.md) e Spec [tasks/spec-deploy-render-vercel.md](file:///C:/Users/progc/Daniel/dental-clinic-crm/tasks/spec-deploy-render-vercel.md).
- **Responsibilidade**: Garantir a substituição do salvamento de arquivos no sistema de arquivos local efêmero (`./uploads/`) por chamadas de API do Supabase Storage no backend em Go.

## 2. External Dependencies (Before starting)
- Credenciais e Buckets do Supabase (`SUPABASE_URL`, `SUPABASE_KEY`, `SUPABASE_STORAGE_BUCKET`).
- Configuração do bucket público/assinado no painel do Supabase.

## 3. Execution Phases
- **Phase 1: Pacote/Adaptador de Storage Supabase**
  - Implementar/revisar o cliente de upload de arquivos para Supabase Storage em `backend-go/internal/pkg/storage/`.
- **Phase 2: Integração com Handlers de Upload**
  - Atualizar os handlers de Patient File, Evoluções Clínicas e Avatar do Usuário para usar a API de Storage.
  - Garantir o retorno de URLs públicas válidas para exibição no Flutter.
- **Phase 3: Validação de Funcionalidade**
  - Testar envio de arquivo em ambiente de desenvolvimento.

## 4. Next Steps
- Validar se todos os endpoints de envio de arquivo referenciam o serviço em nuvem.
