# Plano de Execução do Domínio: Clinic Multi-Tenant Management (`F02-clinic-multi-tenant`)

## 1. Escopo Local
- **Origem:** Extraído de `docs/global/prd.md` e `docs/global/spec.md`.
- **Responsabilidade:** Gestão da tabela `clinics`, controle de status da assinatura (trial, ativo, cancelado) e RLS de leitura/atualização da própria clínica.

## 2. Dependências Externas
- Requer `F01-security-core-and-helpers` (`get_current_clinic_id()`, `get_current_user_role()`).

## 3. Fases de Execução
- **Fase 1:** DDL da tabela `clinics`.
- **Fase 2:** Especificação de políticas RLS (`clinics_select_policy`, `clinics_update_policy`).
- **Fase 3:** Contrato de dados da tabela `clinics`.
