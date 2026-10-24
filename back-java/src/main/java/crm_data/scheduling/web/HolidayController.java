package crm_data.scheduling.web;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import crm_data.scheduling.service.HolidayService;
import crm_data.scheduling.web.HolidayResponses.HolidayResponse;
import lombok.RequiredArgsConstructor;

/** Feriados nacionais ∪ da clínica, em datas locais, para os dois papéis. */
@RestController
@RequestMapping("/api/v1/holidays")
@RequiredArgsConstructor
public class HolidayController {

	private final HolidayService holidays;

	@GetMapping
	public List<HolidayResponse> list(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return HolidayResponse.from(holidays.list(from, to));
	}
}
