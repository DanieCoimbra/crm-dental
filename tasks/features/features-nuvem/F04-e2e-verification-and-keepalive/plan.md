# Action Plan: F04 - End-to-End Verification & Keep-Alive Strategy

## 1. Local Scope
- **Derivation**: Extraído do PRD [tasks/prd-deploy-render-vercel.md](file:///C:/Users/progc/Daniel/dental-clinic-crm/tasks/prd-deploy-render-vercel.md) e Spec [tasks/spec-deploy-render-vercel.md](file:///C:/Users/progc/Daniel/dental-clinic-crm/tasks/spec-deploy-render-vercel.md).
- **Responsibilidade**: Validar a integração de ponta a ponta (E2E) entre a aplicação na Vercel e o servidor no Render, além de documentar a estratégia de ping keep-alive para evitar repouso do container no Render Free Tier.

## 2. External Dependencies (Before starting)
- Conclusão das features `F01`, `F02` e `F03`.
- Deploy das últimas versões nos painéis da Vercel e do Render.

## 3. Execution Phases
- **Phase 1: Testes E2E de Rede e Conectividade**
  - Fazer login na URL `https://crm-clinica-ten.vercel.app` e validar se as requisições autenticadas disparam sem bloqueio de CORS.
  - Verificar no Console do Navegador (F12) o tráfego HTTP 200 OK e tempo de resposta.
- **Phase 2: Documentação de Keep-Alive**
  - Adicionar instruções de configuração de ping periódico (`GET https://crm-clinica-gjss.onrender.com/healthz` a cada 10-14 minutos via UptimeRobot ou similar).
- **Phase 3: Checklist de Lançamento**
  - Validar todos os critérios de aceite listados no PRD.

## 4. Next Steps
- Finalizar homologação e liberar para produção.
