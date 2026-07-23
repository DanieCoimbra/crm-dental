# Action Plan: F11-whatsapp-notifications

## 1. Local Scope
- **Derivation**: Extracted from [PRD Dental CRM Fullstack](../../prd-dental-crm-fullstack.md) (US-010) e [Spec](../../spec-dental-crm-fullstack.md).
- **Responsibility**: Realizar o disparo assíncrono de notificações transacionais via WhatsApp para pacientes alertando sobre consultas agendadas para reduzir faltas.

## 2. External Dependencies (Before starting)
- Requires `F01-multi-tenant-core` para cadastro de chaves no settings da clínica (`app_settings`).
- Requires `F03-smart-agenda` pois lerá a tabela `appointments`.

## 3. Execution Phases
- **Phase 1**: [Setup & Contracts]
  - Criar entidade `AppSetting` (Go) para salvar instâncias/tokens da Z-API ou Evolution API da clínica.
- **Phase 2**: [Local Spec & Logic]
  - Desenvolver `WhatsAppService` responsável pelo HTTP Client que faz o POST da mensagem.
  - Implementar um cronjob (Goroutine) que roda uma vez por dia filtrando consultas de `date == amanhã`.
- **Phase 3**: [Integration]
  - Adicionar tela de Configurações (`/settings/whatsapp`) no Flutter para o admin salvar as credenciais.

## 4. Next Steps
- Run `/spec-write` in this folder to map the cron logic and error handling (retries).
- Run `/contract` to establish the HTTP interface for settings saving.
