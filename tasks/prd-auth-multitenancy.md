# PRD: Autenticação, Multitenancy e RBAC

## 1. Resumo Executivo (Executive Summary)
- **Declaração do Problema**: O Dental Clinic CRM necessita de um sistema base de cadastro e login capaz de isolar os dados de múltiplas clínicas (Multitenancy). Além disso, deve suportar diferentes níveis de permissão (RBAC - Role-Based Access Control) para os funcionários, garantindo que cada clínica controle seu próprio ambiente sem vazamento de informações para terceiros.
- **Solução Proposta**: Implementar uma arquitetura de login/cadastro restrita. O cadastro externo será exclusivo para Clínicas (Owners). Os funcionários serão cadastrados apenas internamente, pelo Owner, que definirá as credenciais iniciais. Um forte esquema de validação multitenant será aplicado no backend utilizando JWT, e o frontend em Flutter entregará um feedback visual claro e atalhos de teclado (Enter).
- **Metas e Métricas de Sucesso**:
  - 100% de isolamento dos dados (Garantia de que requisições só retornam dados da clínica correspondente).
  - Tolerância Zero para vazamentos entre domínios.
  - Rate Limiting implementado no login (bloqueio de 15 minutos).
  - Feedback visual (erros inline e Toasts) funcionando para 100% dos formulários de autenticação.

---

## 2. Histórias de Usuário (User Stories)

### US-001: Cadastro de Nova Clínica (Owner)
**Descrição:** Como um usuário novo (Futuro Owner), eu quero poder cadastrar minha clínica fornecendo Nome da Clínica, Meu Nome, CNPJ, Email e Senha, para começar a usar o sistema.
**Critérios de Aceitação:**
- [ ] Todos os campos são obrigatórios.
- [ ] A senha deve ter no mínimo 8 caracteres, contendo pelo menos letras e números.
- [ ] Pressionar a tecla "Enter" no teclado aciona o botão de cadastro.
- [ ] Erros de formulário (ex: senha curta, email inválido) aparecem em texto vermelho logo abaixo do campo (inline).
- [ ] Após sucesso no cadastro, a tela volta automaticamente para a página de Login e aguarda o usuário logar.
- [ ] Verificar UI no navegador.

### US-002: Login Multi-perfil (Owner e Funcionários)
**Descrição:** Como usuário (Owner ou Funcionário), quero fazer login com meu Email e Senha para acessar os dados restritos da minha clínica.
**Critérios de Aceitação:**
- [ ] O frontend exibe validações inline para campos em branco e formato de email.
- [ ] O frontend exibe um Toast/Snackbar de erro caso o backend retorne "Credenciais Inválidas".
- [ ] A tecla "Enter" no teclado submete o formulário de login.
- [ ] Após o login, o JWT retornado contém o ID da Clínica (Tenant) e o Papel (Role) do usuário.
- [ ] Verificar UI no navegador.

### US-003: Rate Limiting de Autenticação
**Descrição:** Como sistema, quero bloquear tentativas repetidas de erro de senha, para evitar invasão por força bruta.
**Critérios de Aceitação:**
- [ ] Se o usuário errar a senha 5 vezes consecutivas, a conta do email digitado sofre um bloqueio de 15 minutos.
- [ ] Se tentar logar durante o bloqueio, o backend retorna erro HTTP adequado e o frontend exibe o aviso do tempo restante via Toast.

### US-004: Gestão e Cadastro de Funcionários (Owner)
**Descrição:** Como Owner da clínica, quero criar contas para meus funcionários informando seus emails, definindo suas senhas iniciais e selecionando seus papéis (Roles).
**Critérios de Aceitação:**
- [ ] A tela de gestão permite escolher o papel do funcionário: Administrador, Dentista ou Recepcionista.
- [ ] O sistema automaticamente vincula o novo usuário ao ID da Clínica do Owner.
- [ ] A senha gerada pelo Owner para o funcionário segue as regras padrão de segurança (Mínimo 8 caracteres, alfanumérico).

