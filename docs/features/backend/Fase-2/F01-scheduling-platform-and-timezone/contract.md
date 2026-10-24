# Contract — F01 Plataforma da agenda e fuso da clínica

## 1. Resumo
- **Feature:** F01-scheduling-platform-and-timezone
- **Versão:** 1.0.0
- **Consumidores:** Frontend React (leitura do fuso), features F02 a F05 (interfaces internas) e o consumidor de `audit-log`. Requer `Authorization: Bearer` da Fase 0.

## 2. Entradas

Prefixo `/api/v1`.

| Método | Rota | Papéis | Corpo / query |
|---|---|---|---|
| GET | `/clinic` | D, R | — |
| PUT | `/clinic` | D | `{name, document?, phone?, email?, address?, timezone? (≤ 50), version}` |
| GET | `/audit-log` | D | `tableName` agora aceita `appointments` (demais filtros iguais à Fase 1) |

## 3. Saídas

### Sucesso
- **Clínica** (200): `{ "id", "name", "document", "phone", "email", "address", "timezone": "America/Sao_Paulo", "version" }`.
- **Auditoria** (200): `AuditEntryResponse` da Fase 1, com `tableName: "appointments"`; nunca valores de colunas.

### Erros (formato da Fase 0)
| Status | Quando |
|---|---|
| 400 `validation` | `timezone` desconhecido (campo `timezone`) |
| 401 `unauthorized` | Token ausente ou inválido |
| 403 `forbidden` | `RECEPTIONIST` em `PUT /clinic` |
| 409 `conflict` | `version` defasada; (regras globais) `23P01` "Horário já ocupado.", `CR003` "Paciente anonimizado.", `CR004` "Cancele os agendamentos futuros antes de anonimizar.", `CR005` "Paciente inativo." |
| 500 `internal` | Fuso inválido gravado por fora; sessão sem usuário (`28000`) |

## 4. Regras e limites
- O fuso é o único dado de "hoje" do sistema; nenhuma feature usa constante de fuso para a agenda.
- Só `DENTIST_ADMIN` altera o fuso. Mudar o fuso não revalida agendamentos.
- O `DETAIL` do driver nunca é devolvido nem logado.

### Interfaces internas
```java
// crm_data.clinic.service.ClinicZones
ZoneId zone();

// crm_data.patient.service.PatientLookup (acréscimos)
void requireSchedulable(UUID patientId);              // 404 | 409 anonimizado | 409 inativo
Map<UUID, PatientBrief> briefs(Collection<UUID> ids); // PatientBrief(id, name, phone)

// crm_data.shared.error.BusinessException
BusinessException(ProblemTypes type, String message, String reason); // reason opcional
```

## 5. Eventos emitidos (internos)
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] `ddl-auto=validate` passa contra V001–V021.
- [ ] `GET /clinic` devolve `timezone`; `PUT /clinic` com `timezone` válido grava; inválido → 400; sem o campo mantém o atual; R → 403.
- [ ] `POST /auth/register` cria a clínica com `America/Sao_Paulo`.
- [ ] `GET /audit-log?tableName=appointments` → 200.
- [ ] O teste de arquitetura falha se `patient`, `clinic` ou `shared` importarem `scheduling`.
- [ ] Respostas das rotas da agenda levam `Cache-Control: no-store`.
