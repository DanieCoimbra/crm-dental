# PRD Global (Backend) — CRM Clínica Dental

**Escopo deste documento:** backend Java do SaaS de gestão para dentista solo. O detalhamento por módulo/fase é feito depois, no `breakdown`. A primeira entrega planejada em detalhe é a **Fase 0 — Fundação**.

**Fonte:** `docs/arq_inicial.md` + decisões da entrevista (2026-10-19).

## Decisões fechadas

| # | Decisão | Valor |
|---|---|---|
| D1 | Arquitetura | Monólito modular, um deploy, banco PostgreSQL único |
| D2 | Spring Boot / Java | 4.1.1 / 21 |
| D3 | Pacote base | `crm_data` (mantido) |
| D4 | Sessão | Access JWT de 15 min + refresh token rotativo e revogável |
| D5 | Isolamento de tenant | Filtro Hibernate por `clinic_id` do token **+** RLS no PostgreSQL (defesa em profundidade) |
| D6 | Trial | **Não há trial.** O produto é 100% gratuito; não existe plano, cobrança nem expiração de conta |
| D7 | Frontend | React (consumidor sem confiança) |
| D8 | Schema | Dono é a função **banco** (`postgres-dba-architect`): tabelas, papéis, RLS, funções e migrations em `docs/global/banco/`. O backend só consome o contrato (spec global, seção 2) |

## Padrões adotados (sem pergunta; contestar se discordar)

- Chave primária `UUID`; datas `TIMESTAMPTZ`; dinheiro `NUMERIC(12,2)`.
- Senhas com BCrypt; refresh token opaco, guardado só como hash SHA-256.
- Papéis fixos: `DENTIST_ADMIN` e `RECEPTIONIST`.
- Erros em RFC 9457 `ProblemDetail`; OpenAPI (springdoc) **desligado em produção**.
- Rate limiting no login; CORS restrito à origem do React; Actuator health.
- Docker multi-stage + `docker-compose` (app + PostgreSQL); segredos só por variáveis de ambiente.
- Testes: JUnit 5, `@WebMvcTest` com segurança e Testcontainers com PostgreSQL real.

---

## 1. Resumo executivo

**Problema:** dentistas solo não têm um sistema de gestão simples e barato; as opções existentes são caras e cheias de recursos que não usam.

**Solução:** uma API REST em monólito modular que cobre o ciclo paciente → agenda → atendimento → orçamento → financeiro, gratuita e segura (LGPD: dados clínicos criptografados, isolamento por clínica).

**Metas e métricas (backend, Fase 0):**
1. 100% dos endpoints de negócio exigem JWT válido; zero endpoint autenticado aceita `clinic_id` vindo do corpo ou da URL.
2. Teste de integração prova que um usuário da clínica A recebe 404/vazio ao acessar dados da clínica B, **com o filtro Hibernate desligado** (a RLS sozinha bloqueia).
3. Login responde em ≤ 300 ms (p95) com até 4 requisições concorrentes em ambiente local (BCrypt custo 12; ver F04).
4. Cobertura de testes ≥ 80% nas classes de serviço do módulo de autenticação.
5. Build reprodutível: `mvn verify` passa em máquina limpa usando só o `pom.xml` e o Docker (Testcontainers).

---

## 2. Módulos (monólito modular)

Cada módulo é um pacote de topo em `crm_data`, com camadas internas `web`, `service`, `domain`, `repository`. Um módulo só acessa outro por sua interface `service` pública, nunca pelo repositório alheio.

| Módulo | Fase | Responsabilidade |
|---|---|---|
| `auth` | 0 | Registro, login, refresh, logout, perfil |
| `clinic` | 0 | Dados da clínica, usuários da clínica e papéis |
| `patient`, `record` | 1 | Pacientes, prontuário criptografado, arquivos/exames |
| `scheduling` | 2 | Agenda, conflito de horário, feriados, status |
| `billing`, `finance` | 3 | Procedimentos, orçamento (PDF), parcelas, caixa |
| `documents`, `dashboard` | 4 | Atestado/receita/encaminhamento, KPIs |
| `shared` | todas | Segurança, tenant, criptografia, erros, auditoria |

---

## 3. User stories — Fase 0

### US-001: Registrar clínica
**Descrição:** Como dentista, quero registrar minha clínica e minha conta para começar a usar o sistema sem pagar.

**Critérios de aceite:**
- [ ] `POST /api/v1/auth/register` cria clínica e usuário `DENTIST_ADMIN` na mesma transação.
- [ ] E-mail duplicado retorna 409; senha com menos de 10 caracteres retorna 400 com a lista de campos inválidos.
- [ ] Nenhum campo de plano, trial ou expiração é criado ou retornado.
- [ ] Retorna access e refresh token.

### US-002: Login
**Descrição:** Como usuário, quero entrar com e-mail e senha para acessar os dados da minha clínica.

