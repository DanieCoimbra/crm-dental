# Contrato de Dados: Security Core & Multi-Tenant Helpers

## 1. Resumo do Contrato
- **Domínio:** `F01-security-core-and-helpers`
- **Versão:** 1.0.0
- **Consumidores:** RLS de todas as tabelas, Backend API, RPCs

## 2. Interface de Funções Disponibilizadas

### 1. `get_current_clinic_id()`
- **Entrada:** Nenhuma (Lê do ambiente/contexto de sessão do Supabase `request.jwt.claims`).
- **Saída:** `UUID` contendo o ID da clínica.
- **Comportamento em Falha:** Retorna `NULL` se o usuário for anônimo/não autenticado.

### 2. `get_current_user_role()`
- **Entrada:** Nenhuma.
- **Saída:** `TEXT` (`'admin'` ou `'receptionist'`).
- **Comportamento em Falha:** Retorna `NULL` se o usuário for anônimo.

## 3. Critérios de Aceite da Integração
- [ ] Chamada de `SELECT get_current_clinic_id()` sob o perfil autenticado retorna o UUID correto.
- [ ] Desempenho de execução < 1ms por instrução SQL.
