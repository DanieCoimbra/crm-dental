package crm_data.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import crm_data.auth.service.ProfessionalIdentity;
import crm_data.clinic.service.ClinicIdentity;
import crm_data.clinic.service.ClinicZones;
import crm_data.document.domain.ClinicalDocument;
import crm_data.document.domain.DocumentType;
import crm_data.document.repository.ClinicalDocumentRepository;
import crm_data.patient.service.PatientBrief;
import crm_data.patient.service.PatientLookup;
import crm_data.shared.crypto.AesGcmCipher;
import crm_data.shared.crypto.KeyRing;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.IntegrityException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;

class DocumentPdfServiceTest {

	private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

	private final ClinicalDocumentRepository documents = mock(ClinicalDocumentRepository.class);
	private final DocumentDownloadRecorder recorder = mock(DocumentDownloadRecorder.class);
	private final DocumentCipher cipher = new DocumentCipher(new AesGcmCipher(KeyRing.parse("1:" + KEY, "1")));
	private final DocumentPdfRenderer renderer = mock(DocumentPdfRenderer.class);
	private final ClinicIdentity clinic = mock(ClinicIdentity.class);
	private final ProfessionalIdentity professional = mock(ProfessionalIdentity.class);
	private final PatientLookup patients = mock(PatientLookup.class);
	private final ClinicZones zones = mock(ClinicZones.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
	private final DocumentPdfService service = new DocumentPdfService(documents, recorder, cipher, renderer, clinic, professional,
			patients, zones, events);

	private final UUID clinicId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();
	private final UUID patientId = UUID.randomUUID();
	private ClinicalDocument doc;

	@BeforeEach
	void setUp() {
		TenantContext.set(clinicId, userId, Role.DENTIST_ADMIN);
		LocalDate day = LocalDate.of(2026, 10, 27);
		String cipherText = cipher.encrypt("Dipirona 500 mg");
		doc = new ClinicalDocument(UUID.randomUUID(), patientId, null, DocumentType.PRESCRIPTION, day, null, null, cipherText,
				DocumentIntegrity.compute(DocumentType.PRESCRIPTION, patientId, day, null, null, cipherText), userId);
		ReflectionTestUtils.setField(doc, "number", 7);
		when(documents.findOwnById(doc.getId())).thenReturn(Optional.of(doc));
		when(clinic.current()).thenReturn(new ClinicIdentity.ClinicInfo("Clínica", null, null));
		when(professional.of(userId)).thenReturn(Optional.of(new ProfessionalIdentity.Identity("Dra. Ana", "123", "SP")));
		when(patients.briefs(List.of(patientId))).thenReturn(Map.of(patientId, new PatientBrief(patientId, "Maria", "11")));
		when(zones.zone()).thenReturn(ZoneId.of("America/Sao_Paulo"));
		when(renderer.render(any())).thenReturn("%PDF-1.4 teste".getBytes());
	}

	@AfterEach
	void cleanUp() {
		TenantContext.clear();
	}

	private static void assertRule(Runnable action, ProblemTypes expected, String reason) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, ex -> {
			assertThat(ex.type()).isEqualTo(expected);
			assertThat(ex.reason()).isEqualTo(reason);
		});
	}

	@Test
	void recordsTheDownloadBeforeRenderingAndReturnsTheFile() {
		DocumentPdfService.PdfFile file = service.pdf(doc.getId());

		InOrder order = inOrder(recorder, renderer, events);
		order.verify(recorder).record(doc.getId());
		order.verify(renderer).render(any());
		order.verify(events).publishEvent(any(DocumentEvents.Downloaded.class));
		assertThat(file.filename()).isEqualTo("receita-7.pdf");
		assertThat(new String(file.bytes())).startsWith("%PDF");
	}

	@Test
	void tooLargePdfIs422AndPublishesNoEvent() {
		when(renderer.render(any())).thenReturn(new byte[DocumentRules.MAX_PDF_BYTES + 1]);

		assertRule(() -> service.pdf(doc.getId()), ProblemTypes.BUSINESS_RULE, "PDF_TOO_LARGE");
		verify(events, never()).publishEvent(any(Object.class));
	}

	@Test
	void anonymizedDocumentIs409WithoutRecordingTheDownload() {
		ReflectionTestUtils.setField(doc, "anonymizedAt", Instant.now());

		assertRule(() -> service.pdf(doc.getId()), ProblemTypes.CONFLICT, null);
		verify(recorder, never()).record(any());
	}

	@Test
	void tamperedDocumentFailsBeforeRecordingTheDownload() {
		ReflectionTestUtils.setField(doc, "sha256", "0".repeat(64));

		assertThatThrownBy(() -> service.pdf(doc.getId())).isInstanceOf(IntegrityException.class);
		verify(recorder, never()).record(any());
	}

	@Test
	void unknownDocumentIs404() {
		assertThatThrownBy(() -> service.pdf(UUID.randomUUID())).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.NOT_FOUND));
	}

	@Test
	void rendererFailurePropagatesSoTheTransactionRollsBack() {
		when(renderer.render(any())).thenThrow(new IllegalStateException("falha"));

		assertThatThrownBy(() -> service.pdf(doc.getId())).isInstanceOf(IllegalStateException.class);
		verify(events, never()).publishEvent(any(Object.class));
	}

	@Test
	void modelCarriesTheDecryptedTextAndTheDentistOfTheDocument() {
		service.pdf(doc.getId());

		org.mockito.ArgumentCaptor<DocumentPdfModel> captor = org.mockito.ArgumentCaptor.forClass(DocumentPdfModel.class);
		verify(renderer).render(captor.capture());
		DocumentPdfModel model = captor.getValue();
		assertThat(model.content()).isEqualTo("Dipirona 500 mg");
		assertThat(model.dentistName()).isEqualTo("Dra. Ana");
		assertThat(model.dentistCro()).isEqualTo("123");
		assertThat(model.patientName()).isEqualTo("Maria");
		assertThat(model.number()).isEqualTo(7);
		assertThat(model.voidedOn()).isNull();
	}
}
