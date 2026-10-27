package crm_data.dashboard.web;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import crm_data.dashboard.service.DashboardResponse;
import crm_data.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;

/** Dashboard da tela inicial, para os dois papéis; a recepção não recebe o faturamento. */
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

	private final DashboardService dashboard;

	/** @param weekOf qualquer dia da semana desejada ({@code yyyy-MM-dd}); sem o parâmetro, a semana de hoje */
	@GetMapping
	public ResponseEntity<DashboardResponse> get(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekOf) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(dashboard.get(weekOf));
	}
}
