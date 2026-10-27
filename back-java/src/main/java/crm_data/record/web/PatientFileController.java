package crm_data.record.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import crm_data.record.service.PatientFileService;
import crm_data.record.service.PatientFileService.SignedUrl;
import crm_data.record.service.PatientFileService.Upload;
import crm_data.shared.web.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/**
 * Exames do paciente. O dentista e a recepção anexam; só o dentista lista, baixa (URL assinada de 60 s) e remove. As
 * respostas levam {@code Cache-Control: no-store}.
 */
@RestController
@RequestMapping("/api/v1/patients/{patientId}/files")
@Validated
@RequiredArgsConstructor
public class PatientFileController {

	private final PatientFileService files;

	/** Multipart com {@code file} e {@code kind}. Devolve só o id. */
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public UploadResponse upload(@PathVariable UUID patientId, @RequestParam("file") MultipartFile file,
			@RequestParam("kind") String kind) {
		Upload upload = new Upload(kind, file.getContentType(), file.getOriginalFilename(), file.getSize(), file::getInputStream);
		return new UploadResponse(files.upload(patientId, upload));
	}

	@GetMapping
	public PageResponse<FileResponse> list(@PathVariable UUID patientId,
			@RequestParam(defaultValue = "0") @Min(value = 0, message = "deve ser maior ou igual a 0") @Max(value = 100000, message = "deve ser no máximo 100000") int page,
			@RequestParam(defaultValue = "20") @Min(value = 1, message = "deve estar entre 1 e 50") @Max(value = 50, message = "deve estar entre 1 e 50") int size) {
		return PageResponse.of(files.list(patientId, page, size), FileResponse::from);
	}

	@GetMapping("/{fileId}/download-url")
	public SignedUrlResponse downloadUrl(@PathVariable UUID patientId, @PathVariable UUID fileId) {
		SignedUrl signed = files.signedUrl(patientId, fileId);
		return new SignedUrlResponse(signed.url().toString(), signed.expiresInSeconds());
	}

	/** Remoção lógica: o registro e o objeto permanecem até a anonimização. */
	@DeleteMapping("/{fileId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void remove(@PathVariable UUID patientId, @PathVariable UUID fileId) {
		files.remove(patientId, fileId);
	}
}
