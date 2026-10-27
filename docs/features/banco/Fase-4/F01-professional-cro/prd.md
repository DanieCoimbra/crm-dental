# PRD — F01 CRO do profissional (Fase 4)

**Derivação:** PRD global da Fase 4, US-401, decisão D4 e FR-8; spec global, seção 2.2 e S2.
**Dependências externas:** Fase 0 (`core.users`, V004; `forbid_immutable_columns`; funções de sessão da V007). Consumida por F02 (o emissor precisa de CRO, `CR012`) e pelo perfil do dentista no backend.

## 1. Introdução

- **Problema:** atestado e receita precisam identificar o dentista (CRO e UF), e `users` não guarda esses dados.
- **Solução:** duas colunas opcionais em `core.users`, só para `DENTIST_ADMIN`, os dois campos juntos ou nenhum, editáveis apenas pelo próprio dono, com guarda em trigger.
- **Metas:**
  1. 100% dos formatos inválidos (UF, número, par incompleto, recepcionista com CRO) recusados por `CHECK` (`23514`).
  2. 0 alterações de CRO por outro usuário (`42501`).
  3. Migration aditiva, sem reescrever a tabela, com rollback.

## 2. User stories

### US-001: Registrar o CRO
**Descrição:** Como dentista, quero informar meu CRO e UF para que meus documentos saiam identificados.

**Critérios de aceite:**
- [ ] `cro_number varchar(15)` (só dígitos, 1 a 15) e `cro_state varchar(2)` (UF válida em maiúsculas), ambos anuláveis.
- [ ] `ck_users_cro_pair`: os dois preenchidos ou os dois nulos.
- [ ] `ck_users_cro_role`: CRO preenchido só quando `role = 'DENTIST_ADMIN'`.
- [ ] Usuários existentes ficam com CRO nulo (migration aditiva).

### US-002: Só o dono altera o CRO
**Descrição:** Como titular do cadastro, quero que ninguém altere meu CRO.

**Critérios de aceite:**
- [ ] `guard_user_cro` (`BEFORE UPDATE`): mudança de `cro_number` ou `cro_state` com `app.user_id` diferente do `id` da linha → `42501`.
- [ ] Atualizações que não tocam o CRO (nome, senha, `active`) seguem como antes.
- [ ] O registro da clínica (função da V006) continua criando o dentista sem CRO.

## 3. Requisitos funcionais
- **FR-1:** `core.users` aceita `cro_number` e `cro_state` conforme a US-001.
- **FR-2:** O trigger `guard_user_cro` aplica a US-002 sem alterar RLS nem `GRANT`.
- **FR-3:** `U031` remove trigger, `CHECK`s e colunas e **apaga o CRO já informado** (destrutiva por convenção do projeto; com dados, restaurar do `pg_dump`).

## 4. Non-goals
- Validação do CRO junto ao conselho, histórico de CRO, CRO de recepcionista, CRO único entre clínicas (sem `UNIQUE`).
- Tabela de profissionais separada (um dentista por clínica, `ux_users_one_dentist`).

## 5. Requisitos de IA
Não se aplica.

## 6. Especificações técnicas
Ver `spec.md` e a spec global, seção 2.2. Integração: o perfil do dentista no backend passa a ler e gravar os campos.

## 7. Riscos e perguntas abertas
1. O `GRANT UPDATE` de `users` é da tabela inteira: a guarda de coluna depende só do trigger (testar o caminho).
2. Funções com colunas explícitas (V006) não mudam; conferir `schema_dictionary`.
- Perguntas abertas: nenhuma.
