# 🦷 Dental Clinic CRM — Premium Software Portfolio

[![Go Version](https://img.shields.io/badge/Go-1.26-00ADD8?style=for-the-badge&logo=go)](https://go.dev/)
[![Flutter Version](https://img.shields.io/badge/Flutter-Web%20%26%20Mobile-02569B?style=for-the-badge&logo=flutter)](https://flutter.dev/)
[![Riverpod](https://img.shields.io/badge/State_Management-Riverpod-blue?style=for-the-badge)](https://riverpod.dev/)
[![Supabase](https://img.shields.io/badge/Database-Supabase_PostgreSQL-3ECF8E?style=for-the-badge&logo=supabase)](https://supabase.com/)
[![Render](https://img.shields.io/badge/Deploy_API-Render-46E3B7?style=for-the-badge&logo=render)](https://render.com/)
[![Vercel](https://img.shields.io/badge/Deploy_Frontend-Vercel-000000?style=for-the-badge&logo=vercel)](https://vercel.com/)
[![CI Status](https://img.shields.io/badge/CI%2FCD-GitHub_Actions-2088FF?style=for-the-badge&logo=github-actions)](.github/workflows/ci.yml)

> **Sistema de Gestão Clínica Odontológica Multi-Tenant Full-Stack (Go + Flutter).**  
> Desenvolvido com foco em **Clean Architecture**, **Alta Performance**, **Isolamento Estrito de Dados (Multi-Tenancy)** e **Design System Clinical Precision Glass**.

---

## 🌐 Live Demo & Acesso Público

Este repositório está configurado para **exibição pública de portfólio**. Você pode acessar a aplicação em produção utilizando as credenciais públicas de demonstração abaixo:

- 🔗 **Frontend Web (Vercel):** [https://crm-clinica-ten.vercel.app](https://crm-clinica-ten.vercel.app)
- ⚙️ **Backend API (Render):** [https://crm-clinica-gjss.onrender.com](https://crm-clinica-gjss.onrender.com)

### 🔑 Credenciais da Clínica Demo:
| Perfil | E-mail | Senha |
| :--- | :--- | :--- |
| **Dono da Clínica (Owner)** | `admin@clinica.com` | `123456` |

> 🔒 **Nota de Segurança:** Nenhuma chave de API privada, token de produção ou segredo real é versionado neste repositório. O banco de dados demo utiliza *seeding* automático isolado.

---

## 🚀 Módulos & Recursos Principais

### 🦷 1. Prontuário Eletrônico (EMR) & Odontograma Interativo
- Histórico clínico completo com suporte a nota em formato **Quill Delta**.
- Odontograma visual interativo por dente e superfície (hígido, restaurado, canal, extração).
- Galeria de exames e arquivos de imagem com visualizador lightbox integrado.

### 📅 2. Agendamento Multi-Salas & Fila de Espera
- Grade horária flexível com alocação por dentista, sala cirúrgica e procedimento.
- Fila de espera dinâmica com cálculo aproximado de horário de atendimento.

### 📊 3. Gestão Financeira & Orçamentos
- Emissão e controle de orçamentos odontológicos detalhados com itens de procedimento.
- Lançamento de receitas, despesas, parcelamentos e geração automática de recibos em PDF.

### 📦 4. Controle de Estoque & Consumo
- Cadastro de materiais com alerta automático de estoque mínimo.
- Baixa automática de insumos atrelada aos procedimentos efetuados.

### 💳 5. SaaS Billing & RBAC (Multi-Tenancy)
- Suporte a múltiplos consultórios com isolamento rigoroso por `clinic_id`.
- Controle de acesso baseado em cargos (*Owner, Manager, Doctor, Receptionist*).

---

## 📐 Arquitetura da Solução

O projeto segue os princípios de **Clean Architecture** e **Separation of Concerns**:

```mermaid
graph TD
    User([Usuário Web / Mobile]) <-->|HTTPS / JSON API| Front[Frontend Flutter Web]
    Front <-->|Dio HTTP + JWT Bearer| Back[Backend Go Fiber v2]
    
    subgraph Backend Architecture
        Back --> Router[Adapters: Routes & Handlers]
        Router --> Service[Core: Business Logic & Usecases]
        Service --> Repo[Adapters: Repositories / GORM]
        Service --> Domain[Core: Domain Entities & Interfaces]
    end

    Repo <-->|PostgreSQL Pooler 5432| DB[(Supabase PostgreSQL)]
    Service <-->|Storage Bucket| S3[(Supabase Storage)]
```

---

## 🛠️ Stack Tecnológica

### Backend (`backend-go/`)
- **Linguagem:** Go 1.26
- **Framework HTTP:** Fiber v2 (Fast HTTP framework)
- **ORM & DB Driver:** GORM + Supabase PostgreSQL Driver
- **Autenticação:** JWT (`golang-jwt/v5`) com hash `bcrypt`
- **Segurança:** Middleware RBAC, Criptografia AES, Middleware Multi-Tenancy

### Frontend (`frontend_flutter/`)
- **Framework:** Flutter 3 (Web & Mobile Target)
- **Gerenciamento de Estado:** Riverpod (Notifier & Provider Pattern)
- **Roteamento:** GoRouter
- **Cliente HTTP:** Dio (com interceptores de autorização e reconexão 401)
- **UI/UX:** Material 3, Lucide Icons, Google Fonts, PDF Generator

---

## 💻 Instruções para Execução Local

### Pré-requisitos
- [Go 1.26+](https://go.dev/dl/)
- [Flutter SDK 3.x+](https://docs.flutter.dev/get-started/install)
- [PostgreSQL](https://www.postgresql.org/) ou conta [Supabase](https://supabase.com/)

### 1. Clonar o Repositório
```bash
git clone https://github.com/seu-usuario/crm-clinica.git
cd crm-clinica
```

### 2. Configurar e Rodar o Backend (Go)
```bash
cd backend-go
cp .env.example .env # ajuste a DATABASE_URL e JWT_SECRET se necessário
go run cmd/server/main.go
```
*A API iniciará por padrão em `http://localhost:8080`.*

### 3. Rodar os Testes do Backend
```bash
go test -v ./...
```

### 4. Configurar e Rodar o Frontend (Flutter)
```bash
cd ../frontend_flutter
flutter pub get
flutter run -d chrome
```

### 5. Rodar os Testes do Frontend
```bash
flutter test
```

---

## 📄 Licença & Propriedade Intelectual

Este repositório foi desenvolvido para fins de **demonstração de portfólio profissional**.  
Código distribuído sob a licença **MIT**. Sinta-se livre para examinar, clonar e utilizar como referência técnica.
