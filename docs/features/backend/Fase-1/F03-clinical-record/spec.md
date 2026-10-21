# Spec — F03 Prontuário cifrado

Complementa `docs/global/backend/Fase-1/spec.md` (seções 2.2, 3, 4.5, 4.6 e 5).

## 1. Visão técnica
- **Feature:** F03 Prontuário cifrado.
- **Stack:** Spring Web MVC, Spring Data JPA, Bean Validation, `AesGcmCipher`/`EncryptedStringConverter` (Fase 0), Spring Security.
- **Abordagem:** módulo `crm_data.record` (subconjunto de prontuário). Sem migrations: usa V009 e V010.

## 2. Dados
- **Tabelas:** `core.medical_histories` (`UNIQUE (patient_id)`), `core.clinical_notes`; nenhuma alteração.
- **`MedicalHistory`:** `id` atribuído; `patientId` (`updatable = false`); `@Convert(EncryptedStringConverter) String content`; `createdBy` (`updatable = false`); `createdAt`/`updatedAt` somente leitura (sem `@Generated`); `@Version Long`.
- **`ClinicalNote`:** igual, mais `notedAt Instant` editável; `patientId` sem `UNIQUE`.
- **Formato gravado:** `Base64(versão[1] | IV[12] | cifra + tag[16])`, padrão Base64, ≥ 40 caracteres (satisfaz a `CHECK` do banco).
- **Estado em memória:** nenhum; texto decifrado só na entidade e na resposta.

## 3. Componentes
| Componente | Responsabilidade |
|---|---|
| `MedicalHistoryController` | `GET/PUT /patients/{id}/medical-history` |
| `ClinicalNoteController` | `POST/GET /patients/{id}/notes`, `PUT /patients/{id}/notes/{noteId}` |
| `MedicalHistoryRequests`, `NoteRequests` | Records (`content`, `version?`, `notedAt?`); validação `@NotBlank`, `@Size` |
| `MedicalHistoryService`, `ClinicalNoteService` | Regras, transações, `@PreAuthorize("hasRole('DENTIST_ADMIN')")` |
| `MedicalHistoryRepository`, `ClinicalNoteRepository` | `findByPatientId`; página por `patientId` ordenada por `notedAt DESC, id DESC`; busca de nota por `id` e `patientId` |
| `MedicalHistoryResponse`, `NoteResponse` | Saída com `content` decifrado |

Regras de rota (`SecurityConfig`): `/api/v1/patients/*/medical-history` e `/api/v1/patients/*/notes/**` exigem D.

## 4. Lógica
- **Histórico (`PUT`):** `PatientLookup.requireWritable`; busca por `patientId`. Inexistente → cria (201, ignora `version`). Existente + `version` ausente → 409; diferente da atual → 409; igual → atualiza (200). `saveAndFlush`; `ux_histories_patient` → 409; `refresh` para devolver `createdAt`/`updatedAt`.
- **Histórico (`GET`):** `requireExists`; sem registro → 404.
- **Nota (criar):** `requireWritable`; `notedAt` ausente → agora; > agora + 5 min → 400; `id` e `createdBy` do servidor; `saveAndFlush`; `refresh`; 201.
- **Nota (listar):** `requireExists`; página decifrada.
- **Nota (editar):** busca por `id` e `patientId` (404 se não casar); compara `version`; atualiza `content` e `notedAt`; `saveAndFlush`.
- A criptografia ocorre no conversor; o service nunca manipula a chave.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Recepção em qualquer rota | 403 pela rota/`@PreAuthorize`, sem acesso ao banco |
| `{id}` ou `{noteId}` de outra clínica ou de outro paciente | 404 |
| Texto vazio, em branco ou acima do limite | 400 |
| `notedAt` futuro (> agora + 5 min) | 400 |
| Histórico já existe e `version` ausente/defasada | 409 |
| Criação concorrente do histórico | `ux_histories_patient` → 409 |
| Paciente anonimizado | 409 |
| Chave AES ausente/versão desconhecida/texto adulterado | `CryptoException` → 500, log sem conteúdo |

## 6. Segurança e desempenho
- Papel D por rota e por `@PreAuthorize`; RLS exige `app.role = DENTIST_ADMIN` e `created_by = app.user_id` na inserção.
- Entidades sem `@ToString`; nenhum log do `content`; mensagens de erro fixas.
- Listagem de notas: índice `ix_notes_timeline`; decifra só a página (≤ 50); meta p95 ≤ 100 ms com 500 notas.
