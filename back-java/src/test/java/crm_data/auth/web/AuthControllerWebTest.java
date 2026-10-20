package crm_data.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import crm_data.auth.service.AuthSession;
import crm_data.auth.service.AuthSession.UserSummary;
import crm_data.auth.service.LoginRateLimiter;
import crm_data.auth.service.LoginService;
import crm_data.auth.service.RefreshTokenService;
import crm_data.auth.service.RegistrationService;
import crm_data.shared.config.CorsConfig;
import crm_data.shared.config.JwtConfig;
import crm_data.shared.config.ProblemResponseWriter;
import crm_data.shared.config.SecurityConfig;
import crm_data.shared.config.SecurityHeadersConfig;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.error.RateLimitedException;
import crm_data.shared.security.Role;
import crm_data.shared.web.RequestIdFilter;

/** Contrato HTTP das rotas de autenticação (DTOs, validação, normalização, erros e limite por IP). */
@WebMvcTest(AuthController.class)
@Import({ SecurityConfig.class, CorsConfig.class, SecurityHeadersConfig.class, ProblemResponseWriter.class,
		RequestIdFilter.class, JwtConfig.class, LoginRateLimiter.class })
@TestPropertySource(properties = { "crm.cors-origins=https://app.test.local",
		"crm.jwt.secret=segredo-somente-de-teste-com-mais-de-32-bytes" })
class AuthControllerWebTest {

	private static final String VALID_REFRESH = "A".repeat(43);

	@Autowired
	MockMvc mvc;
	@MockitoBean
	RegistrationService registration;
	@MockitoBean
	LoginService login;
	@MockitoBean
	RefreshTokenService refreshTokens;

	private final UUID clinicId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();
	private final AuthSession session = new AuthSession("access.jwt.value", "refresh-token-value", "Bearer", 900,
			new UserSummary(userId, "Dra. Ana", "ana@clinica.com", Role.DENTIST_ADMIN, clinicId));

	@BeforeEach
	void stubServices() {
		when(registration.register(anyString(), anyString(), anyString(), anyString())).thenReturn(session);
		when(login.login(anyString(), anyString(), anyString())).thenReturn(session);
		when(refreshTokens.rotate(anyString())).thenReturn(session);
	}

	private static MockHttpServletRequestBuilder json(String path, String body) {
		return post(path).contentType(MediaType.APPLICATION_JSON).content(body)
				.with(request -> {
					request.setRemoteAddr("198.51.100." + (Math.abs(path.hashCode()) % 200 + 1));
					return request;
				});
	}

	private static String register(String clinicName, String name, String email, String password) {
		return "{\"clinicName\":\"%s\",\"name\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}".formatted(clinicName, name,
				email, password);
	}

	// ---- register ----

