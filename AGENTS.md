# AGENTE.md

> Diretrizes para agentes de IA trabalhando neste projeto (Dental Clinic CRM).

## Visão Geral do Projeto

**Dental Clinic CRM** é um sistema para gerenciamento de clínicas odontológicas. O projeto está dividido em duas partes principais: uma API Backend construída em Go e um aplicativo Frontend construído em Flutter.

### Stack Tecnológica

**Backend (`backend-go/`)**
- **Linguagem:** Go 1.26
- **Framework Web:** Fiber (v2)
- **Banco de Dados & Storage:** Supabase (PostgreSQL + Supabase Storage)
- **Hospedagem/Deploy:** Render (Backend API - Discos efêmeros)
- **Autenticação:** JWT (golang-jwt)
- **Configurações:** godotenv

**Frontend (`frontend_flutter/`)**
- **Linguagem:** Dart (SDK ^3.12.2)
- **Framework:** Flutter
- **Gerenciamento de Estado:** Riverpod
- **Roteamento:** go_router
- **Cliente HTTP:** Dio
- **Armazenamento Local:** Hive & flutter_secure_storage
- **UI/Componentes:** Material Design, Google Fonts, Lucide Icons

## Estrutura do Projeto

```
/
├── backend-go/             # API Backend em Go
│   ├── cmd/                # Ponto de entrada da aplicação (main.go)
│   ├── internal/           # Código privado da aplicação
│   │   ├── adapters/       # Adaptadores (Handlers, Controllers)
│   │   ├── core/           # Lógica de negócios (Use cases, Entidades)
│   │   ├── database/       # Configuração de DB e repositórios
│   │   ├── middleware/     # Middlewares do Fiber (Auth, Logger, etc.)
│   │   └── pkg/            # Pacotes utilitários e bibliotecas
│   ├── uploads/            # Arquivos de upload locais
│   ├── .env                # Variáveis de ambiente
│   ├── go.mod              # Dependências Go
│   └── go.sum              # Hashes de dependências
│
├── frontend_flutter/       # Aplicativo Frontend em Flutter
│   ├── lib/                # Código fonte Dart
│   │   ├── core/           # Configurações globais, temas, constantes
│   │   ├── features/       # Módulos da aplicação (ex: pacientes, agenda)
│   │   ├── shared/         # Componentes UI reutilizáveis, widgets
│   │   └── main.dart       # Ponto de entrada do app
│   ├── pubspec.yaml        # Dependências do Flutter
│   └── analysis_options.yaml # Regras de linting
│
├── legacy/                 # Códigos legados
│   ├── backend-api-legacy/ # Código legado da API (Referência)
│   └── frontend-next-legacy/ # Código legado do Frontend (Referência)
```

## Comandos Úteis

### Backend (Go)
```bash
cd backend-go
go run cmd/server/main.go       # Iniciar servidor de desenvolvimento
go build -o server cmd/server/main.go # Compilar para produção
go mod tidy              # Limpar/atualizar dependências
go test ./...            # Rodar todos os testes
```

### Frontend (Flutter)
```bash
cd frontend_flutter
flutter pub get          # Instalar dependências
flutter run              # Iniciar o aplicativo no dispositivo/emulador padrão
flutter build apk        # Build para Android (APK)
flutter build web        # Build para Web
flutter analyze          # Rodar o linter (Dart Analyzer)
flutter test             # Rodar testes
```

## Diretrizes de Código

### Backend (Go)
- Siga as convenções do [Effective Go](https://go.dev/doc/effective_go).
- Utilize injeção de dependências para facilitar os testes.
- **Arquitetura:** O projeto utiliza uma arquitetura em camadas (`internal/core`, `internal/adapters`). Mantenha a lógica de negócio separada da infraestrutura web (Fiber).
- **Tratamento de Erros:** Não ignore erros. Sempre retorne ou trate adequadamente e utilize logs quando necessário.
- **Formatação:** Sempre utilize `go fmt` antes de commitar o código.

### Frontend (Flutter)
- Utilize **Riverpod** para gerenciamento de estado e injeção de dependência.
- Separe as funcionalidades por **features** (`lib/features/`). Cada feature deve ter sua própria organização de UI, lógica e estado.
- Utilize componentes reutilizáveis da pasta `lib/shared/` para garantir consistência visual.
- Nomes de arquivos e pastas em minúsculas com underscores (ex: `patient_card.dart`).
- Nomes de classes em `PascalCase` (ex: `PatientCard`).

## Limites e Regras

### Sempre Faça
- Verifique se os pacotes e dependências estão atualizados.
- Siga a organização de pastas existente (`internal/` no Go, `features/` no Flutter).
- Tipagem estrita em Dart e uso correto de tipagem estática no Go.
- Escreva código modular e testável.

### Pergunte Primeiro
- Antes de adicionar novas dependências pesadas (`go.mod` ou `pubspec.yaml`).
- Antes de realizar alterações estruturais no banco de dados (GORM migrations).
- Antes de alterar a arquitetura base ou a lógica de autenticação.

### Nunca Faça
- Não versione o arquivo `.env` ou chaves de API sensíveis.
- Não misture regras de negócio nos arquivos de rota do Fiber ou diretamente na UI do Flutter.
- Não altere o código legado (`backend-api-legacy` ou `frontend-next-legacy`) a menos que explicitamente solicitado.

## Variáveis de Ambiente

O arquivo `.env` deve ser configurado dentro da pasta `backend-go/`.
(Adapte as variáveis conforme o projeto necessita, por exemplo:)
```
PORT=8080
DATABASE_URL="postgresql://postgres:suasenha@db.seusupabase.supabase.co:5432/postgres"
JWT_SECRET=your_jwt_secret
SUPABASE_URL="https://seusupabase.supabase.co"
SUPABASE_KEY="sua_anon_key_ou_service_role"
```

## Tarefas Comuns

### Criando um novo Endpoint (Go)
1. Crie o model e interface no `internal/core`.
2. Implemente o repositório em `internal/database`.
3. Implemente a lógica (usecase) no `internal/core`.
4. Crie o handler no `internal/adapters` e vincule a rota do Fiber.

### Criando uma nova Tela (Flutter)
1. Crie uma nova pasta dentro de `lib/features/{nome_da_feature}/`.
2. Adicione os arquivos de UI (ex: `tela.dart`) e de estado Riverpod (ex: `provider.dart`).
3. Registre a rota no `go_router` (geralmente no arquivo de roteamento central).
