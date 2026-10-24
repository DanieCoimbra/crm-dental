package crm_data.scheduling.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import crm_data.scheduling.service.BusinessHoursService;
import crm_data.scheduling.web.BusinessHoursRequests.UpdateBusinessHoursRequest;
import crm_data.scheduling.web.BusinessHoursResponses.BusinessDayResponse;
import crm_data.scheduling.web.BusinessHoursResponses.BusinessHoursUpdateResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Expediente semanal: leitura para os dois papéis, configuração só para o dentista (rota e {@code @PreAuthorize}). */
@RestController
@RequestMapping("/api/v1/business-hours")
@RequiredArgsConstructor
public class BusinessHoursController {

	private final BusinessHoursService businessHours;

	@GetMapping
	public List<BusinessDayResponse> week() {
		return BusinessDayResponse.from(businessHours.week());
	}

	/** {@code dryRun=true} valida e devolve o aviso de agendamentos afetados sem gravar. */
	@PutMapping
	public BusinessHoursUpdateResponse update(@Valid @RequestBody UpdateBusinessHoursRequest request,
			@RequestParam(defaultValue = "false") boolean dryRun) {
		return BusinessHoursUpdateResponse.from(businessHours.update(request.toInputs(), dryRun));
	}
}
