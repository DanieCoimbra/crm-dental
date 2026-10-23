-- =====================================================================
-- V014__core_clinic_timezone.sql | Fase 2 / F01-extensions-and-timezone
-- Objetivo : fuso da clínica (core.clinics.timezone), com validação do nome contra pg_timezone_names e troca
--            restrita ao DENTIST_ADMIN. O banco NÃO calcula "hoje" nem a semana: o backend lê o fuso e passa
--            ao banco os intervalos [início, fim) já convertidos para timestamptz.
-- Executar : scripts/apply_migrations.sh, como crm_owner.
-- Reverter : database/rollback/U014__core_clinic_timezone.sql
-- Depende  : V003 (clinics), V007 (core.current_app_role).
-- Erros    : 23514 fuso inexistente ou com menos de 3 caracteres; 42501 papel diferente de DENTIST_ADMIN trocando o fuso.
-- Observação: a extensão btree_gist (F02) vem do bootstrap/20_extensions.sql, não desta migration.
--            ADD COLUMN com DEFAULT constante é só metadado: não reescreve a tabela nem dispara triggers.
-- =====================================================================

ALTER TABLE core.clinics
  ADD COLUMN timezone varchar(50) NOT NULL DEFAULT 'America/Sao_Paulo';

ALTER TABLE core.clinics
  ADD CONSTRAINT ck_clinics_timezone CHECK (char_length(timezone) >= 3);

-- Um CHECK não pode consultar pg_timezone_names; por isso a validação do nome é um trigger.
-- SECURITY INVOKER: roda com o papel de quem grava (crm_app, ou crm_definer nas funções de cadastro da V006),
-- que já têm EXECUTE em core.current_app_role() e leem pg_timezone_names (visão pública).
CREATE FUNCTION core.validate_clinic_timezone() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  -- o JPA reenvia todas as colunas em todo UPDATE: valor igual ao antigo não custa consulta nem exige papel
  IF TG_OP = 'UPDATE' AND NEW.timezone IS NOT DISTINCT FROM OLD.timezone THEN
    RETURN NEW;
  END IF;
  -- IS DISTINCT FROM: papel ausente (NULL) também é negado
  IF TG_OP = 'UPDATE' AND core.current_app_role() IS DISTINCT FROM 'DENTIST_ADMIN' THEN
    RAISE EXCEPTION 'apenas o dentista altera o fuso' USING ERRCODE = '42501';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_timezone_names n WHERE n.name = NEW.timezone) THEN
    RAISE EXCEPTION 'fuso invalido' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$;

-- Função de trigger: o EXECUTE é verificado só de quem cria o trigger (crm_owner, dono da função). Nenhuma concessão.
REVOKE EXECUTE ON FUNCTION core.validate_clinic_timezone() FROM PUBLIC;

CREATE TRIGGER trg_clinics_validate_timezone
  BEFORE INSERT OR UPDATE OF timezone ON core.clinics
  FOR EACH ROW EXECUTE FUNCTION core.validate_clinic_timezone();
