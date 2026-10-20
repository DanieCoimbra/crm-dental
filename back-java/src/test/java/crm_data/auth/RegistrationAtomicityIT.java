package crm_data.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import crm_data.auth.service.RefreshTokenService;
import crm_data.support.DatabaseContainer;

/**
 * Spec da F04 §5: "Falha ao gravar o refresh após criar a clínica → transação única: nada é persistido".
 * Força a falha exatamente no passo que vem DEPOIS da inserção da clínica e do dentista.
 */
@SpringBootTest(properties = "crm.auth.bcrypt-strength=4")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegistrationAtomicityIT {

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		DatabaseContainer.register(registry);
	}

	@Autowired
	MockMvc mvc;
	@MockitoSpyBean
	RefreshTokenService refreshTokens;

	@Test
	void failureWhileIssuingTheRefreshTokenRollsBackTheClinicAndTheDentist() throws Exception {
		// o stub vai direto no alvo (o spy): a chamada pelo proxy exigiria uma transação (propagation MANDATORY)
		doThrow(new IllegalStateException("falha simulada ao gravar o refresh")).when(AopTestUtils.<RefreshTokenService>getUltimateTargetObject(refreshTokens))
				.issue(any(), any());
		String clinicName = "Atomicidade " + UUID.randomUUID();
		String email = "atomico-" + UUID.randomUUID() + "@teste.local";

		MockHttpServletResponse response = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"clinicName\":\"%s\",\"name\":\"Dra. Ana\",\"email\":\"%s\",\"password\":\"SenhaForte2026\"}"
						.formatted(clinicName, email))
				.with(request -> {
					request.setRemoteAddr("10.77.0.1");
					return request;
				})).andReturn().getResponse();

		assertThat(response.getStatus()).isEqualTo(500);
		assertThat(response.getContentAsString()).doesNotContain("falha simulada");
		var admin = DatabaseContainer.admin();
		assertThat(admin.queryForObject("SELECT count(*) FROM core.clinics WHERE name = ?", Long.class, clinicName))
				.as("a clínica inserida antes da falha foi desfeita").isZero();
		assertThat(admin.queryForObject("SELECT count(*) FROM core.users WHERE email = ?", Long.class, email))
				.as("o dentista inserido antes da falha foi desfeito").isZero();
	}
}
