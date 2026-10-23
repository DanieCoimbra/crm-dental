# PRD — F01 Extensão `btree_gist` e fuso da clínica (Fase 2)

**Derivação:** PRD global da Fase 2, US-202 (extensão), US-208 (fuso), decisões A3 e A11, FR-9; spec global, seções 1.2 (S4, S11), 2.3 (`clinics`), 4.3, 5.2 e 5.3.
**Dependências externas:** Fase 0 (`core.clinics`, `core.forbid_immutable_columns`, papéis), Fase 1 F01 (`core.current_app_role()`), `bootstrap/20_extensions.sql` da Fase 1 (estendido aqui). Consumida por F02 (exige `btree_gist`) e F03 (horário comercial em hora local da clínica).

## 1. Introdução

- **Problema:** a agenda precisa de uma extensão que o dono das migrations não pode criar e de um fuso por clínica para o backend converter "hoje" e "a semana" em intervalos de `timestamptz`.
- **Solução:** estender o bootstrap com `btree_gist` (idempotente) e acrescentar `clinics.timezone`, validada por trigger e alterável só pelo dentista.
- **Metas:**
  1. `btree_gist` presente no schema `extensions` antes de qualquer migration da Fase 2; sem ela, a F02 aborta com mensagem clara.
  2. 100% das clínicas existentes passam a ter `timezone = 'America/Sao_Paulo'` sem reescrever dados.
  3. Fuso inexistente nunca é gravado; só `DENTIST_ADMIN` altera o fuso.

## 2. User stories

### US-001: Instalar `btree_gist` no bootstrap
**Descrição:** Como responsável pelo banco, quero a extensão instalada por quem pode instalá-la, para a garantia de conflito de agenda existir no banco.

**Critérios de aceite:**
- [ ] `bootstrap/20_extensions.sql` cria `btree_gist` com `WITH SCHEMA extensions`, mesmo tratamento de erro e verificação de schema do `pg_trgm`.
- [ ] O arquivo continua idempotente: duas execuções seguidas não falham.
- [ ] Mensagem de erro cita que sem `btree_gist` não há garantia de conflito de agenda no banco (sem plano B).
- [ ] O `crm_app`, o `crm_backup` e o `crm_definer` continuam sem `USAGE` em `extensions` (o aviso existente do bootstrap permanece).

### US-002: Fuso da clínica
**Descrição:** Como backend, quero ler o fuso da clínica para converter intervalos de dia e semana.

**Critérios de aceite:**
- [ ] `core.clinics.timezone varchar(50) NOT NULL DEFAULT 'America/Sao_Paulo'` com `CHECK (char_length(timezone) >= 3)`; clínicas existentes recebem o default.
- [ ] Trigger `BEFORE INSERT OR UPDATE OF timezone` rejeita nome fora de `pg_timezone_names` com `23514`.
- [ ] `UPDATE` que reenvia o mesmo valor passa para qualquer papel (curto-circuito).
- [ ] `UPDATE` que muda o valor exige `app.role = DENTIST_ADMIN`; caso contrário `42501`.
- [ ] O `INSERT` pelas funções de cadastro da V006 usa o default e não é bloqueado.

## 3. Requisitos funcionais

- **FR-1:** `bootstrap/20_extensions.sql` ampliado (como `postgres`, uma vez, reexecutável).
- **FR-2:** Migration `V014__core_clinic_timezone.sql`: coluna, `CHECK`, `core.validate_clinic_timezone()` e `trg_clinics_validate_timezone`.
- **FR-3:** Rollback `U014` remove trigger, função e coluna; o bootstrap não é migration (ver `database/rollback/README.md`).
- **FR-4:** Nenhum grant novo: `clinics` mantém `SELECT, INSERT, UPDATE` do `crm_app`.

## 4. Fora do escopo

- Calcular "hoje" ou a semana no banco (backend).
- Validar o fuso contra uma lista própria; a fonte é `pg_timezone_names`.
- Mudar as funções de cadastro (V006) para receber o fuso: o dentista o ajusta depois.
- Plano B sem `btree_gist`.

## 5. Considerações técnicas e riscos

- A consulta a `pg_timezone_names` custa alguns milissegundos; só roda na criação da clínica e na troca do fuso.
- O JPA reenvia `timezone` em todo `UPDATE` de `clinics`; o curto-circuito evita custo e falso `42501` para a recepção.
- **Risco:** `btree_gist` pode não ser permitida no projeto Supabase real; é pré-requisito de aceite da fase (spec global, seção 10).
- **Pergunta aberta:** nenhuma.
