package crm_data.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import crm_data.document.domain.ClinicalDocument;
import crm_data.document.domain.DocumentType;
import crm_data.shared.error.IntegrityException;

class DocumentIntegrityTest {

	private static final UUID PATIENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final LocalDate DAY = LocalDate.of(2026, 10, 27);

	private static String seal(DocumentType type, UUID patient, LocalDate day, Short days, String specialty, String cipher) {
		return DocumentIntegrity.compute(type, patient, day, days, specialty, cipher);
	}

	private ClinicalDocument sealed(String cipher) {
		String seal = seal(DocumentType.CERTIFICATE, PATIENT, DAY, (short) 2, null, cipher);
		return new ClinicalDocument(UUID.randomUUID(), PATIENT, null, DocumentType.CERTIFICATE, DAY, (short) 2, null, cipher, seal,
				UUID.randomUUID());
	}

	@Test
	void sealIsLowercaseHexOf64CharactersAndDeterministic() {
		String first = seal(DocumentType.CERTIFICATE, PATIENT, DAY, (short) 2, null, "QUJD");

		assertThat(first).matches("^[0-9a-f]{64}$");
		assertThat(seal(DocumentType.CERTIFICATE, PATIENT, DAY, (short) 2, null, "QUJD")).isEqualTo(first);
	}

	@Test
	void everyFieldChangesTheSeal() {
		String base = seal(DocumentType.CERTIFICATE, PATIENT, DAY, (short) 2, "Endo", "QUJD");

		assertThat(seal(DocumentType.REFERRAL, PATIENT, DAY, (short) 2, "Endo", "QUJD")).isNotEqualTo(base);
		assertThat(seal(DocumentType.CERTIFICATE, UUID.randomUUID(), DAY, (short) 2, "Endo", "QUJD")).isNotEqualTo(base);
		assertThat(seal(DocumentType.CERTIFICATE, PATIENT, DAY.plusDays(1), (short) 2, "Endo", "QUJD")).isNotEqualTo(base);
		assertThat(seal(DocumentType.CERTIFICATE, PATIENT, DAY, (short) 3, "Endo", "QUJD")).isNotEqualTo(base);
		assertThat(seal(DocumentType.CERTIFICATE, PATIENT, DAY, (short) 2, "Orto", "QUJD")).isNotEqualTo(base);
		assertThat(seal(DocumentType.CERTIFICATE, PATIENT, DAY, (short) 2, "Endo", "QUJE")).isNotEqualTo(base);
	}

	@Test
	void nullFieldsCountAsEmpty() {
		assertThat(seal(DocumentType.PRESCRIPTION, PATIENT, DAY, null, null, "QUJD"))
				.isEqualTo(seal(DocumentType.PRESCRIPTION, PATIENT, DAY, null, null, "QUJD"));
	}

	@Test
	void verifyAcceptsAnUntouchedDocument() {
		assertThatCode(() -> DocumentIntegrity.verify(sealed("QUJDRA=="))).doesNotThrowAnyException();
	}

	@Test
	void verifyRejectsTamperedContentOrSealWithoutLeakingText() {
		ClinicalDocument tamperedContent = sealed("QUJDRA==");
		ReflectionTestUtils.setField(tamperedContent, "content", "QUJERA==");
		ClinicalDocument tamperedSeal = sealed("QUJDRA==");
		ReflectionTestUtils.setField(tamperedSeal, "sha256", "0".repeat(64));
		ClinicalDocument tamperedDays = sealed("QUJDRA==");
		ReflectionTestUtils.setField(tamperedDays, "restDays", (short) 30);

		for (ClinicalDocument doc : new ClinicalDocument[] { tamperedContent, tamperedSeal, tamperedDays }) {
			assertThatThrownBy(() -> DocumentIntegrity.verify(doc)).isInstanceOfSatisfying(IntegrityException.class,
					ex -> assertThat(ex.getMessage()).isEqualTo("documento id=" + doc.getId()));
		}
	}

	@Test
	void anonymizedDocumentHasNothingToVerify() {
		ClinicalDocument doc = sealed("QUJDRA==");
		ReflectionTestUtils.setField(doc, "content", null);
		ReflectionTestUtils.setField(doc, "anonymizedAt", Instant.now());

		assertThatCode(() -> DocumentIntegrity.verify(doc)).doesNotThrowAnyException();
	}

	@Test
	void missingStoredSealIsAnIntegrityFailure() {
		ClinicalDocument doc = sealed("QUJDRA==");
		ReflectionTestUtils.setField(doc, "sha256", null);

		assertThatThrownBy(() -> DocumentIntegrity.verify(doc)).isInstanceOf(IntegrityException.class);
	}
}
