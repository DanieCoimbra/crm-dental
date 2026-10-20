# PRD — F04 Autenticação

**Derivação:** PRD global, US-001, US-002, US-003 e FR-1 a FR-5, FR-12; riscos 1, 2 e 3.
**Dependências externas:** F01 (erros, CORS, perfis), F02 (`TenantContext`, filtro e aspecto transacional), função banco (tabelas, RLS e funções de bootstrap; contrato na seção 2 do spec global). F03 não é usada aqui.
**Quem usa esta feature:** F05 e todas as rotas autenticadas, que dependem do access token emitido aqui.

## 1. Introdução

- **Problema:** o sistema precisa identificar o usuário e a clínica de cada requisição, sem confiar no front-end.
- **Solução:** registro de clínica com o dentista, login, renovação rotativa e logout, com JWT de curta duração e refresh token revogável.
- **Metas:**
  1. Login p95 ≤ 300 ms com até 4 requisições concorrentes (ambiente local, BCrypt custo 12). Decisão registrada em 2026-10-19: BCrypt custo 12 mantido; a meta deixa de ser 50 concorrentes porque o hash é limitado por CPU (medido em 12 CPUs: 1 login 243 ms; 4 concorrentes p95 295 ms; 50 concorrentes p95 ~1,5 s; custo 11 → 0,8 s e custo 10 → 0,42 s, ainda acima de 300 ms). Decisão de 2026-10-20: no CI (runner do GitHub, 4 CPUs, p95 medido 387 ms) o limite do teste é 600 ms; a meta de 300 ms vale para o ambiente de referência.
  2. 100% das rotas fora de `/api/v1/auth/**` e do health exigem token válido.
  3. Reuso de refresh token revoga toda a cadeia (teste automatizado).
  4. Cobertura ≥ 80% nos services de autenticação.

## 2. User stories

### US-001: Registrar clínica
**Descrição:** Como dentista, quero registrar minha clínica e minha conta, sem pagamento nem trial.

**Critérios de aceite:**
- [ ] `POST /api/v1/auth/register` cria clínica e usuário `DENTIST_ADMIN` na mesma transação.
- [ ] E-mail já usado retorna 409; senha fraca ou campos inválidos retornam 400 com `errors[]`.
- [ ] A resposta não contém plano, trial nem expiração.
- [ ] Retorna access token e refresh token.

### US-002: Login
**Descrição:** Como usuário, quero entrar com e-mail e senha.

**Critérios de aceite:**
- [ ] Credencial inválida, e-mail inexistente ou usuário inativo retornam o mesmo 401 genérico, em tempo equivalente.
- [ ] Access token de 15 min com `sub`, `clinic_id`, `role`, `jti`.
- [ ] Mais de 5 falhas em 1 min por IP+e-mail retornam 429 com `Retry-After`.

### US-003: Renovar sessão
**Descrição:** Como usuário, quero renovar o acesso sem digitar a senha.

**Critérios de aceite:**
- [ ] `POST /api/v1/auth/refresh` devolve novo par e invalida o refresh usado.
- [ ] Reapresentar um refresh já usado revoga toda a cadeia e retorna 401.
- [ ] Usuário inativado não consegue renovar.

### US-004: Encerrar sessão
**Descrição:** Como usuário, quero sair e invalidar meu refresh token.

**Critérios de aceite:**
- [ ] `POST /api/v1/auth/logout` revoga o token informado e sua cadeia; é idempotente (204 mesmo se desconhecido).

### US-005: Proteção de rotas
**Descrição:** Como responsável pela segurança, quero que todo o restante da API exija token.

**Critérios de aceite:**
- [ ] Token ausente, expirado, adulterado ou de algoritmo diferente retorna 401 em `ProblemDetail`.
- [ ] Papel insuficiente retorna 403.

## 3. Requisitos funcionais

- **FR-1:** Registrar clínica e dentista em uma só transação.
- **FR-2:** E-mail único global, salvo em minúsculas.
- **FR-3:** Senha de 10 a 64 caracteres com ao menos uma letra e um número; BCrypt custo 12.
- **FR-4:** Access JWT HS256 de 15 min; refresh token opaco de 7 dias, armazenado como SHA-256.
- **FR-5:** Rotação do refresh em cada uso, com detecção de reuso por `family_id`.
- **FR-6:** Rate limiting em login (IP e IP+e-mail) e registro (IP).
- **FR-7:** Consumir as tabelas `clinics`, `users` e `refresh_tokens` e as funções de bootstrap entregues pelo banco; o backend não cria nem altera objetos de banco.
- **FR-8:** Nenhum campo de plano, trial ou cobrança.

## 4. Fora do escopo

- Recuperação de senha por e-mail e verificação de e-mail.
- Login social, MFA e múltiplos dispositivos com listagem de sessões.
- Perfil, usuários e dados da clínica (F05).

## 5. Considerações técnicas e riscos

- Funções `SECURITY DEFINER` (`core.find_user_for_login`, `core.find_refresh_token`), entregues pelo banco, são o único acesso pré-tenant.
- Rate limit em memória: reinicia a cada restart e não vale entre instâncias; aceitável com 1 instância.
- O refresh vai no corpo da resposta; o armazenamento seguro no React será tratado na spec do frontend.
- **Pergunta aberta:** nenhuma.
