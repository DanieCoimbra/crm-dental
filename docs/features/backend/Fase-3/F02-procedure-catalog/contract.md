# Contract — F02 Catálogo de procedimentos e procedimento na agenda

## 1. Resumo
- **Feature:** F02-procedure-catalog
- **Versão:** 1.0.0
- **Consumidores:** Frontend React (configuração de procedimentos, seleção no orçamento e na agenda), F03 (`ProcedureTypeLookup.quotes/requireActive`) e `scheduling`. Requer `Authorization: Bearer` da Fase 0.

## 2. Entradas

Prefixo `/api/v1`. **D** = `DENTIST_ADMIN`, **R** = `RECEPTIONIST`. Valores monetários em texto decimal (`"250.00"`).

| Método | Rota | Papéis | Corpo / query |
|---|---|---|---|
| GET | `/procedure-types` | D, R | `?active=true\|false\|all` (padrão `true`) |
| GET | `/procedure-types/{id}` | D, R | — |
| POST | `/procedure-types` | D | `{ "name": "2..80", "basePrice": "0..999999.99", "defaultDurationMinutes": 5..720 (múltiplo de 5), "color": "#RRGGBB" }` |
| PUT | `/procedure-types/{id}` | D | os quatro campos acima + `"version": long` |
| PATCH | `/procedure-types/{id}/active` | D | `{ "active": bool, "version": long }` |
| POST | `/appointments` | D, R | + `"procedureTypeId": uuid?` (campo opcional) |
| PUT | `/appointments/{id}` | D, R | + `"procedureTypeId": uuid?` — **substitui**: ausente ou `null` remove o vínculo |

`clinicId`, `createdBy`, `id`, `active` (nas escritas de criação/edição) e `status` do corpo são ignorados.

## 3. Saídas

### Sucesso
- **Procedimento** (201 com `Location: /api/v1/procedure-types/{id}`; 200 nas demais; `Cache-Control: no-store`):
  ```json
  { "id": "uuid", "name": "Restauração", "basePrice": "250.00", "defaultDurationMinutes": 45,
    "color": "#3B82F6", "active": true, "version": 0 }
  ```
- **Agendamento** (Fase 2) ganha `"procedure": { "id": "uuid", "name": "string", "color": "#RRGGBB" } | null`.

### Erros (formato da Fase 0)
| Status | Quando |
|---|---|
| 400 `validation` | Nome fora de 2–80 ou em branco; `basePrice` mal formado ou fora de 0–999999.99; duração fora de 5–720 ou não múltipla de 5; cor inválida; `active` de listagem inválido |
| 401 `unauthorized` | Token ausente ou inválido |
| 403 `forbidden` | R em `POST`/`PUT`/`PATCH` do catálogo |
| 404 `not-found` | Procedimento inexistente ou de outra clínica |
| 409 `conflict` | Nome ativo repetido (qualquer caixa); `version` defasada; reativar nome já ativo |
| 422 `business-rule` | `reason: PROCEDURE_INACTIVE` ao agendar com procedimento inativo |

## 4. Regras e limites
- Nome único por clínica **entre os ativos**, sem diferenciar caixa; inativo pode ser reutilizado.
- Preço de 0 a R$ 999.999,99; duração de 5 a 720 min em passos de 5; cor `#RRGGBB`.
- Não existe `DELETE`: inativar não afeta orçamentos nem agendamentos já gravados; alterar o preço **não** muda orçamentos existentes (snapshot do banco).
- O catálogo não calcula a duração do agendamento: o front propõe `endsAt` com `defaultDurationMinutes`.

### Interfaces internas
```java
// crm_data.catalog.service.ProcedureTypeLookup (usada por scheduling e commercial)
void requireActive(UUID procedureTypeId);                       // 404 | 422 PROCEDURE_INACTIVE
Map<UUID, ProcedureBrief> briefs(Collection<UUID> ids);          // record ProcedureBrief(UUID id, String name, String color)
Map<UUID, ProcedureQuote> quotes(Collection<UUID> ids);          // record ProcedureQuote(UUID id, String name, BigDecimal basePrice, boolean active)
```

## 5. Eventos emitidos (internos)
| Evento | Gatilho | Payload |
|---|---|---|
| `ProcedureType.Created` | Após o commit da criação | `{procedureTypeId, userId}` |
| `ProcedureType.Updated` | Após editar | `{procedureTypeId, userId}` |
| `ProcedureType.ActiveChanged` | Após ativar/inativar | `{procedureTypeId, active, userId}` |

## 6. Critérios de aceite (integração)
- [ ] D cria, edita e inativa; R recebe 403 nas três; sem token, 401; D e R leem.
- [ ] Nome repetido entre ativos (outra caixa) → 409; outra clínica passa; inativo reaproveitado passa; reativar colidindo → 409.
- [ ] Todos os campos inválidos aparecem juntos no 400.
- [ ] Agendar com procedimento inativo → 422 `PROCEDURE_INACTIVE`; de outra clínica → 404; sem procedimento segue funcionando.
- [ ] `PUT /appointments/{id}` sem `procedureTypeId` remove o vínculo; com o mesmo valor mantém.
- [ ] A listagem da agenda de 50 agendamentos executa uma consulta de procedimentos (sem N+1).
- [ ] Nenhum nome ou preço de procedimento em log; nenhuma linha de outra clínica (filtro desligado).
- [ ] Testes das Fases 1 e 2 verdes; `ddl-auto=validate` passa com `ProcedureType` e `Appointment` alterada.
