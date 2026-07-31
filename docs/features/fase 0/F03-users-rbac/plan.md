# Plano de Execução do Domínio: User Profiles & RBAC (`F03-users-rbac`)

## 1. Escopo Local
- **Origem:** Extraído de `docs/global/prd.md` e `docs/global/spec.md`.
- **Responsabilidade:** Vinculação de perfis de usuário na tabela `public.users` à tabela nativa `auth.users`, atribuição dos papéis (`admin` vs `receptionist`) e políticas RLS de membros da equipe.

## 2. Dependências Externas
- Requer `F01-security-core-and-helpers` e `F02-clinic-multi-tenant`.

## 3. Fases de Execução
- **Fase 1:** DDL da tabela `users` com FK para `auth.users` e `public.clinics`.
- **Fase 2:** Políticas RLS para isolamento de membros e permissão de modificação apenas por `admin`.
- **Fase 3:** Contrato de integração de perfis e papéis.
