package crm_data.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import crm_data.document.domain.DocumentType;
import crm_data.shared.error.ProblemDetails.FieldError;

class DocumentRulesTest {

	private static List<String> fields(DocumentType type, Integer restDays, String specialty, String content) {
		return DocumentRules.validate(type, restDays, specialty, content).stream().map(FieldError::field).toList();
	}

	@Test
	void certificateNeedsRestDaysAndAcceptsOptionalText() {
		assertThat(fields(DocumentType.CERTIFICATE, 2, null, null)).isEmpty();
		assertThat(fields(DocumentType.CERTIFICATE, 2, null, "Repouso em casa.")).isEmpty();
		assertThat(fields(DocumentType.CERTIFICATE, null, null, null)).containsExactly("restDays");
		assertThat(fields(DocumentType.CERTIFICATE, 2, "Ortodontia", null)).containsExactly("targetSpecialty");
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, -1, 91, 1000 })
	void restDaysOutsideOneToNinetyAreRejected(int days) {
		assertThat(fields(DocumentType.CERTIFICATE, days, null, null)).containsExactly("restDays");
	}

	@ParameterizedTest
	@ValueSource(ints = { 1, 45, 90 })
	void restDaysInsideTheRangeAreAccepted(int days) {
		assertThat(fields(DocumentType.CERTIFICATE, days, null, null)).isEmpty();
	}

	@Test
	void prescriptionNeedsTextAndForbidsTheStructuredFields() {
		assertThat(fields(DocumentType.PRESCRIPTION, null, null, "Amoxicilina 500 mg")).isEmpty();
		assertThat(fields(DocumentType.PRESCRIPTION, null, null, null)).containsExactly("content");
		assertThat(fields(DocumentType.PRESCRIPTION, 3, "Endodontia", "x")).containsExactlyInAnyOrder("restDays", "targetSpecialty");
	}

	@Test
	void referralNeedsSpecialtyAndText() {
		assertThat(fields(DocumentType.REFERRAL, null, "Endodontia", "Dente 36")).isEmpty();
		assertThat(fields(DocumentType.REFERRAL, null, null, null)).containsExactlyInAnyOrder("targetSpecialty", "content");
		assertThat(fields(DocumentType.REFERRAL, null, "E", "x")).containsExactly("targetSpecialty");
		assertThat(fields(DocumentType.REFERRAL, null, "x".repeat(61), "x")).containsExactly("targetSpecialty");
		assertThat(fields(DocumentType.REFERRAL, 2, "Endodontia", "x")).containsExactly("restDays");
	}

	@Test
	void textLimitIs4000CharactersAnd16000Bytes() {
		assertThat(fields(DocumentType.PRESCRIPTION, null, null, "a".repeat(4000))).isEmpty();
		assertThat(fields(DocumentType.PRESCRIPTION, null, null, "a".repeat(4001))).containsExactly("content");
		// 4000 caracteres de 4 bytes (emoji) = 16000 caracteres UTF-16: passa o limite de bytes antes do de caracteres
		assertThat(fields(DocumentType.PRESCRIPTION, null, null, "é".repeat(4000))).isEmpty(); // 8000 bytes
		assertThat(fields(DocumentType.PRESCRIPTION, null, null, "😀".repeat(2001))).containsExactly("content"); // 4002 chars
	}

	@Test
	void errorMessagesNeverEchoTheSubmittedValue() {
		String secret = "Segredo clinico do paciente";
		String messages = DocumentRules.validate(DocumentType.CERTIFICATE, 500, secret, secret).toString();

		assertThat(messages).doesNotContain(secret);
	}
}
