# Fluxo de Rotas (DentalCRM)

Este documento mapeia todas as rotas (URLs/telas) declaradas no frontend (em app_router.dart) e descreve exatamente como o usuário final pode alcançá-las navegando pela interface do sistema.

## 1. Rotas Públicas (Sem Autenticação)

### `/login` (Tela de Login)
- **Fluxo Principal:** É o ponto de entrada padrão. Qualquer usuário não autenticado que acessar a raiz do sistema será redirecionado para cá.
- **Fluxos Alternativos:**
  - Após o usuário realizar o Log out (Sair) através do menu na barra superior (Topbar).
  - Após o usuário realizar um pagamento na tela de Checkout (SaaS), o sistema faz o log out automático e o redireciona para cá.

### `/register` (Tela de Cadastro)
- **Fluxo:** Acessada exclusivamente a partir da tela de Login, clicando no texto/botão "Não possui conta? Cadastre-se".

---

## 2. Rotas Protegidas e Coercitivas (SaaS)

### `/saas-checkout` (Checkout de Assinatura)
- **Fluxo:** É uma rota isolada do painel principal (Dashboard). O usuário apenas chega aqui se for interceptado pelo componente TenantGuardOverlay. 
- **Gatilhos de Interceptação:**
  1. O Trial de 14 dias da clínica expirou.
  2. O pagamento da fatura falhou ou atrasou.
  3. A assinatura foi cancelada.
- **Ação:** Nesses cenários, a tela inteira é bloqueada com um aviso, e o único caminho possível é clicar no botão "Regularizar Assinatura", que redireciona para esta rota.

---

## 3. Rotas Autenticadas (Internas do Painel)

Todas as rotas a seguir dependem que o usuário esteja logado e com a assinatura da clínica em dia.

### `/dashboard` (Painel Inicial)
- **Fluxo:** Rota padrão assim que o usuário faz login com sucesso. Também pode ser acessada clicando em "Dashboard" no menu lateral esquerdo (Sidebar).

### `/patients` (Listagem de Pacientes)
- **Fluxo:** Acessada clicando em "Pacientes" no menu lateral esquerdo (Sidebar).

### `/patients/:id/emr` (Prontuário Eletrônico do Paciente)
- **Fluxo:** Estando na tela de listagem de pacientes (`/patients`), o usuário deve clicar em cima de um paciente específico (na tabela/lista) para abrir o seu respectivo Prontuário Eletrônico.

### `/schedule` (Agenda Inteligente)
- **Fluxo:** Acessada clicando em "Agenda" no menu lateral esquerdo (Sidebar).

### `/financial` (Módulo Financeiro)
- **Fluxo:** Acessada clicando em "Financeiro" no menu lateral esquerdo (Sidebar). 
- **Restrição:** Visível e acessível apenas se a clínica possuir o plano Premium.

### `/inventory` (Controle de Estoque)
- **Fluxo:** Acessada clicando em "Estoque" no menu lateral esquerdo (Sidebar).
- **Restrição:** Visível e acessível apenas se a clínica possuir os planos Pro ou Premium.

### `/marketing` (Marketing & Cupons)
- **Fluxo:** Acessada clicando em "Marketing (Cupons)" no menu lateral esquerdo (Sidebar).
- **Restrição:** Exige que o usuário logado tenha cargo de admin, manager ou owner.

### `/trash` (Lixeira / LGPD)
- **Fluxo:** Acessada clicando em "Lixeira (LGPD)" no menu lateral esquerdo (Sidebar).
- **Restrição:** Exige que o usuário logado tenha cargo de admin, manager ou owner.

### `/settings` (Configurações da Clínica)
- **Fluxo:** Acessada clicando em "Configurações" no menu lateral esquerdo (Sidebar).
- **Restrição:** Exige que o usuário logado tenha cargo de admin, manager ou owner.

---

## Análise de Rotas Perdidas (Órfãs)
Após análise minuciosa de toda a árvore de roteamento do Flutter (GoRouter em app_router.dart), conclui-se que NENHUMA rota encontra-se perdida ou inacessível. 

Todas as 12 rotas mapeadas na aplicação possuem um caminho claro de acesso via interface visual (seja por um botão no Sidebar, uma linha de tabela, um link, ou o funil do SaaS), sem exceções. O sistema está perfeitamente coeso.
