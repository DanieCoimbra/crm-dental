# Contract — F02 Pacientes

## 1. Resumo
- **Feature:** F02-patients
- **Versão:** 1.0.0
- **Consumidores:** Frontend React (cadastro, busca, ficha); F03, F04 e F05 (interface interna `PatientLookup`). Requer `Authorization: Bearer` da Fase 0.

## 2. Entradas

Prefixo `/api/v1`. **D** = `DENTIST_ADMIN`, **R** = `RECEPTIONIST`.

| Método | Rota | Papéis | Corpo / query |
|---|---|---|---|
| POST | `/patients` | D, R | `CreatePatientRequest` |
| GET | `/patients` | D, R | `?q=string(>=2, opcional)&status=active\|inactive\|all (padrão active)&page=0&size=20 (1–50)` |
| GET | `/patients/{id}` | D, R | — |
| PUT | `/patients/{id}` | D, R | `UpdatePatientRequest` |
| PATCH | `/patients/{id}/active` | D | `{ "active": "boolean (required)" }` |

```json
// CreatePatientRequest (UpdatePatientRequest = igual + "version": "integer (required)")
{
  "name": "string (2-120, required)",
  "cpf": "string (11 dígitos válidos, optional; máscara é removida)",
  "birthDate": "date AAAA-MM-DD (required, >= 1900-01-01, não futura)",
  "sex": "FEMALE|MALE|OTHER|NOT_INFORMED (optional, padrão NOT_INFORMED)",
  "profession": "string (<=100, optional)",
  "phone": "string (10-13 dígitos, required)",
  "email": "string (email, <=254, optional)",
  "address": "string (<=255, optional)",
  "insuranceName": "string (<=100, optional)",
  "insuranceNumber": "string (<=40, optional; exige insuranceName)",
  "acquaintanceName": "string (<=120, optional; par com acquaintancePhone)",
  "acquaintancePhone": "string (10-13 dígitos, optional; par com acquaintanceName)"
}
```

`{id}` é uuid. Nenhum corpo aceita `id`, `clinicId`, `createdBy`, `active` (fora do PATCH) ou `anonymizedAt`.

## 3. Saídas

### Sucesso
- **PatientResponse** (200; 201 em `POST`):
  ```json
  { "id": "uuid", "name": "string", "cpf": "string|null", "birthDate": "date", "sex": "string",
    "profession": "string|null", "phone": "string", "email": "string|null", "address": "string|null",
    "insuranceName": "string|null", "insuranceNumber": "string|null",
    "acquaintanceName": "string|null", "acquaintancePhone": "string|null",
    "active": true, "deactivatedAt": "instant|null", "anonymizedAt": "instant|null",
    "createdAt": "instant", "updatedAt": "instant", "version": 0 }
  ```
- **Página:** `{ "content": [PatientSummaryResponse], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0 }` com `PatientSummaryResponse = { id, name, phone, birthDate, insuranceName|null, active }`.

### Erros (formato da Fase 0)
| Status | Quando |
|---|---|
| 400 `validation` | Campo inválido; `q` < 2; `size` fora de 1–50; `status` desconhecido |
| 401 `unauthorized` | Token ausente ou inválido |
| 403 `forbidden` | R em `PATCH /active` |
| 404 `not-found` | `{id}` inexistente ou de outra clínica |
| 409 `conflict` | CPF repetido na clínica; `version` defasada; paciente anonimizado |

## 4. Regras e limites
- CPF único por clínica; o banco valida o formato e o backend valida os dígitos verificadores.
- Telefones só com dígitos; e-mail em minúsculas; nome sem espaços nas pontas.
- Busca por trecho sem diferenciar caixa e **sem** ignorar acentos; `size` máximo 50; ordem `name, id`.
- Pacientes anonimizados são inativos e aparecem em `inactive|all` como "Paciente anonimizado".
- Inativar não bloqueia novos registros clínicos; anonimizado bloqueia tudo.
- `clinic_id` vem só do token; ids de rota só valem dentro do tenant.

### Interface interna (`patient.service.PatientLookup`)
```java
void requireExists(UUID patientId);    // BusinessException not-found (404)
void requireWritable(UUID patientId);  // 404 se não existe; 409 se anonimizado
```

## 5. Eventos emitidos
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] D e R recebem 201 em `POST /patients`; sem token, 401.
- [ ] R recebe 403 em `PATCH /patients/{id}/active`.
- [ ] CPF com dígito inválido → 400 no campo `cpf`; repetido → 409 sem o CPF na mensagem nem no log.
- [ ] Mesmo CPF em duas clínicas é aceito.
- [ ] `q=ab` retorna resultados; `q=a` → 400; `q` com 11 dígitos busca por CPF.
- [ ] `status=inactive` não lista ativos; padrão não lista inativos.
- [ ] `PUT` com `version` defasada → 409.
- [ ] Id de outra clínica → 404 em GET, PUT e PATCH, inclusive com o filtro Hibernate desligado.
- [ ] Nenhuma resposta contém `clinicId`, `createdBy` ou campos de prontuário.
