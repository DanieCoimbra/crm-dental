# Feature Technical Specification: F03-Smart Agenda

## 1. Technical Overview
- **Feature**: F03-Smart Agenda & Waitlist
- **Tech Stack**: Go (Fiber, GORM) no Backend, Flutter (Riverpod, `calendar_view`) no Frontend. Deploy no Render com banco Supabase PostgreSQL.
- **Architecture Approach**: API RESTFul interagindo com algoritmos heurísticos para cálculo de encaixes em tempo real, **sem dependência de APIs ou calendários externos**.

## 2. Data Models & Schema
- **Database Tables**:
  - `appointments`: `id`, `clinic_id`, `doctor_id`, `patient_id`, `start_time`, `end_time`, `actual_start_time`, `actual_end_time`, `status`, `notes`.
  - `waitlists`: `id`, `clinic_id`, `patient_id`, `preferred_time_range`, `urgency_level`, `notes`, `status`.

## 3. Component Architecture (UI)
- `ScheduleScreen`: Tela dividida entre o `WeekView` (calendário dinâmico) e o `_WaitlistPanel` (gestão lateral).
- `AppointmentFormDialog`: Criação manual de agenda com validação de horário de funcionamento, bloqueios de feriados e conflitos.
- `SmartBookingDialog`: Modal reativo disparado assim que um cancelamento ou fim de consulta precoce libera tempo ocioso significativo, sugerindo pacientes da fila de espera que se encaixam no slot.

## 4. Core Logic & Algorithms
- **Operation: Smart Booking Calculation (`CheckMatches`)**
  - **Trigger**: Usuário cancela ou finaliza precocemente uma consulta gerando folga na agenda do médico.
  - **Step 1**: Frontend calcula os minutos ociosos baseados na distância entre o horário atual e a próxima consulta do dia daquele médico.
  - **Step 2**: Sendo a folga utilizável (ex: >= 60 min), chama a API.
  - **Step 3**: Go consulta a tabela `waitlists`, filtrando compatibilidade (mesmo médico/qualquer médico) e priorizando pelos níveis de urgência (`urgency_level` = Alta > Média > Baixa).
  - **Step 4**: Retorna os melhores candidatos ao Flutter para ação rápida de encaixe.

## 5. Error Handling & Security
- **Concorrência (Double Booking)**: O repositório Go checa a rotina `HasOverlap` imediatamente antes do Insert/Update no banco validando as faixas de tempo (`new_start < end AND new_end > start`) isoladas pelo `clinic_id` e `doctor_id`.
- **Authorization (RBAC)**: Regra de middleware bloqueia estritamente usuários da Role "doctor" de criarem, editarem ou cancelarem agendamentos de terceiros, delegando essa atribuição para "receptionist" ou "manager". Médicos possuem acesso apenas de visualização, com permissão específica para as ações de Iniciar (`/start`) e Finalizar (`/finish`) sua própria consulta.
