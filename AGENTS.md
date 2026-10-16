# SaaS de Gestão — Clínica Dental (Dentista Solo)
## Funcionalidades e Workflow de MVP — Versão Reduzida

**Posicionamento:** consultório de 1 cadeira / dentista solo — simplicidade e preço baixo como diferencial, não paridade de features com players enterprise (Clinicorp, Simples Dental, Dental Office).

**Stack:** Next.js (JavaScript, CSS) · Java (Spring Boot + PostgreSQL) · JWT · AES-256 nos campos sensíveis · multi-tenant por `clinic_id`

---

## 1. Papéis (fixos, não customizáveis)

| Papel          | Acesso                                                                                    |
| -------------- | ----------------------------------------------------------------------------------------- |
| Dentista/Admin | Acesso total: pacientes, odontograma, agenda, orçamento, financeiro, configurações        |
| Recepcionista  | Cadastro/agenda/confirmação de paciente — sem edição de prontuário clínico nem financeiro |

---

## 2. Funcionalidades por módulo

### Autenticação & Perfil
1. Login / registro de clínica com trial
2. Perfil (nome, e-mail, senha, avatar)
3. Sem seleção de sala/consultório (não se aplica a 1 cadeira)

### Pacientes & Prontuário
1. Cadastro de paciente (dados demográficos, convênio, contato)
2. Busca com paginação
3. Prontuário simplificado: histórico médico + notas por atendimento (campos criptografados)
4. Upload de arquivos/exames (radiografia, foto intraoral) vinculados ao paciente

### Agenda
1. Calendário único (1 dentista) — visão diária/semanal
2. Criação/edição de agendamento com bloqueio de conflito de horário
3. Horário comercial e feriados (nacionais + customizados) configuráveis
4. Status do agendamento: agendado → confirmado  → em atendimento → finalizado / faltou / cancelado
5. Botões iniciar/finalizar atendimento

### Orçamento & Tratamento (núcleo comercial)
1. Cadastro de tipos de procedimento com preço-base (ex.: restauração, canal, extração, limpeza)
2. Envio do orçamento ao paciente (PDF simples)
3. Status do orçamento: rascunho → enviado → aprovado / recusado
4. Se aprovado: geração automática de parcelas vinculadas ao plano de tratamento

### Financeiro
1. Contas a receber — vinculadas às parcelas dos orçamentos aprovados
2. Registro de pagamento por parcela 
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

## 3. Workflow do paciente (ciclo principal)

```
Paciente cadastrado
  → Consulta agendada
    → Confirmação 
      → Atendimento 
        → [se necessário tratamento]
          → Orçamento
            → Enviado ao paciente
              → Aprovado ──────────────┐
              → Recusado (fim)         │
                                       ▼
                              Parcelas geradas
                                → Acompanhamento de pagamento
                                  → Tratamento concluído
```

Regra de transição: todo status é validado no backend (Java/Spring Boot), nunca decidido no cliente (Next.js).

---

## 4. Workflow de MVP (fases de desenvolvimento)

### Fase 0 — Fundação
- Multi-tenant simplificado (`clinic_id`), 2 papéis fixos (Dentista/Admin e Recepcionista)
- Auth (login, registro com trial), skeleton Next.js (web)

### Fase 1 — Núcleo clínico
- Cadastro de paciente
- Prontuário simplificado (notas por atendimento + upload de arquivos/exames)

### Fase 2 — Agenda
- Agendamento com bloqueio de conflito, horário comercial e feriados
- Status do agendamento (agendado → confirmado → em atendimento → finalizado/faltou/cancelado)

### Fase 3 — Comercial e Financeiro (o núcleo que faltava na versão anterior)
- Tipos de procedimento com preço-base
- Orçamento gerado PDF simples
- Contas a receber, registro de pagamento por parcela, fluxo de caixa simples

### Fase 4 — Documentos e Dashboard
- Atestado, receita, encaminhamento (geração simplificada)
- Dashboard com KPIs e gráfico semanal


---

## 5. Notas técnicas de stack

- Usar Next.js, Java, Spring Boot, PostgreSQL, JWT e criptografia AES-256 em campos sensíveis
- Usar arquitetura de microserviços separando: Fase 0 e Fase 1, Fase 2, Fase 3 e Fase 4
- O banco de dados será único; somente o backend terá microserviços.