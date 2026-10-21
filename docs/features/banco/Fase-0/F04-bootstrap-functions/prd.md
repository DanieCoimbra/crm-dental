# PRD — F04 Funções de Login, Refresh e Limpeza

**Derivação:** PRD global do banco, US-004, US-005, US-006 e FR-8; spec global, seção 4 e a justificativa do `crm_definer` (seção 1.1).
**Dependências externas:** F01 (papel `crm_definer`), F02 (função de tenant), F03 (tabelas). Consumida pelo backend `F04-authentication`.

## 1. Introdução

- **Problema:** login e refresh acontecem antes de haver tenant, mas a RLS com `FORCE` esconde tudo de quem não definiu `app.clinic_id`.
- **Solução:** três funções `SECURITY DEFINER` pertencentes a `crm_definer` (sem login, sem `BYPASSRLS`, não dono das tabelas), com políticas RLS de leitura nomeadas só para ele e retorno mínimo.
- **Metas:**
  1. Login e refresh funcionam sem tenant definido e retornam só as colunas necessárias.
  2. Nenhum outro caminho de leitura sem tenant existe para `crm_app`.
  3. Limpeza apaga apenas tokens vencidos há mais de 30 dias.
  4. Consultas em ≤ 5 ms (índice único) com 100 mil usuários.

## 2. User stories

### US-001: Localizar usuário para login
**Descrição:** Como backend, quero buscar o usuário por e-mail antes de saber a clínica.

**Critérios de aceite:**
- [ ] `core.find_user_for_login(email)` busca por `lower(email)` e devolve `id, clinic_id, password_hash, role, active` (0 ou 1 linha).
- [ ] Não devolve nome, dados da clínica nem outros usuários.

### US-002: Localizar refresh token
**Descrição:** Como backend, quero buscar o token pelo hash para rotacioná-lo ou revogá-lo.

**Critérios de aceite:**
- [ ] `core.find_refresh_token(hash)` devolve `id, clinic_id, user_id, family_id, expires_at, used_at, revoked_at` (0 ou 1 linha).

### US-003: Limpar tokens vencidos
**Descrição:** Como operador, quero apagar tokens expirados para não crescer sem limite.

**Critérios de aceite:**
- [ ] `core.purge_expired_refresh_tokens()` apaga `expires_at < now() - interval '30 days'` e devolve a contagem.
- [ ] Executável apenas por `crm_app` (chamada pelo `db-maintenance.yml`).

### US-004: Superfície mínima
**Descrição:** Como responsável pela segurança, quero que as funções não ampliem o acesso.

**Critérios de aceite:**
- [ ] `SET search_path` fixo, sem SQL dinâmico, `EXECUTE` revogado de `PUBLIC`.
- [ ] `crm_definer` só tem `SELECT` nas colunas listadas e `DELETE` em `refresh_tokens`.

## 3. Requisitos funcionais

- **FR-1:** Criar as funções em `V006`, dono `crm_definer` (`ALTER FUNCTION ... OWNER TO`), `SECURITY DEFINER`.
- **FR-2:** Criar políticas: `SELECT ... TO crm_definer USING (true)` em `users` e `refresh_tokens`; `DELETE ... TO crm_definer USING (expires_at < now() - interval '30 days')` em `refresh_tokens`.
- **FR-3:** Conceder a `crm_definer` apenas `SELECT (id, clinic_id, email, password_hash, role, active)` em `users`, `SELECT` em `refresh_tokens` e `DELETE` em `refresh_tokens`.
- **FR-4:** `GRANT EXECUTE` das três funções somente a `crm_app`.

## 4. Fora do escopo

- Qualquer função que retorne dados clínicos.
- Agendamento da limpeza (workflow, F05).

## 5. Considerações técnicas e riscos

- `FORCE RLS` aplica-se também ao dono: por isso as funções não podem pertencer a `crm_owner`.
- `crm_owner` precisa ser membro de `crm_definer` para trocar o dono das funções (já feito na F01).
- Funções `SECURITY DEFINER` mal escritas são o principal vetor de escalada: revisão obrigatória.
- **Pergunta aberta:** nenhuma.
