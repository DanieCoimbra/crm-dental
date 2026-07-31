SaaS de Gestão — Clínica Dental (Dentista Solo)
## Funcionalidades e Workflow de MVP — Versão Reduzida

**Posicionamento:** consultório de 1 cadeira / dentista solo — simplicidade e preço baixo como diferencial, não paridade de features com players enterprise (Clinicorp, Simples Dental, Dental Office).

**Stack:** Flutter (Riverpod, GoRouter, Dio, Hive) · Go (Fiber + GORM + PostgreSQL) · JWT · AES-256 nos campos sensíveis · multi-tenant por `clinic_id`

---

## 1. O que foi cortado da versão anterior (e por quê)

| Removido/adiado | Motivo |
|---|---|
| Fila de espera com "smart booking" | Resolve problema de clínica com múltiplos médicos/agenda cheia — não é a dor do dentista solo |
| Turnos/escala por sala e múltiplos consultórios | Presume múltiplos profissionais |
| RBAC customizável (criar/editar cargos) | Solo dentist tem no máximo 2 papéis fixos, não precisa de matriz de permissões |
| Lixeira com limite de itens, force-delete elaborado | Governança pensada para equipe grande — soft delete simples resolve |
| Offline-first com fila de sincronização | Item mais caro tecnicamente da lista — adiar até validar necessidade real |
| Import/export CSV, logs de auditoria completos | Baixo valor percebido por quem paga R$89-129/mês |

## 2. Papéis (fixos, não customizáveis)

| Papel | Acesso |
|---|---|
| Dentista/Admin | Acesso total: pacientes, odontograma, agenda, orçamento, financeiro, configurações |
| Recepcionista | Cadastro/agenda/confirmação de paciente — sem edição de prontuário clínico nem financeiro |

---

## 3. Funcionalidades por módulo

### Autenticação & Perfil
1. Login / registro de clínica com trial
2. Perfil (nome, e-mail, senha, avatar)
3. Sem seleção de sala/consultório (não se aplica a 1 cadeira)

### Pacientes & Prontuário
1. Cadastro de paciente (dados demográficos, convênio, contato)
2. Busca com paginação
3. Prontuário simplificado: histórico médico + notas por atendimento (campos criptografados)
4. **Odontograma** — mapa dental interativo por dente, com status (hígido, cariado, restaurado, extraído, implante, etc.) e histórico de alterações por dente
5. Upload de arquivos/exames (radiografia, foto intraoral) vinculados ao paciente

### Agenda
1. Calendário único (1 dentista) — visão diária/semanal
2. Criação/edição de agendamento com bloqueio de conflito de horário
3. Horário comercial e feriados (nacionais + customizados) configuráveis
4. **Confirmação automática via WhatsApp** — envio automático de lembrete X horas antes da consulta, não apenas registro manual de log
5. Status do agendamento: agendado → confirmado (via WhatsApp) → em atendimento → finalizado / faltou / cancelado
6. Botões iniciar/finalizar atendimento

### Orçamento & Tratamento (núcleo comercial, ausente na versão anterior)
1. Cadastro de tipos de procedimento com preço-base (ex.: restauração, canal, extração, limpeza)
2. Geração de orçamento a partir dos dentes marcados no odontograma
3. Envio do orçamento ao paciente (PDF simples)
4. Status do orçamento: rascunho → enviado → aprovado / recusado
5. Se aprovado: geração automática de parcelas vinculadas ao plano de tratamento

### Financeiro
1. Contas a receber — vinculadas às parcelas dos orçamentos aprovados
2. Registro de pagamento por parcela (dinheiro, cartão, PIX)
3. Fluxo de caixa simples (entradas do mês, inadimplência)
4. Relatório de faturamento por período

### Documentos
1. Geração simplificada de: atestado, receita, encaminhamento
2. Sem múltiplos tipos elaborados de laudo — só o essencial que o dentista solo usa no dia a dia

### Configurações
1. Dados da clínica
2. Tipos de procedimento (nome, duração, preço, cor na agenda)
3. Horário comercial e feriados

### Dashboard
1. KPIs: total de pacientes, consultas hoje, taxa de retorno, faturamento do mês
2. Gráfico semanal de agendamentos

---

## 4. Workflow do paciente (ciclo principal)

```
Paciente cadastrado
  → Consulta agendada
    → Confirmação automática via WhatsApp
      → Atendimento (odontograma atualizado + notas)
        → [se necessário tratamento]
          → Orçamento gerado a partir do odontograma
            → Enviado ao paciente
              → Aprovado ──────────────┐
              → Recusado (fim)         │
                                       ▼
                              Parcelas geradas
                                → Acompanhamento de pagamento
                                  → Tratamento concluído
```

Regra de transição: assim como no sistema da oficina, todo status é validado no backend (Go), nunca decidido no cliente (Flutter).

---

## 5. Workflow de MVP (fases de desenvolvimento)

### Fase 0 — Fundação
- Multi-tenant simplificado (`clinic_id`), 2 papéis fixos (Dentista/Admin e Recepcionista)
- Auth (login, registro com trial), skeleton Flutter (mobile + web)

### Fase 1 — Núcleo clínico
- Cadastro de paciente
- Odontograma (mapa dental + histórico por dente)
- Prontuário simplificado (notas por atendimento + upload de arquivos/exames)

### Fase 2 — Agenda
- Agendamento com bloqueio de conflito, horário comercial e feriados
- Confirmação automática via WhatsApp (lembrete antes da consulta)
- Status do agendamento (agendado → confirmado → em atendimento → finalizado/faltou/cancelado)

### Fase 3 — Comercial e Financeiro (o núcleo que faltava na versão anterior)
- Tipos de procedimento com preço-base
- Orçamento gerado a partir do odontograma, PDF simples
- Aprovação do paciente → geração de parcelas
- Contas a receber, registro de pagamento por parcela, fluxo de caixa simples

### Fase 4 — Documentos e Dashboard
- Atestado, receita, encaminhamento (geração simplificada)
- Dashboard com KPIs e gráfico semanal

### Fase 5 — Pós-MVP (avaliar conforme demanda real dos clientes)
- Fila de espera inteligente, múltiplas salas/turnos, RBAC customizável, offline-first com sincronização, import/export CSV, auditoria completa — só entram se clientes reais pedirem, não por padrão de "sistema completo"

---

## 6. Notas técnicas de stack

- Reaproveitar a base já construída (Flutter Riverpod/GoRouter/Dio, Go Fiber/GORM/PostgreSQL, JWT, criptografia AES-256 em campos sensíveis) — o problema não é a stack, é o escopo de features
- Cortar do backend as rotas/handlers das features listadas na seção 1 (ou deixar como código morto/desativado, sem consumir tempo de manutenção)
- Priorizar construir odontograma, orçamento/parcelas e automação de WhatsApp antes de qualquer refino nas features que já existem