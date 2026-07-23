# PRD: Dental Clinic CRM (Fullstack MVP & Lote 7)

## 1. Executive Summary / Introduction
**Problem Statement:**  
Clínicas odontológicas necessitam de uma plataforma centralizada e de alta performance para gerenciar pacientes, prontuários eletrônicos (EMR), agendamentos e operações diárias (estoque, financeiro). O sistema precisa operar de forma estável, não perder arquivos caso os servidores sofram reinicialização (falta de nuvem real no momento), e fornecer dados agregados (Dashboards) para a diretoria, ao mesmo tempo que reduz a taxa de falta de pacientes usando notificações no WhatsApp e melhora a experiência com Modo Escuro.

**Proposed Solution:**  
Expandir a arquitetura moderna Fullstack (Backend em **Go 1.26** e Frontend em **Flutter/Riverpod**) em um modelo SaaS B2B escalável e isolado (Multi-tenant). Além dos módulos operacionais já integrados (Financeiro, Estoque, Prontuário, Assinaturas Stripe), o sistema avançará para o Lote 7, implementando um Dashboard Gerencial para os administradores, sistema de Cupons de Afiliados para atração de novas clínicas, Armazenamento persistente no Supabase Storage para arquivos médicos, alertas via WhatsApp e Modo Escuro Global.

**Goals & Success Metrics:**
- Garantir **100% de isolamento de dados** entre as clínicas via escopos no banco de dados (Multi-tenant).
- Alcançar altíssima performance e confiabilidade técnica na nova stack (Tempo de resposta da API Go < 150ms).
- Otimizar o tempo da recepção automatizando a baixa de insumos (Estoque).
- Eliminar 100% o risco de perda de arquivos e anexos movendo tudo do disco local/efêmero para o Supabase Storage.
- Reduzir em 30% as faltas de pacientes através de notificações automatizadas via WhatsApp.
- Aumentar a taxa de conversão do SaaS utilizando Cupons de Desconto e Parceiros Afiliados.

---

## 2. User Stories

### US-001: Prontuário Eletrônico (EMR) e Evolução Clínica
**Description:** As a **Médico/Dentista**, I want **escrever as evoluções clínicas usando formatação de texto avançada (Rich Text)**, so that **eu mantenha um histórico completo, detalhado e seguro sobre os tratamentos do paciente.**

**Acceptance Criteria:**
- [x] O sistema permite inserir negrito, itálico, cores e tamanhos variados no texto da evolução.
- [x] O histórico clínico possui criptografia de dados em repouso no banco de dados.

### US-002: Workflow de Agenda e Smart Booking
**Description:** As a **Recepcionista**, I want **visualizar uma agenda interativa, registrar o tempo exato das consultas (Start/Finish) e receber sugestões automáticas de encaixe (Smart Booking) se a consulta terminar cedo**, so that **eu otimize a agenda da clínica e maximize o tempo útil do consultório.**

**Acceptance Criteria:**
- [x] Botões "Iniciar Atendimento" e "Finalizar Atendimento" gravam timestamps no backend Go.
- [x] Sugestão automática de fila de espera ao finalizar mais cedo.

### US-003: Controle Automático de Estoque
**Description:** As a **Recepcionista/Administrador**, I want **vincular materiais específicos aos procedimentos odontológicos**, so that **quando o médico finalizar a consulta, os itens gastos sejam abatidos automaticamente do estoque da clínica.**

**Acceptance Criteria:**
- [x] "Receita" de insumos para cada procedimento (ex: 2 luvas, 1 anestésico para "Restauração").
- [x] Gatilho de "Finalizar Atendimento" deduz os itens do `current_stock` no PostgreSQL.
- [x] Alertas visuais (badges) na barra superior ao atingir o limite `minimum_stock`.

