# Contract — F05 Auditoria e anonimização

## 1. Resumo
- **Feature:** F05-audit-and-anonymization
- **Versão:** 1.0.0
- **Consumidores:** Frontend React (ficha do paciente, tela de auditoria do dentista). Requer `Authorization: Bearer` da Fase 0 com papel `DENTIST_ADMIN`.

## 2. Entradas

Prefixo `/api/v1`. Todas as rotas: **D** apenas (R recebe 403).

| Método | Rota | Corpo / query |
|---|---|---|
| POST | `/patients/{id}/anonymize` | — (sem corpo) |
| GET | `/audit-log` | `?tableName=patients\|medical_histories\|clinical_notes\|patient_files (optional)&recordId=uuid (optional; exige tableName)&page=0&size=20 (1–50)` |

`{id}` é uuid. A clínica vem só do token.

## 3. Saídas

### Sucesso
- **Anonimização** (200): `{ "anonymizedAt": "instant", "storageCleanup": "COMPLETED|PENDING" }`.
- **Página de auditoria** (200, `Cache-Control: no-store`): `{ "content": [AuditEntryResponse], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0 }` com
  ```json
  { "id": 42, "userId": "uuid", "userName": "string|null",
    "tableName": "patients|medical_histories|clinical_notes|patient_files",
    "recordId": "uuid", "action": "INSERT|UPDATE|DELETE|ANONYMIZE", "occurredAt": "instant" }
  ```
  ordenada por `occurredAt` e `id` decrescentes.

### Erros (formato da Fase 0)
| Status | Quando |
|---|---|
| 400 `validation` | `recordId` sem `tableName`; `tableName` desconhecido; `size` fora de 1–50 |
| 401 `unauthorized` | Token ausente ou inválido |
| 403 `forbidden` | Papel `RECEPTIONIST` |
| 404 `not-found` | Paciente inexistente ou de outra clínica (`P0002`) |
| 409 `conflict` | Paciente ativo (`CR001`); retenção vigente (`CR002`, com "Liberado em AAAA-MM-DD"); já anonimizado (`CR003`) |
| 500 `internal` | Sessão sem usuário ou clínica (`28000`); sem detalhe |

## 4. Regras e limites
- A anonimização só vale para paciente **inativo**, da própria clínica, com a retenção (último registro clínico + 20 anos) vencida; paciente sem nenhum registro clínico pode ser anonimizado assim que inativo.
- Efeito no banco (função `core.anonymize_patient`): dados pessoais zerados ou substituídos, nascimento reduzido ao ano, histórico e notas apagados, exames marcados como removidos, ação `ANONYMIZE` na auditoria. Irreversível.
- A limpeza dos objetos no Storage ocorre **depois** do commit; falha → `PENDING`, sem desfazer a anonimização.
- A trilha guarda só identificadores, nunca valores; é somente leitura e **por registro** (sem filtro por paciente).
- Dumps do backup guardam os dados originais por até 30 dias.

### Interfaces internas
```java
// crm_data.clinic.service.UserNames
Map<UUID, String> namesOf(Collection<UUID> userIds);
```

## 5. Eventos emitidos (internos)
| Evento | Gatilho | Payload |
|---|---|---|
| `Patient.Anonymized` | Anonimização concluída (após o commit) | `{clinicId, patientId, objectCount, status}` |
| `Patient.StorageCleanupPending` | Limpeza do Storage falhou | `{patientId, objectCount}` (sem chaves nem nomes) |

## 6. Critérios de aceite (integração)
- [ ] R recebe 403 em `POST /patients/{id}/anonymize` e em `GET /audit-log`.
- [ ] Paciente ativo → 409; retenção vigente → 409 com a data; já anonimizado → 409; de outra clínica → 404.
- [ ] Paciente com `updated_at` recuado para > 20 anos (fixture com a conexão do dono) é anonimizado; depois nenhuma coluna pessoal mantém o original e `PUT /patients/{id}` → 409.
- [ ] Paciente sem registros clínicos e inativo é anonimizado de imediato.
- [ ] Com um `FileStorage` que falha, a resposta é 200 `PENDING` e o paciente continua anonimizado.
- [ ] Os objetos de todos os exames do paciente (inclusive removidos logicamente) são apagados no sucesso.
- [ ] Duas chamadas concorrentes: uma 200 e a outra 409.
- [ ] `GET /audit-log` pagina, filtra por registro, devolve `userName` e nunca valores de colunas; não cruza clínicas.
- [ ] Log e eventos sem nome, CPF, chave do objeto ou nome de arquivo.
