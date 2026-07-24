# Action Plan: F02 - Flutter Web & Vercel Build Optimization

## 1. Local Scope
- **Derivation**: Extraído do PRD [tasks/prd-deploy-render-vercel.md](file:///C:/Users/progc/Daniel/dental-clinic-crm/tasks/prd-deploy-render-vercel.md) e Spec [tasks/spec-deploy-render-vercel.md](file:///C:/Users/progc/Daniel/dental-clinic-crm/tasks/spec-deploy-render-vercel.md).
- **Responsibilidade**: Ajustar os scripts de compilação Web do Flutter, injeção da variável `--dart-define=API_BASE_URL` no `build.sh`, regras de rewrite SPA no `vercel.json` e interceptores de resiliência/timeout no cliente HTTP `Dio` (`api_client.dart`).

## 2. External Dependencies (Before starting)
- URL de produção da API backend no Render (`https://crm-clinica-gjss.onrender.com`).
- Domínio do Frontend na Vercel (`https://crm-clinica-ten.vercel.app`).

## 3. Execution Phases
- **Phase 1: Script de Build (`build.sh`) & Rewrite SPA (`vercel.json`)**
  - Atualizar `frontend_flutter/build.sh` para incluir `--dart-define=API_BASE_URL=https://crm-clinica-gjss.onrender.com`.
  - Garantir a regra de rewrite em `frontend_flutter/vercel.json` (`/(.*) -> /index.html`).
- **Phase 2: Cliente HTTP Dio & Timeouts (`api_client.dart`)**
  - Ajustar `connectTimeout` para 30 segundos no Dio (mitigação do Cold Start do Render).
  - Tratar exceções de timeout com feedback legível no `DioException`.
- **Phase 3: Validação do Build e Linter**
  - Rodar `flutter analyze` em `frontend_flutter`.
  - Executar compilação de teste `flutter build web --release`.

## 4. Next Steps
- Validar se o build local do Flutter Web compila sem erros com as flags inseridas.