	@Test
	void registerReturns201WithTheContractedBodyAndNoPlanOrTrial() throws Exception {
		mvc.perform(json("/api/v1/auth/register", register("Clínica Sorriso", "Dra. Ana", "ana@clinica.com", "senhaSegura123")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.accessToken").value("access.jwt.value"))
				.andExpect(jsonPath("$.refreshToken").value("refresh-token-value"))
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").value(900))
				.andExpect(jsonPath("$.user.id").value(userId.toString()))
				.andExpect(jsonPath("$.user.name").value("Dra. Ana"))
				.andExpect(jsonPath("$.user.email").value("ana@clinica.com"))
				.andExpect(jsonPath("$.user.role").value("DENTIST_ADMIN"))
				.andExpect(jsonPath("$.user.clinicId").value(clinicId.toString()))
				.andExpect(content().string(not(containsString("trial"))))
				.andExpect(content().string(not(containsString("plan"))))
				.andExpect(content().string(not(containsString("password"))))
				.andExpect(header().string("Cache-Control", containsString("no-store")));
	}

	@Test
	void registerNormalizesTextAndLowercasesTheEmailBeforeCallingTheService() throws Exception {
		mvc.perform(json("/api/v1/auth/register", register("  Clínica Sorriso  ", "  Dra. Ana ", "  ANA@Clinica.COM ", "senhaSegura123")))
				.andExpect(status().isCreated());

		verify(registration).register("Clínica Sorriso", "Dra. Ana", "ana@clinica.com", "senhaSegura123");
	}

	@Test
	void registerIgnoresClinicIdRoleAndIdSentByTheClient() throws Exception {
		String body = """
				{"clinicName":"Clínica Sorriso","name":"Dra. Ana","email":"ana@clinica.com","password":"senhaSegura123",
				 "clinicId":"%s","role":"RECEPTIONIST","id":"%s","active":false,"plan":"premium"}""".formatted(UUID.randomUUID(), UUID.randomUUID());

		mvc.perform(json("/api/v1/auth/register", body)).andExpect(status().isCreated());

		verify(registration).register("Clínica Sorriso", "Dra. Ana", "ana@clinica.com", "senhaSegura123");
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"Clínica|Ana|ana@clinica.com|curta1|password",
			"Clínica|Ana|ana@clinica.com|somenteletrasaqui|password",
			"Clínica|Ana|ana@clinica.com|1234567890123|password",
			"Clínica|A|ana@clinica.com|senhaSegura123|name",
			"X|Ana|ana@clinica.com|senhaSegura123|clinicName",
			"Clínica|Ana|nao-e-email|senhaSegura123|email" })
	void registerRejectsInvalidFieldsWithTheFieldInErrors(String clinic, String name, String email, String password,
			String expectedField) throws Exception {
		mvc.perform(json("/api/v1/auth/register", register(clinic, name, email, password)))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/validation"))
				.andExpect(jsonPath("$.errors[?(@.field=='" + expectedField + "')]").isNotEmpty());

		verifyNoInteractions(registration);
	}

	@Test
	void registerRejectsPasswordsAbove64CharactersAndAbove72Bytes() throws Exception {
		mvc.perform(json("/api/v1/auth/register", register("Clínica", "Ana", "ana@clinica.com", "a1".repeat(33))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field=='password')]").isNotEmpty());

		// 30 emojis = 30 caracteres lógicos (60 chars UTF-16, 120 bytes) + letra e número: passa em tamanho mas excede 72 bytes
		String multiByte = "😀".repeat(30) + "a1";
		mvc.perform(json("/api/v1/auth/register", register("Clínica", "Ana", "ana@clinica.com", multiByte)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field=='password')]").isNotEmpty());
		verifyNoInteractions(registration);
	}

	@Test
	void registerReportsAllMissingFieldsAtOnce() throws Exception {
		mvc.perform(json("/api/v1/auth/register", "{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.length()").value(4));
	}

	@Test
	void duplicateEmailFromTheServiceBecomes409Conflict() throws Exception {
		when(registration.register(anyString(), anyString(), anyString(), anyString()))
				.thenThrow(new BusinessException(ProblemTypes.CONFLICT, "E-mail já cadastrado."));

		mvc.perform(json("/api/v1/auth/register", register("Clínica", "Ana", "ana@clinica.com", "senhaSegura123")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/conflict"));
	}

	@Test
	void malformedJsonIs400() throws Exception {
		mvc.perform(json("/api/v1/auth/register", "{nope")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/validation"));
	}

	// ---- login ----

	@Test
	void loginReturns200AndPassesTheNormalizedEmailAndClientIp() throws Exception {
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"  ANA@Clinica.com \",\"password\":\"qualquer\"}")
				.with(request -> {
					request.setRemoteAddr("203.0.113.9");
					return request;
				}))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").value(900));

		verify(login).login("ana@clinica.com", "qualquer", "203.0.113.9");
	}

	@Test
	void loginValidatesPresenceAndSizeButNotPasswordStrength() throws Exception {
		mvc.perform(json("/api/v1/auth/login", "{\"email\":\"ana@clinica.com\"}")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field=='password')]").isNotEmpty());
		mvc.perform(json("/api/v1/auth/login", "{\"email\":\"ana@clinica.com\",\"password\":\"" + "x".repeat(257) + "\"}"))
				.andExpect(status().isBadRequest());
		mvc.perform(json("/api/v1/auth/login", "{\"email\":\"ana@clinica.com\",\"password\":\"fraca\"}"))
				.andExpect(status().isOk()); // regra de senha vale só no cadastro
	}

	@Test
	void loginRateLimitFromTheServiceIs429WithRetryAfter() throws Exception {
		when(login.login(anyString(), anyString(), anyString())).thenThrow(new RateLimitedException("Muitas tentativas.", 42));

		mvc.perform(json("/api/v1/auth/login", "{\"email\":\"ana@clinica.com\",\"password\":\"x\"}"))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "42"))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/rate-limited"));
	}

	// ---- refresh / logout ----

	@Test
	void refreshReturnsTheNewPair() throws Exception {
		mvc.perform(json("/api/v1/auth/refresh", "{\"refreshToken\":\"" + VALID_REFRESH + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.refreshToken").value("refresh-token-value"));

		verify(refreshTokens).rotate(VALID_REFRESH);
	}

	@Test
	void refreshAndLogoutRejectTokensOutsideTheBase64UrlFormat() throws Exception {
		for (String path : new String[] { "/api/v1/auth/refresh", "/api/v1/auth/logout" }) {
			for (String token : new String[] { "curto", "A".repeat(44), "A".repeat(42) + "+", "" }) {
				mvc.perform(json(path, "{\"refreshToken\":\"" + token + "\"}")).andExpect(status().isBadRequest());
			}
			mvc.perform(json(path, "{}")).andExpect(status().isBadRequest());
		}
		verifyNoInteractions(refreshTokens);
	}

	@Test
	void logoutReturns204WithoutBody() throws Exception {
		mvc.perform(json("/api/v1/auth/logout", "{\"refreshToken\":\"" + VALID_REFRESH + "\"}"))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		verify(refreshTokens).revoke(VALID_REFRESH);
	}

	@Test
	void unauthorizedFromTheServiceIs401ProblemJson() throws Exception {
		when(refreshTokens.rotate(anyString())).thenThrow(new BusinessException(ProblemTypes.UNAUTHORIZED, "Token inválido ou expirado."));

		mvc.perform(json("/api/v1/auth/refresh", "{\"refreshToken\":\"" + VALID_REFRESH + "\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"));
	}

	// ---- limite por IP (interceptor) ----

	@Test
	void the21stRequestFromTheSameIpInAMinuteIs429EvenWhenTheBodyIsInvalid() throws Exception {
		String ip = "192.0.2.77";
		for (int i = 1; i <= 20; i++) {
			mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}")
					.with(request -> {
						request.setRemoteAddr(ip);
						return request;
					})).andExpect(status().isBadRequest()); // inválidas também contam
		}

		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"ana@clinica.com\",\"password\":\"x\"}")
				.with(request -> {
					request.setRemoteAddr(ip);
					return request;
				}))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/rate-limited"));
		assertThat(ip).isNotBlank();
	}

	@Test
	void ipLimitsAreIndependentPerActionAndPerIp() throws Exception {
		for (int i = 0; i < 20; i++) {
			mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("{}")
					.with(request -> {
						request.setRemoteAddr("192.0.2.88");
						return request;
					})).andExpect(status().isBadRequest());
		}
		// mesmo IP, outra ação: ainda liberada
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}")
				.with(request -> {
					request.setRemoteAddr("192.0.2.88");
					return request;
				})).andExpect(status().isBadRequest());
		// outro IP, mesma ação: liberado
		mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("{}")
				.with(request -> {
					request.setRemoteAddr("192.0.2.89");
					return request;
				})).andExpect(status().isBadRequest());
		verify(login, org.mockito.Mockito.never()).login(any(), any(), any());
	}
}
