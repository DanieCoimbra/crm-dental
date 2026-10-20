package crm_data.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
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

	@GetMapping("/probe/db-down")
	void dbDown() {
		throw new DataAccessResourceFailureException("connection refused host-interno:5432");
	}
}
