package crm_data.scheduling.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import crm_data.scheduling.service.HolidayService;
import crm_data.scheduling.web.HolidayRequests.CreateHolidayRequest;
import crm_data.scheduling.web.HolidayRequests.UpdateHolidayRequest;
import crm_data.scheduling.web.HolidayResponses.ClinicHolidayChangeResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Feriados da clínica: só o dentista escreve (rota e {@code @PreAuthorize}); remoção física. */
@RestController
@RequestMapping("/api/v1/clinic-holidays")
@RequiredArgsConstructor
public class ClinicHolidayController {

	private final HolidayService holidays;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ClinicHolidayChangeResponse create(@Valid @RequestBody CreateHolidayRequest request) {
		return ClinicHolidayChangeResponse.from(holidays.create(request.date(), request.name()));
	}

	@PutMapping("/{id}")
	public ClinicHolidayChangeResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateHolidayRequest request) {
		return ClinicHolidayChangeResponse.from(holidays.update(id, request.date(), request.name(), request.version()));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID id) {
		holidays.delete(id);
	}
}
