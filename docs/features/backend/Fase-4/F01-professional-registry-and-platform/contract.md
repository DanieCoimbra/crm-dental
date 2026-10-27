# Contract — F01 CRO e plataforma da Fase 4

## 1. Resumo
- **Feature:** F01-professional-registry-and-platform
- **Versão:** 1.0.0
- **Consumidores:** Frontend React (perfil do dentista), F02 a F05 (identidade profissional, mensagens, enums, propriedades e regras de rota). Requer `Authorization: Bearer` da Fase 0.

## 2. Entradas

Prefixo `/api/v1`. **D** = `DENTIST_ADMIN`, **R** = `RECEPTIONIST`.

| Método | Rota | Papéis | Corpo |
|---|---|---|---|
| PUT | `/me/professional-registry` | D | `{ "cro": "12345", "croState": "SP", "version": long }` (ou `cro` e `croState` `null` para apagar) |
| GET | `/me` | D, R | — |

`clinicId`, `role`, `id` e `email` do corpo são ignorados. `cro`: 1 a 15 dígitos; `croState`: UF em maiúsculas (27 valores).

## 3. Saídas

### Sucesso
- **`PUT /me/professional-registry`** (200): `UserResponse`
  ```json
  { "id": "uuid", "name": "Dra. Ana", "email": "ana@clinica.com", "role": "DENTIST_ADMIN", "active": true,
    "clinicId": "uuid", "cro": "12345", "croState": "SP", "version": 4 }
  ```
- **`GET /me`** (200): o mesmo formato; `cro`/`croState` `null` para recepção e para dentista sem CRO.

### Erros (formato da Fase 0, `ProblemDetail`)
| Status | Quando |
|---|---|
| 400 `validation` | `cro` fora de `\d{1,15}`, UF inválida ou só um dos dois campos; `version` ausente |
| 401 `unauthorized` | Token ausente ou inválido |
| 403 `forbidden` | R em `PUT /me/professional-registry` |
| 409 `conflict` | `version` defasada; (para as outras features) `CR012` e `CR003`/`CR005` |
| 500 `internal` | `42501`/`28000`/`23514` residual (sem detalhes) |
| 500 `integrity` | `IntegrityException` (para F02/F04) |

## 4. Regras e limites
- O CRO pertence ao usuário do token; ninguém grava o CRO de outro usuário.
- `PUT /me` nunca altera `cro`/`croState` (rota e DTO separados).
- `CR012` → 409 `conflict`, não 422. `23514` residual → 500.
- Propriedades: `crm.clinical-documents.issues-per-minute` e `pdfs-per-minute`, 1 a 600, padrão 30.

### Interfaces internas
```java
// crm_data.auth.service.ProfessionalIdentity (pública)
Identity current();                 // usuário do token
Optional<Identity> of(UUID userId); // mesma clínica; vazio se não existe
record Identity(String name, String cro, String croState) {}

// crm_data.shared.error.IntegrityException  -> 500, type ".../problems/integrity"
// crm_data.record.domain.AuditAction  += VOID, DOWNLOAD
// crm_data.record.service.AuditQueryService.TABLES += "clinical_documents"
// crm_data.shared.error.ConflictMessages += PROFESSIONAL_REGISTRY_MISSING, DOCUMENT_STATE, DOCUMENT_ANONYMIZED
```

### Regras de rota (`SecurityConfig`)
| Padrão | Papéis |
|---|---|
| `/api/v1/patients/*/clinical-documents/**`, `/api/v1/clinical-documents/**`, `PUT /api/v1/me/professional-registry` | D |
| `/api/v1/dashboard`, `GET /api/v1/me` | D, R |

## 5. Eventos emitidos (internos)
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] D grava CRO (200), troca (200), apaga (200); `GET /me` reflete; R recebe 403 e `null`; sem token, 401.
- [ ] `PUT /me` com nome/e-mail novos mantém o CRO.
- [ ] Um campo sem o outro, UF minúscula, `cro` com letra ou 16 dígitos → 400 com `errors[]`.
- [ ] `version` defasada e duas gravações simultâneas → 409 e 200.
- [ ] `ProfessionalIdentity.of` de usuário de outra clínica → vazio.
- [ ] `GET /audit-log?tableName=clinical_documents` aceito; `VOID`/`DOWNLOAD` serializam como texto.
- [ ] `CR012` → 409; `IntegrityException` → 500 `integrity` sem texto; `23514` de `ck_cdoc_*` → 500.
- [ ] `StartupConfigValidator` rejeita 0 e 601; `Phase4ArchitectureTest` verde; `ddl-auto=validate` com `User` novo.
