# Feature Contract: F01 - CORS & Health Check Backend

**Feature:** `F01-cors-and-health-backend`  
**Versão:** 1.0.0  
**Consumidores Primários:** Flutter Web App (`https://crm-clinica-ten.vercel.app`), Uptime Monitoring Services (UptimeRobot, Render Health Check Probe, Kubernetes Probe), API Clients.  
**Localização:** `features/F01-cors-and-health-backend/contract.md`  

---

### 1. Contract Summary

Este contrato estabelece as garantias de integração para a política de CORS e os endpoints de verificação de saúde (`/healthz` e `/health`). Ele define os cabeçalhos HTTP aceitos e retornados, o payload dos endpoints públicos de monitoramento e os códigos de status de erro.

---

### 2. Inputs (Requirements & Inbound Protocol)

#### 2.1 Requisição Preflight CORS (`OPTIONS *`)
- **Headers Requeridos do Cliente:**
  - `Origin`: `string (URL da aplicação cliente, ex: https://crm-clinica-ten.vercel.app)`
  - `Access-Control-Request-Method`: `string (GET, POST, PUT, DELETE, OPTIONS, PATCH)`
  - `Access-Control-Request-Headers`: `string (Origin, Content-Type, Accept, Authorization, X-Requested-With)`

#### 2.2 Endpoint de Health Check (`GET /healthz` & `GET /health`)
- **Método HTTP:** `GET`
- **Autenticação:** Nenhuma (Rota pública)
- **Query Parameters:** Nenhum
- **Body / Payload:** Nenhum

---

### 3. Outputs (Responses & Schemas)

#### 3.1 Resposta do Preflight CORS (`OPTIONS`)
- **Status HTTP:** `204 No Content`
- **Cabeçalhos de Resposta Esperados:**
  - `Access-Control-Allow-Origin`: `https://crm-clinica-ten.vercel.app` (Corresponde exatamente à origem enviada se estiver na whitelist)
  - `Access-Control-Allow-Credentials`: `true`
  - `Access-Control-Allow-Methods`: `GET, POST, PUT, DELETE, OPTIONS, PATCH`
  - `Access-Control-Allow-Headers`: `Origin, Content-Type, Accept, Authorization, X-Requested-With`
  - `Access-Control-Max-Age`: `86400`

#### 3.2 Resposta de Sucesso do Health Check (`GET /healthz` - 200 OK)
- **Status HTTP:** `200 OK`
- **Content-Type:** `application/json`
- **Payload Schema:**
  ```json
  {
    "status": "ok",
    "database": "connected",
    "timestamp": "2026-07-24T10:04:00Z",
    "version": "1.0.0"
  }
  ```

#### 3.3 Resposta de Erro do Health Check (`GET /healthz` - 503 Service Unavailable)
- **Status HTTP:** `503 Service Unavailable`
- **Content-Type:** `application/json`
- **Payload Schema:**
  ```json
  {
    "status": "error",
    "database": "disconnected",
    "timestamp": "2026-07-24T10:04:00Z",
    "message": "dial tcp ...:5432: i/o timeout"
  }
  ```

---

### 4. Business Rules & Limits

1. **Whitelist Estrita de Origens:**
   - Origens não presentes em `ALLOWED_ORIGINS` não receberão os cabeçalhos `Access-Control-Allow-Origin` nem `Access-Control-Allow-Credentials`, resultando em bloqueio nativo no navegador do cliente.
2. **Timeout de Ping no Banco de Dados:**
   - O endpoint `/healthz` deve abortar a tentativa de conexão com o banco e retornar HTTP 503 caso o banco não responda em no máximo **3.0 segundos**.
3. **Sem Efeitos Colaterais:**
   - Chamadas aos endpoints de health check são estritamente de leitura (idempotentes) e não devem alterar nenhum registro no banco de dados.

---

### 5. Events Emitted (Side Effects)

- **Nenhum evento síncrono ou assíncrono emitido.**
- *Métrica interna de Log:* Registra em `stdout` alertas quando a checagem do banco falha.

---

### 6. Acceptance Criteria (Integration Tests)

- [ ] Requisição `OPTIONS` com `Origin: https://crm-clinica-ten.vercel.app` responde HTTP 204 com `Access-Control-Allow-Credentials: true`.
- [ ] Requisição `OPTIONS` com origem não permitida (ex: `https://malicious-site.com`) não retorna cabeçalhos de autorização de origem.
- [ ] Requisição `GET /healthz` com banco de dados saudável retorna HTTP 200 e `database: "connected"`.
- [ ] Requisição `GET /healthz` simulando banco offline/unreachable retorna HTTP 503 e `database: "disconnected"` em < 3.1 segundos.
