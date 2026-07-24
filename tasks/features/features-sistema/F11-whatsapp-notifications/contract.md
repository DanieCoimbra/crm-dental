# Contract: F11-whatsapp-notifications

## 1. Overview
Interface assíncrona baseada em filas e jobs (Cron) para envio textual. A clínica deve possuir uma conta de WhatsApp Gateway terceira.

## 2. Inputs (API Requests)
- `POST /api/v1/settings/whatsapp`
  - Body: `{ "api_url": "https://api...", "api_key": "123456" }`

## 3. Outputs (API Responses)
- Apenas HTTP 200/204 para configuração.
- O resultado principal não possui resposta HTTP (Background task).

## 4. Integration Rules
- A thread de cron deve injetar dependências do `db` (GORM) independentemente da requisição HTTP (pois roda sem Contexto de Request do Fiber).
- As mensagens têm template predefinido e formatado: "Olá [nome], lembrete de sua consulta em [data]".

## 5. Boundaries
- Chat interativo não está no escopo (o sistema não vai ler respostas dos pacientes).