### US-005: Controle de Acesso no Dashboard
**Descrição:** Como funcionário, eu quero ver apenas os módulos e dados pertinentes à minha função, na minha clínica.
**Critérios de Aceitação:**
- [ ] O frontend adapta o menu principal de acordo com a `Role` do funcionário autenticado (ex: Recepcionista não vê relatórios financeiros, se houverem).
- [ ] Toda query no banco de dados filtra as requisições atrelando ao ID da clínica logada.

### US-006: Recuperação de Senha Segregada
**Descrição:** Como Owner, quero recuperar minha senha por email. E como funcionário, devo pedir para meu Owner trocar.
**Critérios de Aceitação:**
- [ ] O fluxo de "Esqueci minha senha" funciona para Owners e envia link/token para o email.
- [ ] Caso um Funcionário tente usar o fluxo, o sistema avisa: "Por favor, solicite a redefinição de senha para o proprietário ou administrador da clínica".

---

## 3. Requisitos Funcionais (Functional Requirements)
- **FR-1:** O cadastro (Sign Up) deve injetar uma nova `Clinic` e um novo `User` (Role = OWNER) em uma única transação de Banco de Dados.
- **FR-2:** No login (Sign In), o servidor deve fornecer um Token JWT assinado contendo, no payload, os claims: `user_id`, `clinic_id`, e `role`.
- **FR-3:** O Frontend (Flutter) deverá possuir interceptadores no `Dio` (ou gerenciamento Riverpod) que capturem retornos 401/403 de forma global, mostrando Toasts via ScaffoldMessenger.
- **FR-4:** Um Middleware no Fiber (Backend) será responsável por ler o JWT, validar a assinatura, e adicionar o `clinic_id` e `role` ao contexto HTTP (`c.Locals()`) em todas as rotas protegidas.
- **FR-5:** Nas telas de Flutter, mapear a propriedade `onFieldSubmitted` nos TextFields e associá-las aos nós de foco para criar o comportamento nativo da tecla "Enter".

---

## 4. Fora de Escopo (Non-Goals)
- Implementação de convites por email para que funcionários criem a própria conta (o Owner cadastra e entrega a credencial inicial).
- Redefinição de senha automática por email para funcionários.
- Perfis/Roles customizáveis e granulares (Nesta fase, apenas as três citadas + Owner).
- Telas iniciais completamente diferentes (todos usarão a mesma Dashboard base, mas os componentes serão ocultados via regras de visibilidade RBAC).

---

## 5. Especificações Técnicas e Considerações de Design
- **Stack:** Fiber (Go) no Back-end; Flutter (Dart) com Riverpod no Front-end.
- **Banco de Dados (Supabase/PostgreSQL):**
  - Tabela `clinics`: `id (uuid)`, `name`, `cnpj`, `created_at`.
  - Tabela `users`: `id (uuid)`, `clinic_id (fk uuid)`, `name`, `email`, `password_hash`, `role (enum/string)`, `failed_attempts (int)`, `locked_until (timestamp)`.
- **Validações UI (Flutter):**
  - Utilizar a classe `FormState` para validações inline nos TextFields (`errorText`).
  - Utilizar pacote tipo `bot_toast` ou `ScaffoldMessenger` nativo para notificações flutuantes.
- **Tratamento de Rate Limiting:**
  - Armazenado nas colunas `failed_attempts` e `locked_until` na tabela `users` do banco de dados, incrementadas a cada tentativa de validação de bcrypt falha.

---

## 6. Riscos e Questões em Aberto
- **Risco de Multitenancy:** O pior cenário é um vazamento horizontal (clínica X vê clínica Y). **Mitigação:** Criação de um padrão arquitetural no Go em que NENHUM repositório executa SELECT/UPDATE/DELETE sem receber o `clinic_id` do context do Fiber, atuando como um filtro obrigatório em toda query.
- **Migrações:** O esquema do Supabase precisará ser configurado e migrado para garantir integridade relacional entre `users` e `clinics`.