### US-004: Módulo Financeiro Interno da Clínica
**Description:** As a **Administrador da Clínica**, I want **lançar receitas, parcelar tratamentos e registrar despesas**, so that **eu tenha controle total do fluxo de caixa e histórico de pagamentos sem depender de planilhas externas.**

**Acceptance Criteria:**
- [x] Capacidade de gerar parcelamentos ("Installments").
- [x] Interface (Frontend) permite quitação de débitos.

### US-005: Assinatura e Pagamentos SaaS
**Description:** As a **Dono do Sistema (Super Admin)**, I want **bloquear automaticamente clínicas com assinaturas/trials vencidos e liberar planos via webhook da Stripe**, so that **eu possa monetizar a plataforma de forma segura e recorrente sem intervenção humana.**

**Acceptance Criteria:**
- [x] Período de Trial permite acesso irrestrito até a data `trial_ends_at`.
- [x] Middleware no Go retorna HTTP `402 Payment Required` em rotas não-financeiras e de escrita (POST, PUT, DELETE) se o trial vencer ou a assinatura atrasar.
- [x] Frontend intercepta status de bloqueio com o `TenantGuardOverlay`.

### US-006: Dashboard Gerencial (F07)
**Description:** As a **Administrador da Clínica**, I want **visualizar gráficos agregados de faturamento, quantidade de consultas e taxa de faltas**, so that **eu avalie a saúde da minha empresa rapidamente.**

**Acceptance Criteria:**
- [ ] Endpoint `/api/v1/dashboard/stats` retorna métricas calculadas.
- [ ] O frontend exibe 4 cards superiores de totalização e 2 gráficos simples no centro.
- [ ] Apenas role `admin` ou `owner` podem ter acesso à rota.

### US-007: Sistema de Cupons e Afiliados - SaaS (F08)
**Description:** As a **Dono do Software (SaaS)**, I want **cadastrar parceiros e fornecer a eles códigos de cupom (promo codes)**, so that **eles possam repassar descontos na hora que a clínica comprar o sistema e receber uma porcentagem em troca.**

**Acceptance Criteria:**
- [ ] Models `Affiliate` e `PromoCode` com CRUD protegido apenas por donos do sistema.
- [ ] Webhook do SaaS reconhece a ativação da clínica via cupom e injeta +X saldo no `Affiliate`.

### US-008: Armazenamento em Nuvem com Supabase (F09)
**Description:** As a **Médico/Usuário**, I want **que arquivos anexados ao EMR sejam salvos na nuvem**, so that **eles permaneçam em segurança absoluta mesmo que a infraestrutura local sofra perdas (Render ephemeral disks).**

**Acceptance Criteria:**
- [ ] Reestruturar `PatientFileHandler` para utilizar SDK oficial S3 ou AWS SDK conectada ao bucket do Supabase Storage.
- [ ] A tabela salvará o Path ou Signed URL ao invés do caminho local no HD.

### US-009: Tema e Modo Escuro Global (F10)
**Description:** As a **Usuário**, I want **um botão (Toggle) que inverta a paleta de cores para o Modo Escuro**, so that **descanse meus olhos em usos noturnos do sistema.**

**Acceptance Criteria:**
- [ ] Suporte nativo `ThemeMode.dark` implementado no `MaterialApp` via Provider.
- [ ] Salvar preferência no Secure Storage.

### US-010: Integração de Notificações WhatsApp (F11)
**Description:** As a **Recepcionista**, I want **que as notificações de agendamento de amanhã sejam disparadas via WhatsApp de forma robótica**, so that **diminua o número de pacientes que faltam esquecendo a consulta.**

**Acceptance Criteria:**
- [ ] Tela `/settings/whatsapp` onde a clínica cadastra chave/instância do provedor (Ex: Z-API / Evolution API).
- [ ] Rotina Cron no backend Go que escaneia consultas para `amanhã` e envia POST HTTP assíncrono para o provedor de disparo.

