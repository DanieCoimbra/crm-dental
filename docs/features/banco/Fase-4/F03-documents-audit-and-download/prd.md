# PRD — F03 Auditoria e download de documentos (Fase 4)

**Derivação:** PRD global da Fase 4, US-405, decisão D8 e FR-9, FR-10; spec global, seções 2.7, 4.3, 4.4 e S8, S9.
**Dependências externas:** F02 (`clinical_documents`); Fase 1 F-auditoria (`audit_log`, `write_audit`, V012) e o padrão da concessão temporária (V020/V028). Consumida pelo backend (chamada de `record_document_download`) e por F04 (a linha `ANONYMIZE` por documento nasce do trigger daqui).

## 1. Introdução

- **Problema:** é preciso saber quem emitiu, anulou, baixou ou imprimiu cada documento clínico, e a `write_audit()` só conhece `INSERT`/`UPDATE`/`DELETE`.
- **Solução:** `audit_log` passa a aceitar `clinical_documents` e as ações `VOID` e `DOWNLOAD`; uma função de trigger própria classifica `INSERT`/`VOID`/`ANONYMIZE`; uma função `SECURITY DEFINER` registra `DOWNLOAD`.
- **Metas:**
  1. 100% das emissões e anulações com linha de auditoria, na mesma transação.
  2. Cada download ou impressão (PDF do backend) com uma linha `DOWNLOAD`.
  3. Recepção e clínica errada: 0 linhas gravadas.
  4. `audit_log` continua só de inserção.

## 2. User stories

### US-001: Trilha de emissão e anulação
**Descrição:** Como dentista, quero a trilha de quem emitiu e anulou cada documento.

**Critérios de aceite:**
- [ ] `ck_audit_table` inclui `clinical_documents`; `ck_audit_action` inclui `VOID` e `DOWNLOAD` (recriadas sobre a lista vigente lida do catálogo).
- [ ] `trg_cdoc_audit` (`AFTER INSERT OR UPDATE`) grava `INSERT`; `UPDATE` de anulação grava `VOID`; `UPDATE` de anonimização grava `ANONYMIZE`.
- [ ] Só identificadores: `clinic_id`, `user_id`, `record_id`, `occurred_at`; nenhum valor.
- [ ] Sem `app.user_id` a operação reverte com `28000`.

### US-002: Registrar download
**Descrição:** Como dentista, quero que cada download ou impressão do documento fique registrado.

**Critérios de aceite:**
- [ ] `core.record_document_download(uuid)`: papel ≠ `DENTIST_ADMIN` → `42501`; sem sessão → `28000`; documento de outra clínica ou inexistente → `P0002`; anonimizado → `CR003`.
- [ ] Sucesso grava uma linha `DOWNLOAD` com o usuário da sessão; chamadas repetidas gravam linhas repetidas.
- [ ] `EXECUTE` só para `crm_app`; revogado de `PUBLIC`.

## 3. Requisitos funcionais
- **FR-1:** Constraints do `audit_log` estendidas sem perder linhas existentes.
- **FR-2:** `write_document_audit()` (`SECURITY DEFINER`, `search_path` fixo) classifica a operação; `UPDATE` fora das duas transições falha.
- **FR-3:** `record_document_download` conforme a US-002.
- **FR-4:** `U033` falha de forma atômica se houver auditoria de documentos gravada.

## 4. Non-goals
- Auditar leitura em tela (lista e detalhe); valores antigo e novo; deduplicar downloads; registrar o IP ou o dispositivo.
- A geração do PDF e a ordem exata de entrega (contrato do backend).

## 5. Requisitos de IA
Não se aplica.

## 6. Especificações técnicas
Ver `spec.md`. A função de download é a única superfície `SECURITY DEFINER` nova com chamada direta do `crm_app`.

## 7. Riscos e perguntas abertas
1. Recriar as constraints toma lock curto em tabela pequena; conferir a lista vigente (V028/V030) antes de recriar.
2. Superfície nova de escalada: papel e tenant validados dentro da função.
3. `DOWNLOAD` gravado e PDF não entregue (ou o contrário): mesma transação, contrato do backend.
- Perguntas abertas: nenhuma.
