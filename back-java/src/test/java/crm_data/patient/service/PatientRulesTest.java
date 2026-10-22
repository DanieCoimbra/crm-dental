package crm_data.patient.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PatientRulesTest {

	@ParameterizedTest
	@ValueSource(strings = { "52998224725", "11144477735", "39053344705", "00000000191" })
	void acceptsCpfsWithCorrectCheckDigits(String cpf) {
		assertThat(PatientRules.isValidCpf(cpf)).isTrue();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "52998224724", "52998224715", "11111111111", "00000000000", "99999999999", "5299822472",
			"529982247255", "5299822472a", "529.982.247-25" })
	void rejectsInvalidCpfs(String cpf) {
		assertThat(PatientRules.isValidCpf(cpf)).isFalse();
	}

	@Test
	void normalizesCpfMaskOnly() {
		assertThat(PatientRules.normalizeCpf("529.982.247-25")).isEqualTo("52998224725");
		assertThat(PatientRules.normalizeCpf(" 529 982 247 25 ")).isEqualTo("52998224725");
		assertThat(PatientRules.normalizeCpf("529.982.247-2x")).isEqualTo("5299822472x");
		assertThat(PatientRules.normalizeCpf(null)).isNull();
	}

	@Test
	void normalizesPhoneMask() {
		assertThat(PatientRules.normalizePhone("(11) 98765-4321")).isEqualTo("11987654321");
		assertThat(PatientRules.normalizePhone("+55 11 98765-4321")).isEqualTo("5511987654321");
		assertThat(PatientRules.normalizePhone(null)).isNull();
	}

	@Test
	void birthDateMustBeBetween1900AndToday() {
		LocalDate today = LocalDate.of(2026, 10, 22);

		assertThat(PatientRules.isValidBirthDate(LocalDate.of(1900, 1, 1), today)).isTrue();
		assertThat(PatientRules.isValidBirthDate(today, today)).isTrue();
		assertThat(PatientRules.isValidBirthDate(LocalDate.of(1899, 12, 31), today)).isFalse();
		assertThat(PatientRules.isValidBirthDate(today.plusDays(1), today)).isFalse();
		assertThat(PatientRules.isValidBirthDate(null, today)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "529.982.247-25", "52998224725", "529 982 247 25" })
	void recognizesCpfQueries(String q) {
		assertThat(PatientRules.looksLikeCpfQuery(q)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "maria", "5299822472", "529982247255", "maria 52998224725", "123" })
	void treatsEverythingElseAsNameFragment(String q) {
		assertThat(PatientRules.looksLikeCpfQuery(q)).isFalse();
	}
}
