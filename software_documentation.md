# Documentação Completa da Arquitetura (CRM Clínico)

Este documento centraliza toda a inteligência arquitetural, padrões de código e mapeamento de funcionalidades do sistema CRM Clínico, servindo como o mapa definitivo para desenvolvimento e manutenção futura.

---

## 1. Visão Geral e Tech Stack

O software foi projetado como um **SaaS Multi-tenant**, preparado para abrigar múltiplas clínicas (`clinics`) rodando de forma isolada no mesmo banco de dados. 

- **Backend:** Laravel 10 (PHP) operando estritamente como API RESTful.
- **Frontend:** Next.js 14 (React) no padrão SPA Client-side para interatividade rápida, usando Vite/Webpack por baixo dos panos e CSS Vanilla para estilo.
- **Banco de Dados:** MySQL/SQLite relacional com chaves estrangeiras rigorosas.
- **Autenticação:** Laravel Sanctum (Baseado em Sessão/Cookies para SPAs, não em Tokens JWT expostos).

---

## 2. Mapa de Funcionalidades (Features e Código)

Esta seção lista todas as funcionalidades prontas no sistema, descrevendo a lógica de negócio de cada uma e a localização exata de onde os códigos residem.

### 2.1. Gestão de Pacientes (CRM)
- **Como funciona:** Cadastro completo do paciente (Nome, CPF, Data de Nascimento, Telefone). O sistema calcula dinamicamente a idade do paciente com base na data de nascimento e o exibe no perfil.
- **Onde encontrar no Backend:** 
  - `app/Models/Patient.php` (Modelo)
  - `app/Http/Controllers/PatientController.php` (Lógica CRUD)
- **Onde encontrar no Frontend:** 
  - `src/app/patients/page.js` (Tabela de Listagem)
  - `src/app/patients/[id]/page.js` (Página de Perfil do Paciente)

### 2.2. Prontuário Eletrônico Avançado (EMR)
- **Como funciona:** Dividido em três sub-abas essenciais atreladas a um paciente específico.
  1. **Evolução Clínica:** Textos ricos (Rich Text) gerados pelo médico detalhando a consulta.
  2. **Prescrições & Atestados:** Formulários que geram receitas em HTML prontas para impressão limpa.
  3. **Arquivos & Exames:** Upload de anexos, imagens clínicas e Raio-X com organização por tags visuais.
- **Onde encontrar no Backend:**
  - Controllers separados: `ClinicalEvolutionController.php`, `PrescriptionController.php` e `PatientFileController.php`.
- **Onde encontrar no Frontend:**
  - `src/components/PatientEMR.js` (Componente Master que controla as abas)
  - `src/components/RichTextEditor.js` (Componente customizado do editor de texto)

### 2.3. Agenda e Gestão de Consultas
- **Como funciona:** Calendário interativo para marcação de consultas. Regras estritas impedem que consultas sejam agendadas fora dos horários configurados.
- **Onde encontrar no Backend:** 
  - `app/Http/Controllers/AppointmentController.php`
- **Onde encontrar no Frontend:** 
  - `src/app/page.js` (A interface pesada que monta o `react-big-calendar`)
  - Modals de criação/edição estão acoplados e controlados via states no próprio `page.js`.

### 2.4. Timer de Atendimento (Workflow de Consulta)
- **Como funciona:** Botões "Iniciar Atendimento" e "Finalizar Atendimento" presentes no Modal da Agenda. Ao clicar, o sistema grava a hora exata (`actual_start_time` e `actual_end_time`), permitindo no futuro extrair relatórios precisos do tempo que a consulta realmente demorou, comparado ao que foi agendado.
- **Onde encontrar no Backend:** Métodos `start()` e `finish()` no `AppointmentController.php`.
- **Onde encontrar no Frontend:** Funções `handleStartConsultation` e `handleFinishConsultation` no arquivo `src/app/page.js`.

### 2.5. Cálculo Inteligente de Encaixe (Smart Booking)
- **Como funciona:** Quando uma consulta é cancelada ou "Finalizada Antecipadamente" (sobrando tempo), o sistema pega os minutos livres, vai até a Fila de Espera, cruza dados e retorna uma lista de pacientes (priorizando Urgência) que cabem perfeitamente no "buraco" gerado. Uma janela de pop-up salta na tela do usuário sugerindo ligar para eles e encaixá-los ali com 1 clique.
- **Onde encontrar no Backend:** 
  - `app/Services/SmartBookingService.php` (Toda a lógica matemática complexa).
  - Injetado nos métodos `destroy` e `finish` do `AppointmentController.php`.
