# Technical Spec: F11-whatsapp-notifications

## 1. Technical Overview
- **Feature**: Disparo de Lembretes Automáticos via WhatsApp
- **Tech Stack Used**: Go (Goroutines, `time.Ticker`, `net/http`).
- **Architecture Approach**: Worker (Background Job) embutido no servidor Go (Scheduler), consumindo API externa de mensagens (ex: Evolution API ou Z-API).

## 2. Data Models & Schema
- **Database Changes**:
  - `app_settings`: `clinic_id (FK)`, `whatsapp_api_url (varchar)`, `whatsapp_api_key (varchar)`.
  - `appointments` (Alteração): Coluna `whatsapp_notified (boolean) DEFAULT false`.

## 3. Component Architecture
- `WhatsappSettingsScreen` (Frontend): Formulário para o dono da clínica informar as chaves da sua instância de WhatsApp externa.

## 4. Core Logic & Algorithms
- **Operation**: Worker de Disparo
  - Step 1: `cronService` dispara (ex: todo dia as 08:00 AM) no backend.
  - Step 2: Puxa agendamentos com `date = 'amanhã'` E `whatsapp_notified = false`.
  - Step 3: Puxa o `app_settings` correspondente do `clinic_id`.
  - Step 4: Monta Payload JSON e dispara POST assíncrono para o Provedor.
  - Step 5: Marca `whatsapp_notified = true`.

## 5. Error Handling & Edge Cases
- **Scenario**: Provedor retorna Timeout.
  - **Handling**: Registra falha no Log do Go, mantém `notified = false` para re-tentar na próxima varredura.

## 6. Security & Performance
- **Security Check**: As chaves `whatsapp_api_key` devem ser salvas criptografadas de forma reversível ou em texto plano dependendo do compliance no DB.
- **Performance Targets**: Goroutines evitam travamento da main thread, disparando dezenas de POSTs via fan-out simultâneo (max 10 workers).
