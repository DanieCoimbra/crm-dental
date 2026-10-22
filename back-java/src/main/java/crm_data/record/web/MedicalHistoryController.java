package crm_data.record.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import crm_data.record.service.MedicalHistoryService;
import crm_data.record.service.MedicalHistoryService.Saved;
import crm_data.record.web.RecordRequests.SaveHistoryRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Histórico médico do paciente, só para o dentista. Atenção: toda gravação reinicia a contagem de 20 anos de
 * retenção legal do prontuário.
 */
@RestController
@RequestMapping("/api/v1/patients/{patientId}/medical-history")
@RequiredArgsConstructor
public class MedicalHistoryController {

	private final MedicalHistoryService histories;

	@GetMapping
	public MedicalHistoryResponse get(@PathVariable UUID patientId) {
		return MedicalHistoryResponse.from(histories.get(patientId));
	}

	/** 201 ao criar, 200 ao atualizar (a atualização exige a {@code version} atual). */
	@PutMapping
	public ResponseEntity<MedicalHistoryResponse> save(@PathVariable UUID patientId,
			@Valid @RequestBody SaveHistoryRequest request) {
		Saved saved = histories.save(patientId, request.content(), request.version());
		return ResponseEntity.status(saved.created() ? HttpStatus.CREATED : HttpStatus.OK)
				.body(MedicalHistoryResponse.from(saved.history()));
	}
}
