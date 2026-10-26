# Contract — F07 Integração com o núcleo clínico

## 1. Resumo
- **Feature:** F07-commercial-clinical-integration
- **Versão:** 1.0.0
- **Consumidores:** Frontend React (anonimização e tela de auditoria) e F08 (verificação). Requer `Authorization: Bearer` da Fase 0.

## 2. Entradas

Prefixo `/api/v1`. **Só `DENTIST_ADMIN`** (rotas já restritas desde a Fase 1).

| Método | Rota | Corpo / query |
|---|---|---|
| POST | `/patients/{id}/anonymize` | sem corpo |
| GET | `/audit-log` | `?tableName=… &recordId=uuid &page &size` (`recordId` exige `tableName`) |

`tableName` ∈ `patients`, `medical_histories`, `clinical_notes`, `patient_files`, `appointments`, **`budgets`, `installments`, `payments`, `budget_documents`**. `procedure_types` e `budget_items` não são auditados.

## 3. Saídas

### Sucesso
- **Anonimização** (200):
  ```json
  { "anonymizedAt": "instant", "storageCleanup": "COMPLETED | PENDING", "openBudgets": 0 }
  ```
  `openBudgets` = orçamentos `DRAFT`/`SENT` do paciente (aviso).
- **Auditoria** (200, `PageResponse`): entradas com autor, ação (`INSERT`/`UPDATE`/`DELETE`/`ANONYMIZE`), instante e `recordId`; **sem valores de colunas**.

### Erros (formato da Fase 0)
| Status | Quando |
|---|---|
| 400 `validation` | `tableName` desconhecido; `recordId` sem `tableName`; `page`/`size` inválidos |
| 401 `unauthorized` | Token ausente ou inválido |
| 403 `forbidden` | Recepção |
| 404 `not-found` | Paciente inexistente / de outra clínica |
| 409 `conflict` | Paciente ativo (`CR001`); retenção vigente (`CR002`, com a data); já anonimizado (`CR003`); agendamento futuro (`CR004`); **parcela em aberto (`CR006`: "Quite ou cancele as parcelas em aberto antes de anonimizar.")** |
| 500 `internal` | Falha da auditoria do banco (`28000`), sem detalhes |

## 4. Regras e limites
- Precedência dos erros (do banco): `CR002 → CR004 → CR006`.
- Depois de anonimizar: orçamentos, itens, parcelas e pagamentos **permanecem**; `budgets.notes` fica nulo; os PDFs do paciente são marcados como removidos e os objetos apagados do Storage depois do commit.
- Orçamento `DRAFT` ou `SENT` **sem parcelas** não bloqueia; só parcela `OPEN` bloqueia.
- Orçamento novo, plano e pagamento para paciente anonimizado → 409.
- Falha no Storage não desfaz a anonimização: `storageCleanup = PENDING` e a conciliação é manual.
- A auditoria é só de leitura e nunca traz valores monetários, nomes ou observações.

### Interfaces internas
```java
// crm_data.patient.service.AnonymizationContributor (definida na F01, implementada aqui por commercial)
Collection<String> objectKeys(UUID patientId);   // storage_key de todos os budget_documents do paciente
int openItems(UUID patientId);                   // orçamentos DRAFT ou SENT
```

## 5. Eventos emitidos (internos)
| Evento | Gatilho | Payload |
|---|---|---|
| `Patient.Anonymized` | Após o commit e a limpeza | `{clinicId, patientId, objectCount, storageCleanup}` |
| `Patient.StorageCleanupPending` | Limpeza do Storage falhou | `{patientId, objectCount}` |

## 6. Critérios de aceite (integração)
- [ ] Parcela `OPEN` → 409 (`CR006`) e nada alterado; só `PAID`/`CANCELED` ou `SENT` sem parcelas → passa.
- [ ] Após anonimizar: `notes` do paciente nulos; número, total, status, itens, parcelas e pagamentos intactos; PDFs com `deleted_at`, ausentes da listagem e do download (404); objetos apagados do `FileStorage` de teste.
- [ ] Com o `FileStorage` falhando na limpeza: 200 com `PENDING` e anonimização mantida.
- [ ] `openBudgets` reflete os orçamentos `DRAFT`/`SENT`; as chaves dos PDFs vêm **depois** da função, na mesma transação.
- [ ] `GET /audit-log` das quatro tabelas devolve `INSERT`/`UPDATE`/`ANONYMIZE` sem valores; `procedure_types`/`budget_items` → 400; R → 403.
- [ ] A troca de `status` da parcela e o estorno aparecem na auditoria; cada orçamento limpo gera 1 `UPDATE` mais o `ANONYMIZE`.
- [ ] `Phase3ArchitectureTest`: `patient` não importa `commercial` nem `finance`.
- [ ] Nenhuma chave de objeto, valor ou nome em log, `toString` ou mensagem de erro.
