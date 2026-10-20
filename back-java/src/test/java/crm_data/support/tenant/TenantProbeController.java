package crm_data.support.tenant;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Controlador só de teste (contexto completo): lê usuários do tenant da requisição. */
@RestController
public class TenantProbeController {

	private final TenantProbeService service;

	public TenantProbeController(TenantProbeService service) {
		this.service = service;
	}

	/** O parâmetro {@code clinicId} existe para provar que é ignorado: o tenant vem só do token. */
	@GetMapping("/probe/tenant/users")
	List<UUID> users(@RequestParam(required = false) String clinicId) {
		return service.visibleUserIds();
	}
}
