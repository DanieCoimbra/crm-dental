# Contrato de Dados: Clinic Multi-Tenant Management

## 1. Resumo do Contrato
- **Domínio:** `F02-clinic-multi-tenant`
- **Versão:** 1.0.0
- **Consumidores:** Frontend Flutter, Backend API, Autenticação de Usuários

## 2. Estrutura de Tabela Exposta

| Campo | Tipo | Restrições | Descrição |
|---|---|---|---|
| `id` | `UUID` | PRIMARY KEY | Identificador único da clínica |
| `name` | `VARCHAR(255)` | NOT NULL | Nome da clínica / consultório |
| `cnpj_cpf` | `VARCHAR(20)` | NULL | CPF ou CNPJ do profissional/empresa |
| `phone` | `VARCHAR(20)` | NULL | Telefone principal |
| `email` | `VARCHAR(255)` | NOT NULL, UNIQUE | E-mail corporativo da clínica |
| `trial_ends_at` | `TIMESTAMPTZ` | NOT NULL | Data limite do período de teste |
| `status` | `VARCHAR(20)` | CHECK (active, trial_expired, canceled) | Status de faturamento do tenant |

## 3. Regras de Segurança (RLS)
- **Recepcionista:** Leitura liberada da própria clínica. Alteração negada (`42501`).
- **Admin:** Leitura e alteração liberadas da própria clínica.
