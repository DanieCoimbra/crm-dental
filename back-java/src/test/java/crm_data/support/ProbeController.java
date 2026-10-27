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

	/** Documento adulterado: hash não confere (500 {@code integrity}); a mensagem leva só o id. */
	@GetMapping("/probe/integrity")
	void integrity() {
		throw new crm_data.shared.error.IntegrityException("documento id=00000000");
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
			case "23505-PTYPE" -> unique("ux_ptype_clinic_name", "Key (clinic_id, lower(name))=(x, restauracao) already exists.");
			case "23505-REVERSE" -> unique("ux_pay_reverses", "Key (reverses_payment_id)=(x) already exists.");
			case "23505-PLAN" -> unique("ux_inst_budget_number", "Key (budget_id, number)=(x, 1) already exists.");
			case "23505-NUM" -> unique("ux_budgets_clinic_number", "Key (clinic_id, number)=(x, 3) already exists.");
			case "23514-ITEM" -> check("budget_items", "ck_items_line", "Failing row contains (x, 1000000.00).");
			case "23514-TOTAL" -> check("budgets", "ck_budgets_total", "Failing row contains (x, -5.00).");
			case "23514-OTHER" -> check("budgets", "ck_budgets_notes", "Failing row contains (x, paciente 52998224725).");
			case "23P01" -> "ERROR: conflicting key value violates exclusion constraint \"ex_appt_no_overlap\"\n"
					+ "  Detail: Key (clinic_id, tstzrange(starts_at, ends_at))=(x, [2026-11-03 13:00,2026-11-03 14:00)) conflicts"
					+ " with existing key; paciente 52998224725.";
			default -> "ERROR: falha " + state + " cpf 52998224725";
		};
		String sqlState = state.startsWith("CR002") ? "CR002" : state.startsWith("23505") ? "23505"
				: state.startsWith("23514") ? "23514" : state;
		throw new DataIntegrityViolationException("could not execute statement", new SQLException(message, sqlState));
	}

	/** Corpo de dinheiro como o das features da Fase 3: texto decimal validado por regex (nunca número JSON). */
	public record MoneyPayload(
			@jakarta.validation.constraints.NotNull(message = "obrigatório") @jakarta.validation.constraints.Pattern(regexp = "^\\d{1,6}(\\.\\d{1,2})?$", message = "valor monetário inválido") String amount) {
	}

	@PostMapping("/probe/money")
	String money(@Valid @RequestBody MoneyPayload payload) {
		return crm_data.shared.money.Money.format(crm_data.shared.money.Money.parse(payload.amount()));
	}

	/** Falha de commit sem erro de banco na causa (por exemplo, falha de sincronização). */
	@GetMapping("/probe/commit-error-no-sql")
	void commitErrorNoSql() {
		throw new org.springframework.transaction.TransactionSystemException("Could not commit JPA transaction",
				new IllegalStateException("sincronização falhou"));
	}

	private static String unique(String constraint, String detail) {
		return "ERROR: duplicate key value violates unique constraint \"" + constraint + "\"\n  Detail: " + detail;
	}

	private static String check(String relation, String constraint, String detail) {
		return "ERROR: new row for relation \"" + relation + "\" violates check constraint \"" + constraint
				+ "\"\n  Detail: " + detail;
	}

	/**
	 * Erro de banco que só estoura no {@code COMMIT} (por exemplo {@code CR011}, constraint trigger adiado): o Spring o
	 * entrega como {@link org.springframework.transaction.TransactionSystemException}, que não é {@code DataAccessException}.
	 */
	@GetMapping("/probe/commit-error/{state}")
	void commitError(@PathVariable String state) {
		throw new org.springframework.transaction.TransactionSystemException("Could not commit JPA transaction",
				new IllegalStateException("rollback",
						new SQLException("ERROR: parcelas não somam o total\n  Detail: soma 799.99 total 800.00 cpf 52998224725",
								state)));
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
