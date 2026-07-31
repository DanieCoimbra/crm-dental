# Plano de Ação & Arquitetura de Domínios: Fase 1 (Núcleo Clínico - BD)

**Documento:** Feature Breakdown & Scaffolding de Banco de Dados  
**PRD de Origem:** `docs/global/prd-fase1-bd.md`  
**Especificação de Origem:** `docs/global/spec-fase1-bd.md`  
**Projeto:** SaaS Clínica Dental Solo  
**Autor:** Arquiteto de Banco de Dados e Cibersegurança Sênior (`supabase-sec-dba`)  

---

## 1. Visão Geral do Breakdown

Com base nos requisitos globais de banco de dados e cibersegurança da **Fase 1 (Núcleo Clínico)**, o domínio de dados foi fracionado em 3 sub-domínios (features) isolados e altamente coesos:

```
docs/features/
├── F01-patients-db/            # Domínio de Dados Cadastrais do Paciente & LGPD
│   └── plan.md
├── F02-odontogram-db/           # Domínio de Dados do Odontograma & Imutabilidade (FDI)
│   └── plan.md
└── F03-clinical-records-db/     # Domínio de Prontuário, Evolução e Arquivos/Exames
    └── plan.md
```

---

## 2. Matriz dos Domínios Fracionados

| ID Feature | Nome do Domínio | Responsabilidade em Isolamento | Dependências de Dados |
|---|---|---|---|
| **F01-patients-db** | Gestão de Pacientes & Criptografia | Tabela `patients`, criptografia AES-256 (CPF/Telefone), Soft Delete, RLS compartilhado (Admin + Recepcionista). | Depende das tabelas `clinics` e `users` (Fase 0). |
| **F02-odontogram-db** | Odontograma Interativo & Audit Trail | Tabelas `teeth_status` e `teeth_history`, validação FDI (11-48, 51-85), RLS exclusivo para `admin`, imutabilidade por Trigger. | Depende de `patients` (F01) e `users` (Fase 0). |
| **F03-clinical-records-db** | Prontuários & Anexos de Exames | Tabelas `clinical_notes` e `patient_files`, criptografia de queixa/diagnóstico, RLS exclusivo para `admin`. | Depende de `patients` (F01) e `users` (Fase 0). |

---

## 3. Próximos Passos (Workflow Supabase Sec DBA)

1. **[CONCLUÍDO] Etapa 1:** PRD Global de BD (`docs/global/prd-fase1-bd.md`).
2. **[CONCLUÍDO] Etapa 2:** Especificação Técnica Global de BD (`docs/global/spec-fase1-bd.md`).
3. **[ATUAL] Etapa 3:** Breakdown e Scaffolding da arquitetura de domínios (Scaffolded em `docs/features/`).
4. **Etapa 4 (`spec-write`):** Elaborar a Especificação Técnica Detalhada por Domínio (`spec.md` em cada pasta).
5. **Etapa 5 (`contract`):** Definir os Contratos de Dados, DDLs e RLS finais (`contract.md` em cada pasta).
