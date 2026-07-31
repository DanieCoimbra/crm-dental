# Contrato de Dados: User Profiles & RBAC

## 1. Resumo do Contrato
- **Domínio:** `F03-users-rbac`
- **Versão:** 1.0.0
- **Consumidores:** Auth Service, Frontend Flutter, Middlewares de Permissão

## 2. Estrutura de Tabela Exposta

| Campo | Tipo | Restrições | Descrição |
|---|---|---|---|
| `id` | `UUID` | PRIMARY KEY, FK `auth.users(id)` | ID do usuário no Supabase Auth |
| `clinic_id` | `UUID` | NOT NULL, FK `public.clinics(id)` | ID da clínica vinculada |
| `name` | `VARCHAR(255)` | NOT NULL | Nome completo do usuário |
| `email` | `VARCHAR(255)` | NOT NULL, UNIQUE | E-mail de login do usuário |
| `role` | `VARCHAR(20)` | CHECK (admin, receptionist) | Papel de acesso no sistema |
| `is_active` | `BOOLEAN` | DEFAULT true | Status de acesso do membro |

## 3. Regras de Cibersegurança & RLS
- **Usuários da mesma clínica:** Podem visualizar a lista de membros da equipe (`SELECT`).
- **Apenas Admin:** Pode alterar funções (`role`), desativar usuários (`is_active = false`) ou cadastrar novos funcionários da sua própria clínica.
