# AGENTE.md

> Diretrizes para agentes de IA trabalhando neste projeto (Dental Clinic CRM).

## Visão Geral do Projeto

**Dental Clinic CRM** é um sistema para gerenciamento de clínicas odontológicas. O projeto está dividido em duas partes principais: uma API Backend construída em Go e um aplicativo Frontend construído em Flutter. Sempre converse em portugues br.

### Stack Tecnológica

**Backend (`backend-go/`)**
- **Linguagem:** Go 1.26
- **Framework Web:** Fiber (v2)
- **Banco de Dados & Storage:** Supabase (PostgreSQL + Supabase Storage)
- **Hospedagem/Deploy:** Render (`https://crm-dental-lap6.onrender.com` - Backend API - Discos efêmeros)
- **Autenticação:** JWT (golang-jwt)
- **Configurações:** godotenv

**Frontend (`frontend_flutter/`)**
- **Linguagem:** Dart (SDK ^3.12.2)
- **Framework:** Flutter (Web e Mobile)
- **Hospedagem/Deploy:** Vercel (Flutter Web via `build.sh` e `vercel.json`)
- **Gerenciamento de Estado:** Riverpod
- **Roteamento:** go_router
- **Cliente HTTP:** Dio (Configurado com `API_BASE_URL` apontando para o Render)
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
bash build.sh            # Script de build automatizado para Vercel
flutter analyze          # Rodar o linter (Dart Analyzer)
flutter test             # Rodar testes
```

## Diretrizes de Código

### Backend (Go)
- Siga as convenções do [Effective Go](https://go.dev/doc/effective_go).
- Utilize injeção de dependências para facilitar os testes.
- **Arquitetura:** O projeto utiliza uma arquitetura em camadas (`internal/core`, `internal/adapters`). Mantenha a lógica de negócio separada da infraestrutura web (Fiber).
- **Tratamento de Erros:** Não ignore erros. Sempre retorne ou trate adequadamente e utilize logs quando necessário.
- **Formatação:** Sempre utilize `go fmt ./...` antes de commitar o código.
- **Histórico Clínico & LGPD:** Evoluções clínicas devem suportar formato Quill Delta JSON, incluir paginação (`page`, `limit`) e registrar logs de auditoria assíncronos (`audit_logs`) para consultas, criações e exclusões.

### Frontend (Flutter)
- Utilize **Riverpod** para gerenciamento de estado e injeção de dependência.
- **Design System ("Clinical Precision Glass"):**
  - Paleta base: Deep Slate (`#0F172A`), Superfícies (`#1E293B` no escuro / `#FFFFFF` no claro), Bordas cirúrgicas (`#334155` / `#E2E8F0`), Azul Royal (`#2563EB`) e Verde Esmeralda (`#10B981`).
  - Suporte nativo e dinâmico para **Modo Claro (Light)** e **Modo Escuro (Dark)** usando `Theme.of(context)`. Proibido usar cores estáticas (*hardcoded*).
- **Acessibilidade & UX (WCAG 2.1):**
  - Botões com apenas ícone devem ser envolvidos em `Semantics(label: '...', button: true)`.
  - Textos de histórico médico e notas clínicas devem utilizar `SelectableText` para facilitar cópia.
  - Alvos de clique/toque com tamanho mínimo de 44x44px.
- **Ícones:** Padronização estrita com `LucideIcons`.
- Separe as funcionalidades por **features** (`lib/features/`). Cada feature deve ter sua própria organização de UI, lógica e estado.
- Utilize componentes reutilizáveis da pasta `lib/shared/` para garantir consistência visual.
- Nomes de arquivos e pastas em minúsculas com underscores (ex: `patient_card.dart`).
- Nomes de classes em `PascalCase` (ex: `PatientCard`).

## Design & Protótipos no Stitch
- **Projeto Stitch:** `Dental Clinic CRM - Premium` (ID: `3046150886637043753`)
- **Estilo Base:** Glassmorphism clínico, alta visibilidade em turnos médicos, gráficos de fluxo de caixa e prontuário estruturado.

## Limites e Regras

### Sempre Faça
- Verifique se os pacotes e dependências estão atualizados.
- Siga a organização de pastas existente (`internal/` no Go, `features/` no Flutter).
- Tipagem estrita em Dart e uso correto de tipagem estática no Go.
- Escreva código modular e testável.
- Execute `flutter analyze` no frontend e `go test ./...` no backend antes de considerar uma tarefa concluída.

### Pergunte Primeiro
- Antes de adicionar novas dependências pesadas (`go.mod` ou `pubspec.yaml`).
- Antes de realizar alterações estruturais no banco de dados (GORM migrations).
- Antes de alterar a arquitetura base ou a lógica de autenticação.

### Nunca Faça
- Não versione o arquivo `.env` ou chaves de API sensíveis.
- Não misture regras de negócio nos arquivos de rota do Fiber ou diretamente na UI do Flutter.
- Não altere o código legado (`backend-api-legacy` ou `frontend-next-legacy`) a menos que explicitamente solicitado.

## Variáveis de Ambiente e Conexão com Banco de Dados (Supabase + Render)

O arquivo `.env` deve ser configurado dentro da pasta `backend-go/`.
(Adapte as variáveis conforme o projeto necessita, por exemplo:)
```env
PORT=8080
DATABASE_URL="postgresql://postgres.hecpazxguibzkcsibpjq:suasenha@aws-0-us-east-1.pooler.supabase.com:5432/postgres"
JWT_SECRET=your_jwt_secret
SUPABASE_URL="https://hecpazxguibzkcsibpjq.supabase.co"
SUPABASE_KEY="sua_anon_key_ou_service_role"
```

> ⚠️ **Atenção ao Deploy no Render (Supabase IPv4 / Pooler):**
> - **Endereço do Pooler:** O Render não suporta conexões de saída IPv6. Por isso, **nunca** use o endereço direto do Supabase (`db.[ref].supabase.co`) em produção no Render. Use sempre o endereço do **Connection Pooler (Supavisor)** (ex: `aws-0-[regiao].pooler.supabase.com:5432`).
> - **Formato do Usuário:** No Pooler do Supabase, o nome de usuário do banco exige o sufixo `.[PROJECT_REF]` (ex: `postgres.hecpazxguibzkcsibpjq`).
> - **Modo de Sessão (Porta 5432):** Use a porta `5432` (Session Mode) no Pooler para total compatibilidade com o GORM.
> - **Sanitização de DSN (`cleanDSN`):** O código em `database.go` limpa automaticamente aspas, quebras de linha (`\n`, `\r`) e espaços em branco que possam ser colados por engano nas variáveis do Render.
> - **Estrutura do `cmd/`:** Ponto de entrada do servidor é estritamente `backend-go/cmd/server/main.go`. **Nunca** crie arquivos com `package main` na raiz da pasta `backend-go/`, pois o compilador do Render irá tentar executá-los no lugar do servidor.

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
