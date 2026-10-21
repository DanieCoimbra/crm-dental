# Contract — F03 Prontuário cifrado

## 1. Resumo
- **Feature:** F03-clinical-record
- **Versão:** 1.0.0
- **Consumidores:** Frontend React (prontuário do dentista). Requer `Authorization: Bearer` da Fase 0 com papel `DENTIST_ADMIN`.

## 2. Entradas

Prefixo `/api/v1`. Todas as rotas: **D** apenas (R recebe 403).

| Método | Rota | Corpo / query |
|---|---|---|
| GET | `/patients/{id}/medical-history` | — |
| PUT | `/patients/{id}/medical-history` | `{ "content": "string (1-10000, não em branco, required)", "version": "integer (obrigatório se já existe; ignorado na criação)" }` |
| POST | `/patients/{id}/notes` | `{ "content": "string (1-5000, não em branco, required)", "notedAt": "instant ISO-8601 (optional; <= agora + 5 min)" }` |
| GET | `/patients/{id}/notes` | `?page=0&size=20 (1–50)` |
| PUT | `/patients/{id}/notes/{noteId}` | `{ "content": "string (1-5000)", "notedAt": "instant (<= agora + 5 min)", "version": "integer (required)" }` |

`{id}`, `{noteId}` são uuid. Nenhum corpo aceita `id`, `patientId`, `clinicId` ou `createdBy`.

## 3. Saídas

### Sucesso
- **MedicalHistoryResponse** (200; 201 na criação): `{ "id": "uuid", "patientId": "uuid", "content": "string", "createdAt": "instant", "updatedAt": "instant", "version": 0 }`.
- **NoteResponse** (200; 201 no `POST`): `{ "id": "uuid", "patientId": "uuid", "notedAt": "instant", "content": "string", "createdBy": "uuid", "createdAt": "instant", "updatedAt": "instant", "version": 0 }`.
- **Página de notas:** `{ "content": [NoteResponse], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0 }`, ordenada por `notedAt` e `id` decrescentes.
- `content` volta sempre decifrado.

### Erros (formato da Fase 0)
| Status | Quando |
|---|---|
| 400 `validation` | Texto vazio/em branco/acima do limite; `notedAt` futuro; `size` fora de 1–50 |
| 401 `unauthorized` | Token ausente ou inválido |
| 403 `forbidden` | Papel `RECEPTIONIST` |
| 404 `not-found` | Paciente, nota ou histórico inexistente, de outro paciente ou de outra clínica |
| 409 `conflict` | `version` defasada ou ausente em histórico existente; criação concorrente; paciente anonimizado |
| 500 `internal` | Falha de decifra (chave ausente, adulteração); sem detalhe |

## 4. Regras e limites
- Um histórico por paciente; notas ilimitadas, sem `DELETE`.
- Cifra AES-256-GCM na aplicação; o banco guarda `varchar` opaco em Base64 e não vê o texto.
- Qualquer `PUT` reinicia a contagem de 20 anos de retenção do prontuário.
- Paciente inativo aceita registros; anonimizado não.
- `clinic_id` e `created_by` vêm só do token; ids de rota só valem dentro do tenant.
- O texto não é aparado nem normalizado.

## 5. Eventos emitidos
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] R recebe 403 em todas as rotas e o banco não é consultado.
- [ ] Com `app.role = RECEPTIONIST` forjado e filtro desligado, `medical_histories` e `clinical_notes` devolvem 0 linhas e as gravações falham.
- [ ] `SELECT content` por SQL nativo casa com `^[A-Za-z0-9+/]+={0,2}$` e não contém o texto enviado.
- [ ] `PUT` do histórico cria (201), depois atualiza (200) com a `version`; sem `version` → 409.
- [ ] Notas listam do mais recente ao mais antigo, `size` ≤ 50.
- [ ] Nota de outro paciente → 404; paciente anonimizado → 409 em criar e editar.
- [ ] Conteúdo clínico ausente de log, evento e mensagem de erro (teste de captura de log).
- [ ] Timeline com 500 notas: p95 ≤ 100 ms.
