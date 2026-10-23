# Spec — F01 Extensão `btree_gist` e fuso da clínica (Fase 2)

Complementa `docs/global/banco/Fase-2/spec.md` (seções 1.2, 2.3, 4.3, 5.2 e 5.3).

## 1. Visão técnica
- **Feature:** extensão `btree_gist` no bootstrap e `core.clinics.timezone`.
- **Stack:** psql, PL/pgSQL, `V014`, `bootstrap/20_extensions.sql`.
- **Abordagem:** o bootstrap (como `postgres`) instala a extensão; a migration (como `crm_owner`) adiciona a coluna e um trigger `SECURITY INVOKER` que valida o nome e restringe a troca ao dentista.

## 2. Objetos

### 2.1 `bootstrap/20_extensions.sql` (estendido)
Dentro do bloco `DO` existente, depois do `pg_trgm`:
```sql
BEGIN
  CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA extensions;
EXCEPTION
  WHEN insufficient_privilege OR feature_not_supported OR undefined_file THEN
    RAISE EXCEPTION 'extensions: nao foi possivel criar btree_gist (%). Sem ela nao ha garantia de conflito de agenda no banco', SQLERRM;
END;

IF NOT EXISTS (SELECT 1 FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
                WHERE e.extname = 'btree_gist' AND n.nspname = 'extensions') THEN
  RAISE EXCEPTION 'extensions: btree_gist precisa estar no schema extensions (esta em outro schema)';
END IF;
```
O cabeçalho do arquivo passa a citar a Fase 2 (F01) e o comentário "Plano B" ganha: "btree_gist: sem plano B". O restante (grant de `USAGE` ao `crm_owner`, avisos de `USAGE` herdado) não muda.

### 2.2 `V014__core_clinic_timezone.sql` (como `crm_owner`, transação única)
```sql
ALTER TABLE core.clinics
  ADD COLUMN timezone varchar(50) NOT NULL DEFAULT 'America/Sao_Paulo';
ALTER TABLE core.clinics
  ADD CONSTRAINT ck_clinics_timezone CHECK (char_length(timezone) >= 3);

CREATE FUNCTION core.validate_clinic_timezone() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND NEW.timezone IS NOT DISTINCT FROM OLD.timezone THEN
    RETURN NEW;                                   -- o JPA reenvia a coluna em todo UPDATE
  END IF;
  IF TG_OP = 'UPDATE' AND core.current_app_role() IS DISTINCT FROM 'DENTIST_ADMIN' THEN
    RAISE EXCEPTION 'apenas o dentista altera o fuso' USING ERRCODE = '42501';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_timezone_names n WHERE n.name = NEW.timezone) THEN
    RAISE EXCEPTION 'fuso invalido' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

REVOKE EXECUTE ON FUNCTION core.validate_clinic_timezone() FROM PUBLIC;

CREATE TRIGGER trg_clinics_validate_timezone
  BEFORE INSERT OR UPDATE OF timezone ON core.clinics
  FOR EACH ROW EXECUTE FUNCTION core.validate_clinic_timezone();
```
`ADD COLUMN ... DEFAULT` constante é só metadado; não dispara triggers nem reescreve linhas. A função não é `SECURITY DEFINER`: roda com o papel do chamador (`crm_app`, ou `crm_definer` dentro das funções de cadastro), que já têm `EXECUTE` em `core.current_app_role()` e `SELECT` em `pg_timezone_names` (público).

## 3. Componentes
`bootstrap/20_extensions.sql`, `V014`, `U014`, `tests/clinic_timezone.sql` e a ampliação de `bootstrap_idempotence` (F08).

## 4. Lógica
1. O operador executa o bootstrap como `postgres`; a extensão fica em `extensions`.
2. A V014 acrescenta a coluna; linhas existentes assumem o default.
3. Em `INSERT`, o trigger só valida o nome. Em `UPDATE`, compara com o valor antigo: igual, passa; diferente, exige dentista e nome válido.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| `btree_gist` não pode ser criada | O bootstrap aborta com a mensagem acima |
| Extensão instalada em outro schema | O bootstrap aborta (a F02 usa `extensions`) |
| Fuso inexistente (`Mars/Phobos`) | `23514` |
| Recepção troca o fuso | `42501` |
| Recepção reenvia o mesmo fuso | Passa |
| `app.role` ausente e fuso diferente | `42501` |
| `timezone` com 2 caracteres | `23514` (`ck_clinics_timezone`) |
| Cadastro de clínica pelas funções da V006 | Usa o default; passa |

## 6. Segurança e desempenho
- Sem novo privilégio nem política: a RLS de `clinics` (Fase 0) não muda.
- O trigger só faz consulta cara quando o fuso muda ou na criação da clínica (milissegundos, volume irrisório).
- O papel dentro da clínica vem de `app.role` (limite aceito da arquitetura, como nas Fases 0 e 1).
