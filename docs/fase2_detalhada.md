# Especificação Detalhada: Fase 2 — Agenda & Confirmações

**Projeto:** SaaS de Gestão — Clínica Dental (Dentista Solo)  
**Documento Relacionado:** [workflow.md](file:///C:/IgnisPath/Crm-Clinica/docs/workflow.md)  
**Data:** 2026-09-04  

---

## 1. Visão Geral da Fase 2

A **Fase 2 (Agenda & Confirmação)** foca na gestão de consultas do dentista solo, evitando conflitos de horários, organizando a lista do dia e automatizando a confirmação via WhatsApp.

---

## 2. Ciclo de Vida do Agendamento (Status Stream)

O agendamento possui os seguintes estados estritamente validados no backend (Go):

- `scheduled`: Consulta criada.
- `confirmed`: Consulta confirmada pelo paciente (manual ou via link/notificação WhatsApp).
- `in_progress`: Dentista iniciou o atendimento no consultório.
- `finished`: Atendimento concluído.
- `missed`: Paciente não compareceu à consulta (Faltou).
- `cancelled`: Consulta cancelada.

---

## 3. Endpoints Backend (Go / Fiber)

| Método | Endpoint | Descrição |
|---|---|---|
| `GET` | `/api/v1/appointments` | Lista agendamentos do período (`?start=YYYY-MM-DD&end=YYYY-MM-DD`) |
| `POST` | `/api/v1/appointments` | Cria novo agendamento com validação de sobreposição, horário comercial e feriados |
| `PUT` | `/api/v1/appointments/:id` | Atualiza dados da consulta |
| `DELETE` | `/api/v1/appointments/:id` | Cancela/deleta agendamento |
| `POST` | `/api/v1/appointments/:id/confirm` | Altera status para `confirmed` |
| `POST` | `/api/v1/appointments/:id/start` | Altera status para `in_progress` |
| `POST` | `/api/v1/appointments/:id/finish` | Altera status para `finished` e aciona deduções |
| `POST` | `/api/v1/appointments/:id/miss` | Altera status para `missed` |
| `POST` | `/api/v1/appointments/:id/whatsapp-link` | Gera o link wa.me com mensagem padronizada de lembrete/confirmação |

---

## 4. Frontend & Telas (Flutter / Riverpod)

1. **Visão Semanal / Diária da Agenda**:
   - Badges coloridos por status:
     - 🔵 `scheduled` (Agendado - Azul)
     - 🟢 `confirmed` (Confirmado - Verde Esmeralda)
     - 🟡 `in_progress` (Em Atendimento - Amarelo/Dourado)
     - ⚪ `finished` (Finalizado - Cinza/Slate)
     - 🔴 `missed` (Faltou - Vermelho)
     - ⬛ `cancelled` (Cancelado)
2. **Ações Rápidas no Card de Agendamento**:
   - Botão **"Confirmar (WhatsApp)"**: Abre WhatsApp com mensagem pré-formatada e atualiza status para `confirmed`.
   - Botão **"Iniciar Atendimento"**: Registra horário de início real e altera status para `in_progress`.
   - Botão **"Finalizar Atendimento"**: Registra horário de encerramento e altera status para `finished`.
   - Botão **"Marcar Falta"**: Altera status para `missed`.
