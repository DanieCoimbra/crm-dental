package crm_data.clinic.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import crm_data.clinic.service.ClinicService;
import crm_data.clinic.service.ClinicService.ClinicData;
import crm_data.clinic.web.ClinicRequests.UpdateClinicRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Dados da clínica do usuário logado: leitura para os dois papéis, edição só para o dentista. */
@RestController
@RequestMapping("/api/v1/clinic")
@RequiredArgsConstructor
public class ClinicController {

	private final ClinicService clinics;

	@GetMapping
	public ClinicResponse get() {
		return ClinicResponse.from(clinics.get());
	}

	@PutMapping
	public ClinicResponse update(@Valid @RequestBody UpdateClinicRequest request) {
		ClinicData data = new ClinicData(request.name(), request.document(), request.phone(), request.email(),
				request.address(), request.timezone());
		return ClinicResponse.from(clinics.update(data, request.version()));
	}
}
