# Feature Contract: F04 - End-to-End Verification & Keep-Alive Strategy

**Feature:** `F04-e2e-verification-and-keepalive`  
**Versão:** 1.0.0  
**Consumidores Primários:** Engenharia DevOps, Uptime Monitoring Cron (UptimeRobot), QA.  
**Localização:** `features/F04-e2e-verification-and-keepalive/contract.md`  

---

### 1. Contract Summary

Este contrato estabelece os critérios de sucesso e os SLAs de verificação E2E e indisponibilidade para a integração Vercel <-> Render.

---

### 2. Inputs (Probes & Tests)

- **Probe URL:** `GET https://crm-clinica-gjss.onrender.com/healthz`
- **Frequência Requerida:** A cada 12 minutos
- **Origem dos Testes E2E:** `https://crm-clinica-ten.vercel.app`

---

### 3. Outputs (SLA & Validation Metrics)

#### 3.1 Métricas de Saúde (Uptime SLA)
- **Disponibilidade Esperada:** >= 99.5%
- **Tempo de Resposta Máximo do `/healthz`:** < 500ms
- **Taxa de Erros de CORS:** 0%

---

### 4. Business Rules & Limits

1. **Janela Máxima de Inatividade:**
   - O intervalo entre solicitações automáticas de keep-alive não deve exceder 14 minutos para garantir que a VM efêmera do Render permaneça em estado aquecido (*warm*).

---

### 5. Events Emitted (Side Effects)

- **`System.WarmUpPing`:** Executado periodicamente via `GET /healthz`.

---

### 6. Acceptance Criteria (Integration Validation)

- [ ] Chamada periódica a cada 12 min mantém o tempo de resposta inicial de login em < 1s.
- [ ] Checklist completo de 5 passos E2E finalizado sem inconsistências.
