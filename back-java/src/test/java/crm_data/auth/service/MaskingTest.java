package crm_data.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaskingTest {

	@Test
	void keepsOnlyTheFirstCharacterAndTheDomain() {
		assertThat(Masking.email("joao@clinica.com")).isEqualTo("j***@clinica.com");
		assertThat(Masking.email("a@b.co")).isEqualTo("a***@b.co");
	}

	@Test
	void neverEchoesWhatIsNotAnEmail() {
		assertThat(Masking.email("sem-arroba")).isEqualTo("***");
		assertThat(Masking.email("@dominio.com")).isEqualTo("***");
		assertThat(Masking.email(null)).isEqualTo("?");
		assertThat(Masking.email("  ")).isEqualTo("?");
	}
}
