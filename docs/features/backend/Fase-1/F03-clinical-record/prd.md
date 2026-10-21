# PRD — F03 Prontuário cifrado

**Derivação:** PRD global da Fase 1, US-P1-05 e US-P1-06, FR-4, FR-12, FR-14, FR-16; decisões E4 e E5. Spec global, seções 2.2, 4.5 e 4.6.
**Dependências externas:** F01 (sessão com papel e usuário, erros), F02 (`PatientLookup`), `AesGcmCipher`/`EncryptedStringConverter` e `KeyRing` da Fase 0 (F03 daquela fase), tabelas `core.medical_histories` (V009) e `core.clinical_notes` (V010). Requer o access token da Fase 0.

## 1. Introdução

- **Problema:** o dentista precisa registrar o histórico médico e as notas de cada atendimento, que são dados de saúde sensíveis e não podem ficar legíveis no banco nem acessíveis à recepção.
- **Solução:** histórico (um por paciente) e notas datadas, cifrados com AES-256-GCM na aplicação, acessíveis só ao `DENTIST_ADMIN`, com controle otimista de edição.
- **Metas:**
  1. 0 texto clínico em claro no banco (verificado por SQL nativo) e nos logs.
  2. `RECEPTIONIST` recebe 403 em 100% das rotas e, mesmo forjando o papel no banco, a RLS devolve 0 linhas.
  3. Linha do tempo com 500 notas responde p95 ≤ 100 ms (decifra só a página de até 50).
  4. Cobertura ≥ 80% nos services da feature.

## 2. User stories

### US-001: Histórico médico cifrado
**Descrição:** Como dentista, quero um histórico médico por paciente, ilegível para quem acessa o banco por fora da aplicação.

**Critérios de aceite:**
- [ ] `GET` e `PUT /api/v1/patients/{id}/medical-history` só para D.
- [ ] `PUT` cria na primeira vez (201) e atualiza depois (200); criação ignora `version`; atualização exige a `version` atual.
- [ ] Texto obrigatório, não em branco, até 10.000 caracteres; não é aparado nem normalizado.
- [ ] `GET` sem histórico → 404; paciente anonimizado → 409 em `PUT`.
- [ ] SQL nativo em `content` não contém o texto original e casa com `^[A-Za-z0-9+/]+={0,2}$`.
- [ ] O conteúdo não aparece em `toString`, log, exceção nem resposta de erro.

### US-002: Notas por atendimento
**Descrição:** Como dentista, quero registrar, listar e corrigir notas clínicas datadas.

**Critérios de aceite:**
- [ ] `POST /api/v1/patients/{id}/notes`: `content` obrigatório (até 5.000 caracteres), `notedAt` opcional (padrão agora; no máximo agora + 5 min).
- [ ] `GET /api/v1/patients/{id}/notes?page&size` da mais recente para a mais antiga (`notedAt DESC, id DESC`), `size` ≤ 50, já decifrada.
- [ ] `PUT /api/v1/patients/{id}/notes/{noteId}` edita `content` e `notedAt` com `version`; não existe `DELETE`.
- [ ] Nota de outro paciente ou de outra clínica → 404; paciente anonimizado → 409 em criar e editar.

### US-003: Recepção sem acesso
**Descrição:** Como titular da clínica, quero que a recepção não leia nem altere o prontuário, mesmo que o backend erre.

**Critérios de aceite:**
- [ ] R recebe 403 em todas as rotas desta feature (rota e `@PreAuthorize`), sem tocar o banco.
- [ ] Teste de integração com `app.role = RECEPTIONIST` e filtro desligado: leitura devolve 0 linhas; `INSERT`/`UPDATE` falham.
- [ ] Documentado na API que editar o prontuário reinicia a contagem de 20 anos de retenção.

## 3. Requisitos funcionais

- **FR-1:** Cifrar `content` de histórico e nota via `EncryptedStringConverter`; a chave e a versão vêm do `KeyRing`.
- **FR-2:** Aplicar os limites de 10.000 e 5.000 caracteres e rejeitar texto vazio ou em branco.
- **FR-3:** Controle otimista (`version`) em histórico e nota.
- **FR-4:** Mapear `patientId` como `UUID` simples (sem `@ManyToOne`) e marcá-lo `updatable = false`, junto com `createdBy`.
- **FR-5:** Violação de `ux_histories_patient` → 409.
- **FR-6:** Nunca registrar conteúdo clínico em log, evento ou mensagem de erro.
- **FR-7:** `CryptoException` na leitura (chave ausente, texto adulterado) → 500 `internal` sem conteúdo.

## 4. Fora do escopo

- Exclusão de nota ou de histórico; versionamento do texto; anexos na nota.
- Busca ou ordenação por conteúdo; odontograma e evolução por dente.
- Vínculo da nota com agendamento (`appointment_id`, Fase 2).
- Rotação gradual da chave AES (o `KeyRing` já a suporta; a tarefa de recriptografia fica fora).

## 5. Considerações técnicas e riscos

- Perder a chave AES torna o prontuário irrecuperável: guardar `CRM_AES_KEYS` fora do repositório e do banco.
- Editar reinicia a retenção (cálculo no banco pelo último registro).
- O limite de caracteres conta unidades UTF-16; cabe no `varchar` cifrado mesmo no pior caso.
- **Pergunta aberta:** nenhuma.