### US-011: Pagamentos Reais com Cartão e PIX via Stripe (F12)
**Description:** As a **Dono de Clínica**, I want **pagar a assinatura inserindo meus dados de cartão no app ou escaneando um QR Code PIX gerado pela Stripe**, so that **o sistema seja ativado automaticamente sem telas de simulação.**

**Acceptance Criteria:**
- [ ] Formulário Customizado de Cartão de Crédito integrado ao App (Flutter Stripe / Stripe Elements).
- [ ] Geração dinâmica e exibição de QR Code do PIX + função Copia e Cola, orquestrados pela API da Stripe (PaymentIntents).
- [ ] Feedback visual atualizando a tela assim que o pagamento for concluído com sucesso.

---

## 3. Functional Requirements

- **FR-1:** O backend processa autenticação de usuários emitindo tokens JWT, assegurando escopo `clinic_id` local.
- **FR-2:** Entidades usam exclusão "Soft Delete" centralizada com Lixeira de Reciclagem.
- **FR-3:** Monetário tratado internamente sempre usando `int64` (centavos) nas structs Go e PostgreSQL.
- **FR-4:** O arquivo não deve ultrapassar 10MB no envio pro Storage.
- **FR-5:** Middleware SaaS não pode bloquear requisições de leitura GET puras para evitar inutilizar visualização, travando apenas métodos MUTÁVEIS (POST, PUT, DELETE, PATCH).

---

## 4. Non-Goals (Out of Scope)
- **Painel Externo de Afiliados:** Os afiliados parceiros do SaaS não terão login, telas ou portal exclusivo neste momento. O dono do sistema administrará, visualizará saldos e efetuará depósitos manuais por fora.
- **Chatbot Inteligente IA no WhatsApp:** Disparos serão simples notificações textuais unilaterais, sem menu, chat, e sem atendimento automático.
- **App Mobile Nativo (iOS/Android):** Foco em Flutter Compilação Web (SPA Responsivo) para Desktops/Tablets de recepção.

---

## 5. Technical Specifications & Design Considerations

### Architecture Overview
- **Frontend (Flutter):** `Riverpod` gerenciando estado. Padrão Clean Architecture (`lib/features`). UI em Material 3 sobria e limpa.
- **Backend (Go 1.26):** Framework web rápido `Fiber v2`.
- **Database:** Supabase (PostgreSQL) servindo as bases e `Supabase Storage (API S3)` armazenando binários de imagem/pdf.

### O Padrão Multi-Tenant (Isolamento B2B)
- A implementação usa queries `Where("clinic_id = ?", id)` atreladas aos handlers pelo middleware de token que extrai essa informação a cada request. Nenhuma tabela desprotegida deve existir.

### Integration Points
- **Stripe API (stripe-go):** Webhook recebendo eventos da fatura gerando a transição de status para Assinatura (SaaS).
- **Z-API / Evolution:** Provedor de API externa consumida pelo client nativo HTTP do Go para disparar os WhatsApps.
- **Supabase Storage:** S3 Compatível, operado pela library oficial da Amazon SDK `aws-sdk-go` para garantir uplaod escalável de chunk de memória.

---

## 6. Risks & Open Questions

**Technical Risks:**
- **Armazenamento de Storage Lento:** O Render reiniciar constantemente não é o problema, o problema é a rede subir imagens grandes (> 5MB) de clientes lentos e manter o socket de HTTP do Fiber travado aguardando envio à Nuvem. Deve-se considerar streaming pass-through ou signed-upload-urls no front em um V2.
- **Confiabilidade da API Whatsapp Externa:** Caso a API externa caia ou retorne 500, o envio da mensagem pode se perder. Será necessário um campo "notificado_em" no agendamento para re-tentativas (retry).

**Open Questions:**
- **Google Calendar Multi-usuário:** A conta do Google Calendar a ser sincronizada é uma global da clínica (Master Account) ou cada médico vai autorizar individualmente na tela de Perfil? (Recurso postergado para análise).
