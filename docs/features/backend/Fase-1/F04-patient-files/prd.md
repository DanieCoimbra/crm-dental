# PRD — F04 Exames e armazenamento

**Derivação:** PRD global da Fase 1, US-P1-07 e US-P1-08, FR-5 a FR-8, FR-12, FR-13, FR-15, FR-17 (parte de Storage); decisões E1, E4 e E6. Spec global, seções 3.3, 4.8, 4.9 e 7.
**Dependências externas:** F01 (sessão com papel e usuário, erros), F02 (`PatientLookup`), tabela `core.patient_files` (V011), bucket privado do Supabase Storage (checklist manual do `database/README.md`, ação do usuário), `CrmProperties.Storage` e `FileStorage` da Fase 0. Consumida por F05 (`storageKeysOf`, `purgeObjects`).

## 1. Introdução

- **Problema:** radiografias e fotos precisam ser anexadas ao paciente, inclusive pela recepção, mas só o dentista pode vê-las; o disco do Render é efêmero e o arquivo não pode ir para o banco.
- **Solução:** upload validado por tamanho, tipo e bytes para o Supabase Storage (bucket privado), metadados em `patient_files`, download por URL assinada de 60 s e remoção lógica, com compensação para não deixar objeto órfão.
- **Metas:**
  1. 100% dos uploads com tipo conferido pelos bytes (declarado ≠ detectado é rejeitado).
  2. A recepção anexa, mas recebe 403 em lista, download e remoção; a resposta do upload traz só `{id}` e nenhum `SELECT`/`RETURNING` ocorre nesse caminho.
  3. Falha do banco após o envio deixa 0 objetos órfãos (compensação provada por IT).
  4. Upload de 10 MB com `LocalFileStorage` em ≤ 5 s, sem carregar o arquivo inteiro no heap.
  5. Cobertura ≥ 80% em `PatientFileService`, `FileSignature` e `FileNames`.

## 2. User stories

### US-001: Anexar exame
**Descrição:** Como recepcionista ou dentista, quero anexar uma radiografia ou foto ao paciente.

**Critérios de aceite:**
- [ ] `POST /api/v1/patients/{id}/files` (D, R), `multipart/form-data` com `file` e `kind` (`RADIOGRAPH`, `INTRAORAL_PHOTO`, `OTHER`).
- [ ] Rejeita: vazio (400); acima de 10 MB (413); tipo fora de `image/jpeg`, `image/png`, `image/webp`, `application/pdf` ou com bytes que não casam com o declarado (400). Grava-se o tipo detectado.
- [ ] A chave é `<clinic_id>/<patient_id>/<uuid>`, gerada pelo servidor; o nome original nunca entra nela e é sanitizado (último segmento, sem controle, NFC, ≤ 255).
- [ ] Ordem: validar → checar paciente → enviar ao Storage → gravar metadados; falha do banco apaga o objeto enviado.
- [ ] Resposta 201 `{ "id" }` para D e R; sem `RETURNING` nem recarga da linha.
- [ ] Paciente inexistente na clínica → 404; anonimizado → 409; Storage fora do ar → 503.
- [ ] 11º upload no mesmo minuto pelo mesmo usuário → 429 com `Retry-After`.

### US-002: Listar e baixar exames
**Descrição:** Como dentista, quero ver os exames do paciente e baixá-los com segurança.

**Critérios de aceite:**
- [ ] `GET /api/v1/patients/{id}/files` (só D): exames ativos, mais recentes primeiro, `size` ≤ 50, sem `storage_key`.
- [ ] `GET .../files/{fileId}/download-url` (só D) devolve `{url, expiresIn: 60}`; exame removido, de outro paciente ou de outra clínica → 404.
- [ ] Antes de assinar, confere que a chave começa com o `clinic_id` do token.
- [ ] A URL não aparece em log e a resposta leva `Cache-Control: no-store`.

### US-003: Remover exame da lista
**Descrição:** Como dentista, quero remover um exame da lista sem perder o registro legal.

**Critérios de aceite:**
- [ ] `DELETE .../files/{fileId}` (só D) faz remoção lógica (`deleted_at`) e responde 204.
- [ ] O objeto permanece no Storage até a anonimização.
- [ ] Recepção recebe 403 em lista, download e remoção.

## 3. Requisitos funcionais

- **FR-1:** `FileStorage` ganha `save(key, in, contentType, size)`, `deleteAll`, `createSignedUrl`; implementações `SupabaseFileStorage` (prod) e `LocalFileStorage` (dev/teste).
- **FR-2:** `StorageKeys` valida a chave (três UUIDs separados por `/`); falhas do Storage viram `StorageException` → 503.
- **FR-3:** Validação por `FileSignature` (JPEG, PNG, WebP, PDF) e `FileNames`.
- **FR-4:** Limite de 10 MB no multipart, `resolve-lazily`, e limite de 10 uploads por minuto por usuário aplicado antes da leitura do corpo.
- **FR-5:** O Storage nunca é chamado dentro de uma transação de banco.
- **FR-6:** `PatientFile` com todos os campos `updatable = false`, exceto `deletedAt`; `createdAt`/`updatedAt` sem `@Generated`.
- **FR-7:** Em `prod`, a aplicação não sobe sem `CRM_STORAGE_URL`, `CRM_STORAGE_SERVICE_KEY` e `CRM_STORAGE_BUCKET`, nem com `LocalFileStorage`.
- **FR-8:** Expor `storageKeysOf(patientId)` e `purgeObjects(keys)` ao módulo `patient`.
- **FR-9:** Nenhuma URL, chave ou nome de arquivo em log.

## 4. Fora do escopo

- Cifrar o binário (E1), antivírus, miniaturas, DICOM e pré-visualização no servidor.
- Edição de metadados (`kind`, nome) e substituição do arquivo.
- Cota por clínica e conciliação automática de objetos órfãos.
- Download por *proxy* do backend.

## 5. Considerações técnicas e riscos

- As rotas da API REST do Supabase Storage (upload, assinatura, remoção em lote) devem ser conferidas contra um bucket real antes de implementar.
- Upload e gravação não são atômicos: a compensação cobre falhas; se ela também falhar, o órfão é registrado em log com `patientId`/`fileId`.
- A service key ignora as políticas do Storage; o isolamento depende do prefixo `<clinic_id>/` montado pelo backend.
- O plano gratuito do Storage tem 1 GB no total.
- **Pergunta aberta:** nenhuma.