**Critérios de aceite:**
- [ ] `POST /api/v1/auth/login` retorna access token (15 min) com `sub`, `clinic_id` e `role`, mais o refresh token.
- [ ] Credencial inválida retorna 401 com mensagem genérica (sem revelar se o e-mail existe).
- [ ] Mais de 5 falhas em 1 minuto por IP+e-mail retornam 429.

### US-003: Renovar e encerrar sessão
**Descrição:** Como usuário, quero renovar a sessão sem digitar a senha e poder encerrá-la.

**Critérios de aceite:**
- [ ] `POST /api/v1/auth/refresh` troca o refresh token por um novo par; o antigo fica inválido.
- [ ] Reapresentar um refresh já usado revoga toda a cadeia daquele usuário (detecção de reuso).
- [ ] `POST /api/v1/auth/logout` revoga o refresh token atual.

### US-004: Gerenciar usuários da clínica
**Descrição:** Como dentista/admin, quero cadastrar recepcionistas para delegar a agenda.

**Critérios de aceite:**
- [ ] `POST /api/v1/users` só é permitido para `DENTIST_ADMIN` e cria apenas usuários da própria clínica.
- [ ] `RECEPTIONIST` recebendo 403 em qualquer rota de usuários, exceto o próprio perfil.
- [ ] Dentista pode desativar um usuário; usuário desativado não consegue login nem refresh.

### US-005: Perfil
**Descrição:** Como usuário, quero ver e editar meu nome, e-mail e senha.

**Critérios de aceite:**
- [ ] `GET/PUT /api/v1/me` opera sempre sobre o usuário do token.
- [ ] Troca de senha exige a senha atual e revoga os refresh tokens existentes.
- [ ] Avatar fica **fora** da Fase 0 (depende do armazenamento de arquivos, Fase 1).

### US-006: Dados da clínica
**Descrição:** Como dentista/admin, quero editar os dados da minha clínica.

**Critérios de aceite:**
- [ ] `GET /api/v1/clinic` está disponível para ambos os papéis; `PUT /api/v1/clinic` só para `DENTIST_ADMIN`.
- [ ] O `clinic_id` nunca é aceito na requisição.

### US-007: Isolamento de tenant provado
**Descrição:** Como responsável pelo produto, quero garantia técnica de que uma clínica não vê dados de outra.

**Critérios de aceite:**
- [ ] **(Banco)** Toda tabela de negócio tem `clinic_id NOT NULL` indexado e política RLS ativa (`FORCE ROW LEVEL SECURITY`).
- [ ] **(Banco)** A aplicação conecta com um papel sem `BYPASSRLS` e sem ser dono das tabelas.
- [ ] A cada transação, o `clinic_id` do token é definido via `SET LOCAL app.clinic_id`.
- [ ] Testcontainers: com dois tenants, consultas da clínica A nunca retornam linhas da B, inclusive via SQL nativo.

---

## 4. Requisitos funcionais — Fase 0

- **FR-1:** O sistema deve permitir registrar uma clínica com seu primeiro usuário `DENTIST_ADMIN`.
- **FR-2:** O sistema não deve implementar trial, planos, cobrança ou expiração de conta.
- **FR-3:** O sistema deve autenticar por e-mail e senha e emitir access JWT (15 min) e refresh token rotativo.
- **FR-4:** O access JWT deve conter `sub` (id do usuário), `clinic_id` e `role`; o `clinic_id` usado em qualquer operação deve vir apenas dele.
- **FR-5:** O sistema deve revogar refresh tokens no logout, na troca de senha, na desativação do usuário e na detecção de reuso.
- **FR-6:** O sistema deve restringir rotas por papel (`DENTIST_ADMIN`, `RECEPTIONIST`) no servidor.
- **FR-7:** O sistema deve isolar dados por `clinic_id` em duas camadas: filtro Hibernate e RLS no PostgreSQL (a RLS é entregue pela função banco; o backend define o tenant e o filtro).
- **FR-8:** O sistema deve oferecer um componente de criptografia AES-256-GCM para campos sensíveis (usado a partir da Fase 1), com chave via variável de ambiente e identificador de versão da chave gravado junto do dado para permitir rotação.
- **FR-9:** O sistema deve devolver todos os erros como `ProblemDetail`, sem stack trace.
- **FR-10:** O sistema deve registrar auditoria mínima (`created_at`, `updated_at`, `created_by`) nas tabelas de negócio.
- **FR-11:** O sistema deve expor `/actuator/health` (liveness e readiness) e nenhum outro endpoint do Actuator sem autenticação.
- **FR-12:** O sistema deve aplicar rate limiting no login e no registro.

## 5. Requisitos de plataforma (pom.xml e infraestrutura)

