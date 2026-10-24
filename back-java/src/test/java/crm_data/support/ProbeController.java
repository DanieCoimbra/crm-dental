package crm_data.support;

import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import crm_data.shared.crypto.CryptoException;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.error.RateLimitedException;
import crm_data.shared.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Controlador só de teste: provoca cada tipo de erro do contrato. */
@RestController
public class ProbeController {

	public record Payload(@NotBlank(message = "obrigatório") String name,
			@Size(min = 10, message = "mínimo de 10 caracteres") String password) {
	}

	private static final Logger log = LoggerFactory.getLogger(ProbeController.class);

	@GetMapping("/probe/ok")
	String ok() {
		log.info("probe-ok-executado");
		return "ok";
	}

	@GetMapping("/api/v1/patients/probe")
	String clinicalRoute() {
		return "ok";
	}

	@GetMapping("/probe/tenant")
	String tenant() {
		return TenantContext.clinicId() + "|" + TenantContext.userId() + "|" + TenantContext.role();
	}

	@GetMapping("/probe/business/{type}")
	void business(@PathVariable ProblemTypes type) {
		throw new BusinessException(type, "mensagem de negócio");
	}

	@GetMapping("/probe/rate-limited")
	void rateLimited() {
		throw new RateLimitedException("Muitas tentativas.", 30);
	}

	@PostMapping("/probe/validate")
	void validate(@Valid @RequestBody Payload payload) {
	}

	@GetMapping("/probe/number")
	void number(@RequestParam int n) {
	}

	@GetMapping("/probe/boom")
	void boom() {
		throw new IllegalStateException("segredo interno XYZ em jdbc:postgresql://host-interno/db");
	}

	@GetMapping("/probe/admin")
	@PreAuthorize("hasRole('ADMIN')")
	void admin() {
	}

	@GetMapping("/probe/crypto")
	void crypto() {
		throw new CryptoException(CryptoException.Reason.TAMPERED, "Valor cifrado adulterado ou chave incorreta");
	}

	@GetMapping("/probe/locking")
	void locking() {
		throw new OptimisticLockingFailureException("versão antiga da entidade Foo");
	}

	/** Erro de banco com o SQLState pedido e uma mensagem que repete um CPF, como faz o driver. */
	@GetMapping("/probe/db-error/{state}")
	void dbError(@PathVariable String state) {
		String message = switch (state) {
			case "CR002" -> "ERROR: retenção vigente\n  Detail: liberado em 2046-10-22";
			case "CR002-NODATE" -> "ERROR: retenção vigente\n  Detail: formato inesperado";
			case "23505" -> "ERROR: duplicate key value violates unique constraint \"ux_patients_clinic_cpf\"\n"
					+ "  Detail: Key (clinic_id, cpf)=(x, 52998224725) already exists.";
			case "23505-BH" -> "ERROR: duplicate key value violates unique constraint \"ux_bh_clinic_weekday\"\n"
					+ "  Detail: Key (clinic_id, weekday)=(x, 1) already exists.";
			case "23505-HOLIDAY" -> "ERROR: duplicate key value violates unique constraint \"ux_clinic_holidays_date\"\n"
					+ "  Detail: Key (clinic_id, holiday_date)=(x, 2026-12-24) already exists.";
			case "23P01" -> "ERROR: conflicting key value violates exclusion constraint \"ex_appt_no_overlap\"\n"
					+ "  Detail: Key (clinic_id, tstzrange(starts_at, ends_at))=(x, [2026-11-03 13:00,2026-11-03 14:00)) conflicts"
					+ " with existing key; paciente 52998224725.";
			default -> "ERROR: falha " + state + " cpf 52998224725";
		};
		String sqlState = state.startsWith("CR002") ? "CR002" : state.startsWith("23505") ? "23505" : state;
		throw new DataIntegrityViolationException("could not execute statement", new SQLException(message, sqlState));
	}

	/** Regra de negócio com {@code reason}, como as da agenda (422). */
	@GetMapping("/probe/business-reason")
	void businessReason() {
		throw new crm_data.shared.error.BusinessException(crm_data.shared.error.ProblemTypes.BUSINESS_RULE,
				"Horário fora do expediente.", "OUTSIDE_HOURS");
	}

	/** Erro de banco embrulhado numa exceção que NÃO é DataAccessException (cai no handler genérico). */
	@GetMapping("/probe/wrapped-sql")
	void wrappedSql() {
		throw new IllegalStateException("falha ao confirmar",
				new SQLException("ERROR: duplicate key\n  Detail: Key (cpf)=(52998224725) already exists.", "23505"));
	}

	@GetMapping("/probe/storage-down")
	void storageDown() {
		throw new crm_data.shared.storage.StorageException("Falha ao gravar no Storage (HTTP 401).",
				new IllegalStateException("causa com https://segredo.supabase.co/chave"));
	}

	@GetMapping("/probe/db-down")
	void dbDown() {
		throw new DataAccessResourceFailureException("connection refused host-interno:5432");
	}
}
