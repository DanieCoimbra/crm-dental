package crm_data.catalog.web;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import crm_data.catalog.domain.ProcedureType;
import crm_data.catalog.service.ProcedureTypeService;
import crm_data.catalog.service.ProcedureTypeService.ActiveFilter;
import crm_data.catalog.web.ProcedureTypeRequests.CreateProcedureTypeRequest;
import crm_data.catalog.web.ProcedureTypeRequests.SetActiveRequest;
import crm_data.catalog.web.ProcedureTypeRequests.UpdateProcedureTypeRequest;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Catálogo de procedimentos: os dois papéis leem; só o dentista cria, edita e ativa/inativa (rota e service). */
@RestController
@RequestMapping("/api/v1/procedure-types")
@RequiredArgsConstructor
public class ProcedureTypeController {

	private final ProcedureTypeService procedureTypes;

	/** {@code active=true} (padrão), {@code false} ou {@code all}; sem paginação (dezenas de linhas). */
	@GetMapping
	public List<ProcedureTypeResponse> list(@RequestParam(defaultValue = "true") String active) {
		return ProcedureTypeResponse.from(procedureTypes.list(parse(active)));
	}

	@GetMapping("/{id}")
	public ProcedureTypeResponse get(@PathVariable UUID id) {
		return ProcedureTypeResponse.from(procedureTypes.get(id));
	}

	@PostMapping
	public ResponseEntity<ProcedureTypeResponse> create(@Valid @RequestBody CreateProcedureTypeRequest request) {
		ProcedureType created = procedureTypes.create(request.name(), request.price(), request.minutes(), request.color());
		return ResponseEntity.created(URI.create("/api/v1/procedure-types/" + created.getId()))
				.body(ProcedureTypeResponse.from(created));
	}

	@PutMapping("/{id}")
	public ProcedureTypeResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateProcedureTypeRequest request) {
		return ProcedureTypeResponse.from(procedureTypes.update(id, request.name(), request.price(), request.minutes(),
				request.color(), request.version()));
	}

	@PatchMapping("/{id}/active")
	public ProcedureTypeResponse setActive(@PathVariable UUID id, @Valid @RequestBody SetActiveRequest request) {
		return ProcedureTypeResponse.from(procedureTypes.setActive(id, request.active(), request.version()));
	}

	private static ActiveFilter parse(String value) {
		return switch (value.strip().toLowerCase(Locale.ROOT)) {
			case "true" -> ActiveFilter.ACTIVE;
			case "false" -> ActiveFilter.INACTIVE;
			case "all" -> ActiveFilter.ALL;
			default -> throw new BusinessException(ProblemTypes.VALIDATION, "O filtro active deve ser true, false ou all.");
		};
	}
}
