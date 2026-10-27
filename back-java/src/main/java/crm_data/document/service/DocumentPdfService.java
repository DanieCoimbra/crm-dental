package crm_data.document.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.auth.service.ProfessionalIdentity;
import crm_data.clinic.service.ClinicIdentity;
import crm_data.clinic.service.ClinicZones;
import crm_data.document.domain.ClinicalDocument;
import crm_data.document.repository.ClinicalDocumentRepository;
import crm_data.patient.service.PatientBrief;
import crm_data.patient.service.PatientLookup;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ConflictMessages;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;

/**
 * PDF sob demanda do documento clínico, sem passar pelo Storage. Numa única transação: carrega, confere o selo, grava o
 * {@code DOWNLOAD}, decifra, monta o modelo e renderiza em memória. Qualquer falha (selo, tamanho acima de 4 MB, renderização)
 * desfaz a transação inteira, então nenhum {@code DOWNLOAD} fica sem PDF entregue. O texto, o nome do paciente e o CRO nunca
 * vão para log.
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('DENTIST_ADMIN')")
public class DocumentPdfService {

	/** PDF pronto e o nome sugerido do arquivo ({@code atestado-12.pdf}). */
	public record PdfFile(byte[] bytes, String filename) {

		@Override
		public String toString() {
			return "PdfFile[" + filename + "]";
		}
	}

	private final ClinicalDocumentRepository documents;
	private final DocumentDownloadRecorder recorder;
	private final DocumentCipher cipher;
	private final DocumentPdfRenderer renderer;
	private final ClinicIdentity clinic;
	private final ProfessionalIdentity professional;
	private final PatientLookup patients;
	private final ClinicZones zones;
	private final ApplicationEventPublisher events;

	/**
	 * @throws BusinessException {@code not-found} (404); {@code conflict} (409) se anonimizado; {@code business-rule} (422,
	 *         {@code PDF_TOO_LARGE}) se o PDF passar de 4 MB
	 * @throws crm_data.shared.error.IntegrityException se o selo não confere
	 */
	@Transactional
	public PdfFile pdf(UUID id) {
		ClinicalDocument doc = documents.findOwnById(id)
				.orElseThrow(() -> new BusinessException(ProblemTypes.NOT_FOUND, "Documento não encontrado."));
		if (doc.getAnonymizedAt() != null) {
			throw new BusinessException(ProblemTypes.CONFLICT, ConflictMessages.DOCUMENT_ANONYMIZED);
		}
		DocumentIntegrity.verify(doc);
		recorder.record(id);

		String content = cipher.decrypt(doc.getContent());
		ClinicIdentity.ClinicInfo clinicInfo = clinic.current();
		ProfessionalIdentity.Identity dentist = professional.of(doc.getCreatedBy())
				.orElse(new ProfessionalIdentity.Identity("", null, null));
		PatientBrief patient = patients.briefs(List.of(doc.getPatientId())).get(doc.getPatientId());
		LocalDate voidedOn = doc.getVoidedAt() == null ? null : doc.getVoidedAt().atZone(zones.zone()).toLocalDate();

		DocumentPdfModel model = new DocumentPdfModel(clinicInfo.name(), clinicInfo.address(), clinicInfo.phone(), doc.getType(),
				doc.getNumber(), doc.getIssuedOn(), patient == null ? "" : patient.name(),
				doc.getRestDays() == null ? null : doc.getRestDays().intValue(), doc.getTargetSpecialty(),
				content.isEmpty() ? null : content, voidedOn, dentist.name(), dentist.cro(), dentist.croState());
		byte[] bytes = renderer.render(model);
		if (bytes.length > DocumentRules.MAX_PDF_BYTES) {
			throw new BusinessException(ProblemTypes.BUSINESS_RULE, "O PDF gerado excede o tamanho máximo permitido.",
					"PDF_TOO_LARGE");
		}
		events.publishEvent(new DocumentEvents.Downloaded(TenantContext.clinicId(), id, TenantContext.userId()));
		return new PdfFile(bytes, filename(doc));
	}

	private static String filename(ClinicalDocument doc) {
		String prefix = switch (doc.getType()) {
			case CERTIFICATE -> "atestado";
			case PRESCRIPTION -> "receita";
			case REFERRAL -> "encaminhamento";
		};
		return prefix.toLowerCase(Locale.ROOT) + "-" + doc.getNumber() + ".pdf";
	}
}