- **FR-13:** O `pom.xml` deve usar o parent `spring-boot-starter-parent 4.1.1` (GA) e nenhum repositório de snapshots.
- **FR-14:** Dependências da Fase 0: `web`, `data-jpa`, `validation`, `security`, `actuator`, driver `postgresql`, biblioteca JWT, `springdoc` (somente perfil dev) e, em teste, `spring-security-test`, Testcontainers (PostgreSQL) e JUnit 5.
- **FR-15:** Perfis `dev`, `test` e `prod`; `ddl-auto=validate` e Flyway embutido desligado; nenhum segredo no repositório; `.env.example` documentado.
- **FR-16:** `Dockerfile` multi-stage com usuário não-root e `HEALTHCHECK`; `docker-compose.yml` com app e PostgreSQL.

---

## 6. Fases seguintes (visão, sem detalhe aqui)

| Fase | Módulos | Destaques de backend |
|---|---|---|
| 1 | `patient`, `record` | Busca paginada, campos de prontuário em AES-256-GCM, upload de exames com validação de tipo/tamanho e checagem de tenant no download |
| 2 | `scheduling` | Conflito de horário (constraint de exclusão no PostgreSQL), feriados, máquina de estados do agendamento |
| 3 | `billing`, `finance` | Máquina de estados do orçamento, PDF, geração de parcelas com `BigDecimal` e arredondamento explícito |
| 4 | `documents`, `dashboard` | Geração de documentos, KPIs agregados |

## 7. Fora do escopo (Non-Goals)

- Trial, planos pagos, cobrança, gateway de pagamento.
- Papéis customizáveis ou permissões granulares.
- Microserviços, mensageria e gateway.
- Múltiplas cadeiras/dentistas por clínica, agenda multi-profissional.
- Recuperação de senha por e-mail e verificação de e-mail (dependem de provedor de e-mail; ficam como pergunta aberta).
- Upload de avatar e de exames (Fase 1).
- Integrações com convênios, WhatsApp e NF-e.

## 8. Considerações técnicas e de segurança

- **Fluxo:** React → `/api/v1/**` → filtro JWT → controller → service (`@Transactional`, define `app.clinic_id`) → repository → PostgreSQL (RLS).
- **Segurança:** Spring Security stateless; BCrypt; chave de assinatura JWT e chave AES por variável de ambiente; logs sem senha, token ou conteúdo de prontuário; consultas só por JPA/JPQL parametrizadas.
- **LGPD:** dados de saúde são dados sensíveis. Criptografia em nível de aplicação nos campos clínicos, mínimo de dados nos logs e base para exportação e exclusão por paciente (Fase 1).

## 9. Riscos e perguntas abertas

**Riscos técnicos**
1. **Login antes de existir tenant (resolvido pelo banco).** A RLS exige `app.clinic_id`, mas no login só se conhece o e-mail. *Proposta:* função `SECURITY DEFINER` (`core.find_user_for_login(email)`) que devolve só os dados de autenticação, com `EXECUTE` concedido apenas ao papel da aplicação.
2. **Registro cria a clínica antes de haver tenant (resolvido pelo banco).** *Proposta:* a aplicação gera o UUID da clínica, executa `SET LOCAL app.clinic_id` com ele e só depois insere clínica e usuário, com a política de `INSERT` validando que `clinic_id = app.clinic_id`.
3. **E-mail único.** *Proposta:* e-mail único global (um e-mail pertence a uma só clínica), o que simplifica o login. Impede o mesmo profissional de atuar em duas clínicas com o mesmo e-mail.
4. **Rotação da chave AES** exige recriptografia dos dados antigos; a versão da chave gravada junto do dado permite fazê-la de forma gradual.
5. **Pool de conexões + `SET LOCAL`**: deve estar sempre dentro de transação, para o valor não vazar entre requisições.

**Decisões das perguntas (2026-10-19)**
- Riscos 1, 2 e 3 aprovados como propostos. O cadastro (`/auth/register`) cria clínica + dentista; recepcionistas só são criados pelo dentista (US-004).
- Sem recuperação de senha por e-mail: não há integração com provedor de e-mail.
- Arquivos de exame (Fase 1): disco do container.
- Deploy: API Java no Render. **PostgreSQL em deploy separado**, provedor ainda em análise (critérios na seção 2.1 da spec).

**Riscos derivados**
6. **Disco do container no Render é efêmero** (sem Persistent Disk, os exames somem a cada deploy/restart). Antes da Fase 1, anexar um Persistent Disk (plano pago) ou migrar para object storage. A camada de armazenamento deve ficar atrás de uma interface para trocar sem alterar os módulos.
7. **Sem recuperação de senha**, uma senha esquecida do dentista bloqueia a clínica. Mitigação a definir na spec (ex.: dentista redefine a senha de recepcionistas; para o próprio dentista, procedimento manual de suporte).

## 10. Rollout

MVP = Fases 0 a 4 em um único deploy, liberadas por módulo. A Fase 0 é pré-requisito de todas as outras.
