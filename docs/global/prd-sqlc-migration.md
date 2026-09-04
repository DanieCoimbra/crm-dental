# PRD Global — Migração de Banco de Dados: GORM ➔ SQLC + pgx/v5

**Documento:** Product Requirements Document (PRD) Global  
**Projeto:** Dental Clinic CRM (`backend-go`)  
**Autor:** Go Architect Agent  
**Versão:** 1.0  
**Status:** Aguardando Aprovação Humana (Etapa 1 de 5)  

---

## 1. Resumo Executivo & Declaração do Problema

### 1.1 Problema
Atualmente, o `backend-go` utiliza o ORM GORM (`gorm.io/gorm`). Embora o GORM auxilie no desenvolvimento inicial, ele introduz:
- **Overhead de Reflection em Runtime:** Redução de performance em queries intensivas.
- **Consultas Ocultas ("Magic SQL"):** Dificuldade em otimizar e auditar queries complexas.
- **Risco de Erros em Runtime:** Faltas de colunas ou tipos incorretos são detectados apenas durante a execução das requisições.

### 1.2 Solução Proposta
Migrar 100% da camada de persistência para **SQLC** (SQL Compiler for Go) integrado com o driver nativo de alta performance **`pgx/v5`** (`github.com/jackc/pgx/v5/pgxpool`).

### 1.3 Benefícios Estratégicos & Métricas
- **[PERF-01] Latência de Banco:** Redução de até 60% no tempo de execução de queries de leitura/escrita.
- **[TYPE-01] Type-Safety em Tempo de Compilação:** 100% das consultas SQL validadas na compilação Go (`sqlc generate`).
- **[ARCH-02] Clean Architecture Preservada:** Manutenção total das interfaces e regras de negócio da camada `internal/core/services`.

---

## 2. Escopo da Migração (Fases)

### Módulos a Serem Migrados:
1. **Autenticação & Multi-Tenancy (`auth`):** Usuários, Clínicas, Cargos/Roles e Sessões.
2. **Prontuário Odontológico (`patients` & `EMR`):** Pacientes, Prontuários, Evoluções Clínicas e Documentos.
3. **Agendamento Inteligente (`appointments`):** Consultas, Tipos de Atendimento, Salas e Fila de Espera.
4. **Módulo Financeiro (`financial`):** Lançamentos de Receita/Despesa, Orçamentos e Parcelamentos.
5. **Estoque & SaaS Billing (`inventory` & `saas`):** Insumos, Transações e Assinaturas.

---

## 3. Não-Objetivos

- Não haverá alteração na API pública REST ou nos contratos de payload consumidos pelo Frontend Flutter.
- Não haverá alteração na lógica das regras de negócio existentes nos serviços do Core (`internal/core/services`).

---

## 4. Próximos Passos (Workflow Go Architect)

- **[ATUAL] Etapa 1:** Aprovação Humana deste PRD Global (`docs/global/prd-sqlc-migration.md`).
- **Etapa 2:** Elaboração da Especificação Técnica Global (`docs/global/spec-sqlc-migration.md`).
- **Etapa 3:** Fracionamento em Features Isoladas (`breakdown`).
- **Etapa 4:** Especificação Técnica Detalhada por Feature.
- **Etapa 5:** Definição dos Contratos de Transição e Interfaces (`contract.md`).
