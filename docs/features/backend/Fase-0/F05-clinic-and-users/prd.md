# PRD — F05 Clínica e Usuários

**Derivação:** PRD global, US-004, US-005 e US-006; FR-6 e FR-10.
**Dependências externas:** F04 (access token e `TokenPrincipal`), F02 (tenant), função banco (tabelas `clinics`/`users`/`refresh_tokens` e RLS), F01 (erros). Requer o token emitido pela F04.

## 1. Introdução

- **Problema:** após o registro, o dentista precisa manter os dados da clínica, delegar a agenda a recepcionistas e cada usuário precisa gerir o próprio perfil.
- **Solução:** endpoints de perfil, dados da clínica e gestão de recepcionistas, com regras de papel validadas no servidor.
- **Metas:**
  1. 100% das rotas validam papel no servidor (matriz papel × endpoint testada).
  2. Id de usuário de outra clínica sempre retorna 404.
  3. Desativação, troca e reset de senha revogam os refresh tokens do usuário afetado.
  4. Cobertura ≥ 80% nos services da feature.

## 2. User stories

### US-001: Ver e editar meu perfil
**Descrição:** Como usuário, quero ver e editar nome e e-mail.

**Critérios de aceite:**
- [ ] `GET /me` e `PUT /me` operam só sobre o usuário do token.
- [ ] E-mail já usado retorna 409.
- [ ] O papel e a clínica não podem ser alterados por esta rota.

### US-002: Trocar minha senha
**Descrição:** Como usuário, quero trocar minha senha informando a atual.

**Critérios de aceite:**
- [ ] Senha atual errada retorna 401; nova igual à atual retorna 422.
- [ ] Os refresh tokens do usuário são revogados após a troca.

### US-003: Dados da clínica
**Descrição:** Como dentista, quero editar os dados da clínica.

**Critérios de aceite:**
- [ ] `GET /clinic` para os dois papéis; `PUT /clinic` só para o dentista (recepcionista recebe 403).
- [ ] Edição concorrente retorna 409 (`@Version`).

### US-004: Cadastrar recepcionista
**Descrição:** Como dentista, quero cadastrar recepcionistas para delegar a agenda.

**Critérios de aceite:**
- [ ] `POST /users` só para o dentista e sempre cria `RECEPTIONIST` na própria clínica.
- [ ] E-mail já usado retorna 409.
- [ ] Recepcionista recebe 403 em qualquer rota `/users`.

### US-005: Desativar e reativar usuário
**Descrição:** Como dentista, quero desativar um recepcionista que saiu da clínica.

**Critérios de aceite:**
- [ ] `PATCH /users/{id}/active` não permite desativar a si mesmo nem o dentista (422).
- [ ] Desativar revoga os refresh tokens do alvo; ele não consegue login nem refresh.

### US-006: Redefinir senha de recepcionista
**Descrição:** Como dentista, quero redefinir a senha de um recepcionista que a esqueceu.

**Critérios de aceite:**
- [ ] `PUT /users/{id}/password` só funciona para `RECEPTIONIST` da própria clínica.
- [ ] Revoga os refresh tokens do alvo.

## 3. Requisitos funcionais

- **FR-1:** Perfil, troca de senha, clínica e usuários conforme o contract.
- **FR-2:** O `clinic_id` nunca vem do cliente; ids de rota são resolvidos dentro do tenant.
- **FR-3:** Um dentista por clínica; o dentista não pode ser desativado nem rebaixado.
- **FR-4:** Listagem de usuários paginada (`size` máx. 50).
- **FR-5:** Auditoria (`created_by`, `updated_at`, `version`) preenchida.
- **FR-6:** Emitir `Auth.UserSessionsRevoked` quando sessões forem revogadas.

## 4. Fora do escopo

- Avatar (depende de arquivos, Fase 1).
- Excluir usuário (apenas desativar).
- Mais de um dentista por clínica e papéis customizados.
- Recuperação da senha do próprio dentista (procedimento manual de suporte).

## 5. Considerações técnicas e riscos

- Access tokens já emitidos de um usuário desativado valem até 15 min; a desativação bloqueia apenas o refresh e novos logins.
- Alterar o e-mail do próprio usuário não invalida o access token atual (o `sub` é o id).
- **Pergunta aberta:** nenhuma.
