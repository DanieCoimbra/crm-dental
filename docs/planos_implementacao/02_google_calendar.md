# Plano de Implementação: Integração com API do Google Calendar

## 1. Visão Geral
Integrar o CRM de Clínicas Odontológicas com a API oficial do Google Calendar para sincronização bidirecional de consultas. Cada dentista/médico cadastrado poderá conectar sua conta do Google Workspace / Gmail para que consultas agendadas no CRM apareçam automaticamente na sua agenda pessoal/profissional e alterações na agenda reflitam no CRM.

---

## 2. Tecnologias Aplicáveis & Skills Utilizadas
- **Backend:** Go 1.26 + Fiber v2, `golang.org/x/oauth2`, SDK `google.golang.org/api/calendar/v3`, PostgreSQL (GORM), `crypto/aes` para criptografia em repouso dos tokens OAuth de refresh.
- **Frontend:** Flutter + Riverpod, `url_launcher` (para redirecionamento do consentimento OAuth2 no navegador/deep link), Dio HTTP Client.
- **Segurança & Credenciais:** OAuth 2.0 PKCE / Server Token Swap, Criptografia AES-GCM dos tokens de acesso e refresh no PostgreSQL per tenant/user.

---

## 3. Regras de Negócio
1. **Autorização por Usuário/Dentista:**
   - Apenas usuários com perfil "Dentista" ou "Administrador" podem vincular sua conta do Google Calendar.
2. **Sincronização Automática:**
   - **Criação de Consulta:** Ao criar uma consulta no CRM, um evento correspondente é gerado no Google Calendar com título `[Consulta Dental] Nome do Paciente`, descrição com procedimentos e local da clínica.
   - **Edição / Reagendamento:** Alterações de data/hora no CRM atualizam a `eventId` correspondente no Google Calendar.
   - **Cancelamento:** Ao cancelar no CRM, o evento no Google Calendar é removido ou marcado como cancelado.
3. **Gerenciamento de Erros e Offline:**
   - Se a API do Google estiver indisponível ou o token expirado, a consulta no CRM é salva normalmente e entra em uma fila de retry assíncrono (Job Queue / Worker Go).
4. **Desconexão:**
   - O dentista pode revogar o acesso a qualquer momento na tela de Configurações, removendo os tokens do banco.

---

## 4. Integração Total (Backend + Frontend)

### 4.1. Backend (Go)

#### Data Model (`internal/core/domain/google_calendar.go`)
```go
type UserGoogleAuth struct {
    ID           uint      `gorm:"primaryKey" json:"id"`
    UserID       uint      `gorm:"uniqueIndex;not null" json:"user_id"`
    ClinicID     uint      `gorm:"index;not null" json:"clinic_id"`
    AccessToken  string    `gorm:"type:text;not null" json:"-"` // Encriptado AES-GCM
    RefreshToken string    `gorm:"type:text;not null" json:"-"` // Encriptado AES-GCM
    TokenType    string    `json:"token_type"`
    Expiry       time.Time `json:"expiry"`
    CalendarID   string    `gorm:"default:'primary'" json:"calendar_id"`
    CreatedAt    time.Time `json:"created_at"`
    UpdatedAt    time.Time `json:"updated_at"`
}
```

#### Service / UseCase (`internal/core/usecase/google_calendar_usecase.go`)
- `GetAuthURL(userID uint) (string, error)`: Gera a URL do Google OAuth2 Consent Screen com escopo `https://www.googleapis.com/auth/calendar.events`.
- `HandleCallback(code string, state string) error`: Troca o `code` de autorização pelos tokens de Acesso e Refresh, encripta e salva no DB.
- `SyncAppointmentToGoogle(appointment *domain.Appointment, action string) error`:
  - Utiliza o client da API Google (`calendar.NewService`).
  - Mapeia a consulta para `calendar.Event`.
  - Executa `Events.Insert()`, `Events.Patch()` ou `Events.Delete()`.

#### Worker de Reprocessamento Assíncrono (`internal/pkg/worker/calendar_sync_worker.go`)
- Processa eventos na tabela `pending_calendar_syncs` via goroutines concorrentes com controle de contexto e retries exponenciais.

---

### 4.2. Frontend (Flutter)

#### Integration Screen (`lib/features/settings/presentation/integrations_screen.dart`)
- Exibe o status da conexão com Google Calendar:
  - **Estado Conectado:** Mostra o e-mail Google conectado e botão "Desconectar".
  - **Estado Desconectado:** Exibe botão "Conectar com Google Calendar" com ícone oficial.

#### Flow de Autenticação OAuth2
1. O usuário clica em "Conectar Google Calendar".
2. O app obtém a URL de autorização via GET `/api/v1/integrations/google/auth-url`.
3. O app abre o navegador via `url_launcher`.
4. Após o consentimento, a API Go recebe o callback `/api/v1/integrations/google/callback` e o Flutter escuta o Deep Link / fecha a aba web.
5. O estado do provider Riverpod (`googleCalendarProvider`) é invalidado e atualizado.

---

## 5. Plano de Testes & Validação
1. **Teste de Unidade (Go):** Testar criptografia/descriptografia dos tokens AES-GCM e mapeamento de `Appointment` -> `calendar.Event`.
2. **Teste de Integração (Mock API):** Simular respostas HTTP da Google API (`httptest.Server`) validando renovação de Access Token via Refresh Token.
3. **Teste E2E (Flutter + Dev Server):** Realizar o fluxo completo de agendamento de consulta no Flutter e verificar a inserção do evento em um calendário Google Sandbox de teste.
