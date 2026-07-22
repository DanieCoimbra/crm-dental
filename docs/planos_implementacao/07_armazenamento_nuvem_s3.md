# Plano de Implementação: Armazenamento em Nuvem (AWS S3 / S3-Compatible)

## 1. Visão Geral
Migrar o sistema de armazenamento de anexos, Raio-X, fotos de prontuário, documentos e imagens de perfil do armazenamento em disco local (`backend-go/uploads/`) para um serviço de Cloud Object Storage compatível com AWS S3 (como AWS S3, Cloudflare R2 ou MinIO). A arquitetura utiliza URLs pré-assinadas (Presigned URLs) para download seguro e upload direto cliente-nuvem com baixíssima latência.

---

## 2. Tecnologias Aplicáveis & Skills Utilizadas
- **Backend:** Go 1.26 + Fiber v2, AWS SDK for Go v2 (`github.com/aws/aws-sdk-go-v2`), `crypto/rand` para UUIDs únicos de arquivo, Script de Migração CLI em Go.
- **Frontend:** Flutter + Riverpod, `dio` para upload com indicação de progresso, `cached_network_image` para cache de imagens médicas no cliente.
- **Arquitetura & Segurança:** Provider Pattern (`StorageProvider` interface), Presigned URLs de visualização temporária (expiração em 15min), isolamento de arquivos por `clinic_id/patient_id/`.

---

## 3. Regras de Negócio
1. **Estrutura de Buckets e Prefixo de Chaves:**
   - Formato da chave no S3: `clinics/{clinic_id}/patients/{patient_id}/{category}/{uuid}_{filename}`.
   - Isso garante isolamento multi-tenant absoluto no bucket S3.
2. **Segurança de Acesso (Zero Public Bucket):**
   - O bucket S3 é estritamente privado (`Private`).
   - O frontend solicita à API Go uma Presigned URL temporária de leitura com validade ajustável (ex: 15 minutos).
3. **Upload Direto com Presigned Upload URL:**
   - Para arquivos pesados (ex: Raio-X panorâmico de alta resolução ou tomografias):
     1. App solicita Presigned PUT URL à API Go.
     2. App envia os bytes diretamente para o S3 (sem sobrecarregar a memória do servidor Go).
     3. App confirma a gravação dos metadados no Go após o upload ser concluído na nuvem.
4. **Script de Migração Retrospectiva:**
   - Ferramenta CLI Go para ler arquivos da pasta `backend-go/uploads/`, subir para o S3 e atualizar as URLs no banco de dados PostgreSQL sem perder o histórico dos clientes.

---

## 4. Integração Total (Backend + Frontend)

### 4.1. Backend (Go)

#### Interface de Armazenamento (`internal/core/ports/storage.go`)
```go
type StorageProvider interface {
    UploadFile(ctx context.Context, key string, body io.Reader, contentType string) (string, error)
    GeneratePresignedDownloadURL(ctx context.Context, key string, expiration time.Duration) (string, error)
    GeneratePresignedUploadURL(ctx context.Context, key string, contentType string, expiration time.Duration) (string, error)
    DeleteFile(ctx context.Context, key string) error
}
```

#### Implementação AWS S3 (`internal/adapters/storage/s3_storage_provider.go`)
- Utiliza `aws-sdk-go-v2/service/s3` e `s3.NewPresignClient`.
- Alternância simples via variável `.env`: `STORAGE_DRIVER=s3` ou `STORAGE_DRIVER=local` (para desenvolvimento local).

#### Migration CLI Command (`cmd/migrate_storage/main.go`)
```go
func main() {
    // 1. Conectar ao DB e inicializar S3 Provider
    // 2. Buscar todos os registros em `patient_files` onde `is_remote == false`
    // 3. Abrir arquivo local em `backend-go/uploads/`
    // 4. Fazer upload para o S3 bucket
    // 5. Atualizar coluna `file_key` e `is_remote = true` no PostgreSQL
}
```

---

### 4.2. Frontend (Flutter)

#### Upload Component (`lib/features/emr/presentation/widgets/file_uploader_widget.dart`)
- Seleção de arquivos via `file_picker`.
- Envio direto para o S3 via Dio com callback de progresso:
```dart
final response = await dio.put(
  presignedUploadUrl,
  data: fileBytes,
  options: Options(headers: {'Content-Type': mimeType}),
  onSendProgress: (sent, total) {
    ref.read(uploadProgressProvider.notifier).state = sent / total;
  },
);
```

#### Renderização de Imagens e Exames (`patient_file_viewer.dart`)
- Ao carregar a lista de anexos do prontuário, a API Go retorna a Presigned URL.
- O Flutter utiliza `CachedNetworkImage` para visualização rápida e suave.

---

## 5. Plano de Testes & Validação
1. **Teste de Unidade com LocalStack / MinIO:** Executar suíte de testes do Go utilizando MinIO rodando em Docker para simular o S3 localmente.
2. **Teste de Expiração de Presigned URL:** Verificar se URLs temporárias expiram corretamente e retornam HTTP `403 Forbidden` após o tempo limite.
3. **Teste do Script de Migração:** Rodar a migração em banco de staging contendo 100 arquivos locais e validar integridade checksum MD5 no S3.