- **Onde encontrar no Frontend:** 
  - `src/components/SmartBookingModal.js` (A interface visual do pop-up).
  - Disparado dentro dos `catch/then` de `handleDelete` e `handleFinish` no `page.js`.

### 2.6. Fila de Espera (Waitlist)
- **Como funciona:** Onde os recepcionistas cadastram pacientes que precisam de horário mas não têm vaga. Podem ser definidos com Nível de Urgência, Dias e Turnos de preferência.
- **Onde encontrar no Backend:** `app/Models/Waitlist.php` e `WaitlistController.php`.
- **Onde encontrar no Frontend:** `src/app/waitlist/page.js`.

### 2.7. Lixeira de Reciclagem (Soft Deletes)
- **Como funciona:** Quando a exclusão de algo crítico (como um Paciente ou Documento) acontece, o registro não some do banco. A data é salva na coluna `deleted_at`. O usuário acessa a aba "Lixeira" e pode recuperar o arquivo clicando em Restaurar. Há limite de espaço de lixeira para contas do tipo trial (SaaS).
- **Onde encontrar no Backend:** 
  - `app/Http/Controllers/TrashController.php`
  - `app/Services/TrashService.php`
- **Onde encontrar no Frontend:** `src/app/trash/page.js`.

### 2.8. Integração e Disparo de WhatsApp
- **Como funciona:** Exibe um registro claro e limpo de histórico das notificações que o sistema tentou disparar (lembretes de consulta, felicitações, etc) para auditar se as mensagens foram entregues com sucesso aos pacientes.
- **Onde encontrar no Backend:** `WhatsAppLogController.php` e modelo `WhatsAppLog`.
- **Onde encontrar no Frontend:** `src/app/whatsapp-logs/page.js`.

---

## 3. Arquitetura do Backend (Conceitos Core)

O backend segue o padrão **MVC evoluído**.

### 3.1. O Coração Multi-tenant (Trait)
Para garantir que a Clínica A não veja os dados da Clínica B, usamos uma estratégia global.
- **Técnica:** O Trait `App\Traits\Tenantable.php` é anexado aos Models.
- **Funcionamento:** Ele intercepta as queries do Eloquent adicionando automaticamente `where('clinic_id', auth()->user()->clinic_id)` utilizando o `static::addGlobalScope`. Quando um dado é salvo, ele preenche o `clinic_id` sozinho no evento `creating`.

### 3.2. Separação de Módulos (Controllers x Services)
- **Controllers:** Recebem a requisição, limpam variáveis e devolvem JSON.
- **Services:** Classes puras para lógica pesada (Ex: `SmartBookingService`). Mantém os controladores curtos e legíveis.

---

## 4. Arquitetura do Frontend (React / Next.js)

O frontend foi desenhado pensando na performance do usuário e microinterações para dar a sensação de um App Nativo.

### 4.1. Gestão de Estado
- O estado é gerido com `useState` e `useEffect`.
- Para estado global (como exibição de caixas de Diálogo, Alertas e Modais de Confirmação), usamos a `Context API` através do `AlertContext.js` para não poluir o código com passagem infinita de *props*.

### 4.2. Interface e CSS
- Tudo centralizado no `src/app/globals.css` via **CSS Variables** (ex: `var(--accent)`, `var(--bg-primary)`).
- Isso garante que alterar a cor primária da clínica ou acionar o **Dark Mode** exija apenas a troca de 5 ou 6 linhas de código no topo do CSS.
- Não utilizamos lixo visual (CSS inline desorganizado). A UI se baseia numa hierarquia de contêineres limpos.

---

## 5. Guia Prático de Manutenção

Para adicionar uma nova funcionalidade, siga este checklist rigoroso:

1. **Alterar Banco (Migrations):** 
   - Nunca edite tabelas existentes via SQL direto. Use sempre `php artisan make:migration add_coluna_to_tabela_table`.
2. **Atualizar Modelo e Validação:**
   - Adicione o novo campo no array `$fillable` dentro de `app/Models/NomeDoModel.php`.
3. **Modificar a Interface (React):**
   - No frontend, amarre seu novo `<input>` a um estado `useState` e não esqueça de enviá-lo no payload do `axios.post()`.
4. **Respeite as Cores e Estilos:**
   - Nunca use códigos Hex (ex: `#3b82f6`) soltos. Sempre prefira `var(--accent)`. Isso previne que a aplicação fique "inquebrável" perante as trocas de tema.
