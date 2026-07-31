# Plano de Execução do Domínio: Security Core & Multi-Tenant Helpers (`F01-security-core-and-helpers`)

## 1. Escopo Local
- **Origem:** Extraído de `docs/global/prd.md` e `docs/global/spec.md`.
- **Responsabilidade:** Criação das extensões base do PostgreSQL, funções auxiliares PL/pgSQL (`get_current_clinic_id`, `get_current_user_role`) e schemas utilitários de cibersegurança.

## 2. Dependências Externas
- Nenhuma. Este é o domínio base de segurança que todos os outros domínios consumirão.

## 3. Fases de Execução
- **Fase 1:** Definição de Contrato de RPCs e Helpers.
- **Fase 2:** Especificação detalhada de código PL/pgSQL e busca de claims em JWT (`auth.jwt()`).
- **Fase 3:** Testes unitários em SQL (pgTAP / scripts SQL de validação de isolamento).
