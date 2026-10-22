package crm_data.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import crm_data.auth.service.AuthEvents.ClinicRegistered;
import crm_data.auth.service.AuthEvents.RefreshReuseDetected;
import crm_data.support.DatabaseContainer;

/**
 * Fluxos de autenticação ponta a ponta, contra o PostgreSQL real (scripts de ../database, papel crm_app, RLS e
 * funções de bootstrap) com tokens JWT de verdade. BCrypt com custo 4 só para acelerar o teste.
 */
@SpringBootTest(properties = "crm.auth.bcrypt-strength=4")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@RecordApplicationEvents
@ExtendWith(OutputCaptureExtension.class)
class AuthFlowIT {

	private static final String PASSWORD = "SenhaForte2026";
	private static final AtomicInteger IP_COUNTER = new AtomicInteger();

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		DatabaseContainer.register(registry);
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	JwtDecoder jwtDecoder;
	@Autowired
	JwtEncoder jwtEncoder;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	ApplicationEvents events;
	@Autowired
	crm_data.auth.service.HashingGate hashingGate;

	final org.springframework.jdbc.core.JdbcTemplate admin = DatabaseContainer.admin();

	// ---- Auxiliares ----

	/** Um IP novo por chamada: o limite de 20/min por IP não interfere nos cenários. */
	private static String freshIp() {
		int n = IP_COUNTER.incrementAndGet();
		return "10.1." + (n / 250) + "." + (n % 250 + 1);
	}

	private static MockHttpServletRequestBuilder json(String path, String body, String ip) {
		return post(path).contentType(MediaType.APPLICATION_JSON).content(body).with(request -> {
			request.setRemoteAddr(ip);
			return request;
		});
	}

	private MockHttpServletResponse call(String path, String body, String ip) throws Exception {
		return mvc.perform(json(path, body, ip)).andReturn().getResponse();
	}

	private static String uniqueEmail() {
		return "usuario-" + UUID.randomUUID() + "@teste.local";
	}

	private static String registerBody(String clinicName, String email, String password) {
		return "{\"clinicName\":\"%s\",\"name\":\"Dra. Ana Souza\",\"email\":\"%s\",\"password\":\"%s\"}".formatted(clinicName, email,
				password);
	}

	private DocumentContext register(String email) throws Exception {
		MockHttpServletResponse response = call("/api/v1/auth/register", registerBody("Clínica " + UUID.randomUUID(), email, PASSWORD),
				freshIp());
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
		return JsonPath.parse(response.getContentAsString(StandardCharsets.UTF_8));
	}

	private MockHttpServletResponse login(String email, String password, String ip) throws Exception {
		return call("/api/v1/auth/login", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password), ip);
	}

	private MockHttpServletResponse refresh(String token) throws Exception {
		return call("/api/v1/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(token), freshIp());
	}

	private int status(String path, String bearer) throws Exception {
		MockHttpServletRequestBuilder request = get(path);
		if (bearer != null) {
			request.header("Authorization", "Bearer " + bearer);
		}
		return mvc.perform(request).andReturn().getResponse().getStatus();
	}

	private static DocumentContext body(MockHttpServletResponse response) throws Exception {
		return JsonPath.parse(response.getContentAsString(StandardCharsets.UTF_8));
	}

	private static String sha256Hex(String value) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
	}

	/** Cria um recepcionista na clínica do dentista (a gestão de usuários é da F05; aqui é só massa de teste). */
	private String createReceptionist(UUID clinicId, boolean active) {
		String email = uniqueEmail();
		admin.update("""
				INSERT INTO core.users (id, clinic_id, name, email, password_hash, role, active)
				VALUES (?, ?, 'Recepção', ?, ?, 'RECEPTIONIST', ?)""",
				UUID.randomUUID(), clinicId, email, passwordEncoder.encode(PASSWORD), active);
		return email;
	}

	private String signedToken(String issuer, Instant issuedAt, Instant expiresAt, UUID userId, UUID clinicId, String role) {
		JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject(userId.toString())
				.claim("clinic_id", clinicId.toString()).claim("role", role).id(UUID.randomUUID().toString())
				.issuedAt(issuedAt).expiresAt(expiresAt).build();
		return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
	}

	// ---- Registro ----

	@Test
	void registrationCreatesClinicDentistAndSessionAndStoresOnlyHashes() throws Exception {
		String email = uniqueEmail();
		DocumentContext session = register(email);

		UUID userId = UUID.fromString(session.read("$.user.id"));
		UUID clinicId = UUID.fromString(session.read("$.user.clinicId"));
		assertThat(session.<String>read("$.tokenType")).isEqualTo("Bearer");
		assertThat(session.<Integer>read("$.expiresIn")).isEqualTo(900);
		assertThat(session.<String>read("$.user.role")).isEqualTo("DENTIST_ADMIN");
		assertThat(session.<String>read("$.user.email")).isEqualTo(email);

		var user = admin.queryForMap("SELECT * FROM core.users WHERE id = ?", userId);
		assertThat(user).containsEntry("role", "DENTIST_ADMIN").containsEntry("active", true).containsEntry("email", email);
		assertThat(user.get("clinic_id")).isEqualTo(clinicId);
		assertThat((String) user.get("password_hash")).startsWith("$2a$04$").doesNotContain(PASSWORD);
		assertThat(admin.queryForObject("SELECT count(*) FROM core.clinics WHERE id = ?", Long.class, clinicId)).isEqualTo(1L);

		String refreshToken = session.read("$.refreshToken");
		assertThat(refreshToken).hasSize(43).matches("[A-Za-z0-9_-]{43}");
		assertThat(admin.queryForObject("SELECT count(*) FROM core.refresh_tokens WHERE token_hash = ?", Long.class,
				sha256Hex(refreshToken))).isEqualTo(1L);
		assertThat(admin.queryForObject("SELECT count(*) FROM core.refresh_tokens WHERE token_hash = ?", Long.class, refreshToken))
				.as("o token em claro nunca é gravado").isZero();

		Jwt jwt = jwtDecoder.decode(session.read("$.accessToken"));
		assertThat(jwt.getSubject()).isEqualTo(userId.toString());
		assertThat(jwt.getClaimAsString("clinic_id")).isEqualTo(clinicId.toString());
		assertThat(jwt.getClaimAsString("role")).isEqualTo("DENTIST_ADMIN");
		assertThat(jwt.getClaimAsString("iss")).isEqualTo("crm-clinica");
		assertThat(jwt.getExpiresAt().getEpochSecond() - jwt.getIssuedAt().getEpochSecond()).isEqualTo(900);

		assertThat(events.stream(ClinicRegistered.class)).anySatisfy(
				event -> assertThat(event).isEqualTo(new ClinicRegistered(clinicId, userId)));
	}

	@Test
	void duplicateEmailIs409EvenWithDifferentCaseAndSpacesAndLeavesNoOrphanClinic() throws Exception {
		String email = uniqueEmail();
		register(email);
		String secondClinic = "Segunda " + UUID.randomUUID();

		MockHttpServletResponse second = call("/api/v1/auth/register",
				registerBody(secondClinic, "  " + email.toUpperCase() + " ", PASSWORD), freshIp());

		assertThat(second.getStatus()).isEqualTo(409);
		assertThat(body(second).<String>read("$.type")).isEqualTo("https://crm.local/problems/conflict");
		assertThat(admin.queryForObject("SELECT count(*) FROM core.clinics WHERE name = ?", Long.class, secondClinic))
				.as("transação única: a clínica da tentativa que falhou não pode sobrar").isZero();
	}

	@Test
	void clientCannotChooseClinicIdRoleOrIdAndExtraFieldsAreIgnored() throws Exception {
		UUID wantedClinic = UUID.randomUUID();
		UUID wantedId = UUID.randomUUID();
		String email = uniqueEmail();
		String requestBody = """
				{"clinicName":"Clínica Extra","name":"Dra. Ana","email":"%s","password":"%s",
				 "clinicId":"%s","id":"%s","role":"RECEPTIONIST","active":false,"plan":"premium","trialEndsAt":"2030-01-01"}"""
				.formatted(email, PASSWORD, wantedClinic, wantedId);

		MockHttpServletResponse response = call("/api/v1/auth/register", requestBody, freshIp());

		assertThat(response.getStatus()).isEqualTo(201);
		DocumentContext session = body(response);
		assertThat(session.<String>read("$.user.role")).isEqualTo("DENTIST_ADMIN");
		assertThat(session.<String>read("$.user.clinicId")).isNotEqualTo(wantedClinic.toString());
		assertThat(session.<String>read("$.user.id")).isNotEqualTo(wantedId.toString());
		assertThat(admin.queryForObject("SELECT active FROM core.users WHERE email = ?", Boolean.class, email)).isTrue();
	}

	@Test
	void weakOrInvalidInputIs400WithErrorsAndNothingIsPersisted() throws Exception {
		String clinicName = "Fraca " + UUID.randomUUID();

		MockHttpServletResponse response = call("/api/v1/auth/register", registerBody(clinicName, uniqueEmail(), "curta1"), freshIp());

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(body(response).<List<?>>read("$.errors[?(@.field=='password')]")).isNotEmpty();
		assertThat(admin.queryForObject("SELECT count(*) FROM core.clinics WHERE name = ?", Long.class, clinicName)).isZero();
	}

	@Test
	void concurrentRegistrationsWithTheSameEmailYieldExactlyOneWinnerAndNoOrphans() throws Exception {
		String email = uniqueEmail();
		String prefix = "Corrida-" + UUID.randomUUID() + "-";
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				String ip = freshIp();
				String requestBody = registerBody(prefix + i, email, PASSWORD);
				Callable<Integer> task = () -> call("/api/v1/auth/register", requestBody, ip).getStatus();
				results.add(pool.submit(task));
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> result : results) {
				statuses.add(result.get());
			}
			assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
			assertThat(statuses).filteredOn(s -> s == 409).hasSize(7);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(admin.queryForObject("SELECT count(*) FROM core.users WHERE email = ?", Long.class, email)).isEqualTo(1L);
		assertThat(admin.queryForObject("SELECT count(*) FROM core.clinics WHERE name LIKE ?", Long.class, prefix + "%"))
				.as("só a clínica vencedora existe").isEqualTo(1L);
	}

	// ---- Login ----

	@Test
	void loginIssuesAWorkingSessionAndAcceptsAnyEmailCase() throws Exception {
		String email = uniqueEmail();
		DocumentContext registered = register(email);

		MockHttpServletResponse response = login(email.toUpperCase(), PASSWORD, freshIp());

		assertThat(response.getStatus()).isEqualTo(200);
		DocumentContext session = body(response);
		assertThat(session.<String>read("$.user.id")).isEqualTo(registered.read("$.user.id"));
		assertThat(session.<String>read("$.refreshToken")).isNotEqualTo(registered.read("$.refreshToken"));

		MockHttpServletResponse me = mvc.perform(get("/probe/auth/any").header("Authorization", "Bearer " + session.read("$.accessToken")))
				.andReturn().getResponse();
		assertThat(me.getStatus()).isEqualTo(200);
		assertThat(body(me).<String>read("$.userId")).isEqualTo(registered.read("$.user.id"));
		assertThat(body(me).<String>read("$.clinicId")).isEqualTo(registered.read("$.user.clinicId"));
		assertThat(body(me).<String>read("$.role")).isEqualTo("DENTIST_ADMIN");
	}

	@Test
	void unknownEmailWrongPasswordAndInactiveUserGetTheExactSame401() throws Exception {
		DocumentContext dentist = register(uniqueEmail());
		UUID clinicId = UUID.fromString(dentist.read("$.user.clinicId"));
		String inactiveEmail = createReceptionist(clinicId, false);
		String dentistEmail = dentist.read("$.user.email");

		MockHttpServletResponse unknown = login(uniqueEmail(), PASSWORD, freshIp());
		MockHttpServletResponse wrongPassword = login(dentistEmail, "OutraSenha999", freshIp());
		MockHttpServletResponse inactive = login(inactiveEmail, PASSWORD, freshIp());

		for (MockHttpServletResponse response : List.of(unknown, wrongPassword, inactive)) {
			assertThat(response.getStatus()).isEqualTo(401);
			assertThat(response.getContentType()).startsWith("application/problem+json");
		}
		DocumentContext reference = body(unknown);
		for (MockHttpServletResponse other : List.of(wrongPassword, inactive)) {
			DocumentContext json = body(other);
			assertThat(json.<String>read("$.type")).isEqualTo(reference.read("$.type"));
			assertThat(json.<String>read("$.title")).isEqualTo(reference.read("$.title"));
			assertThat(json.<String>read("$.detail")).isEqualTo(reference.read("$.detail"));
			assertThat(json.<Integer>read("$.status")).isEqualTo(401);
		}
	}

	@Test
	void sixthFailureInAMinuteIs429WithRetryAfterEvenIfThePasswordIsRightThen() throws Exception {
		String email = register(uniqueEmail()).read("$.user.email");
		String ip = freshIp();

		for (int i = 1; i <= 5; i++) {
			assertThat(login(email, "Errada" + i + "999", ip).getStatus()).as("falha %d", i).isEqualTo(401);
		}
		MockHttpServletResponse sixth = login(email, "Errada6999", ip);
		MockHttpServletResponse correctPasswordWhileBlocked = login(email, PASSWORD, ip);

		assertThat(sixth.getStatus()).isEqualTo(429);
		assertThat(Long.parseLong(sixth.getHeader("Retry-After"))).isBetween(1L, 60L);
		assertThat(body(sixth).<String>read("$.type")).isEqualTo("https://crm.local/problems/rate-limited");
		assertThat(correctPasswordWhileBlocked.getStatus()).isEqualTo(429);
		assertThat(login(email, PASSWORD, freshIp()).getStatus()).as("outro IP não é afetado").isEqualTo(200);
		assertThat(login(uniqueEmail(), PASSWORD, ip).getStatus()).as("outro e-mail no mesmo IP não é bloqueado").isEqualTo(401);
	}

	@Test
	void twentyFirstFailureInFifteenMinutesAgainstOneEmailIs429EvenFromDifferentIps() throws Exception {
		String email = register(uniqueEmail()).read("$.user.email");

		for (int i = 1; i <= 20; i++) {
			assertThat(login(email, "Errada" + i + "999", freshIp()).getStatus()).as("falha %d (IP novo a cada vez)", i).isEqualTo(401);
		}
		MockHttpServletResponse blocked = login(email, "Errada21999", freshIp());
		MockHttpServletResponse correctPasswordWhileBlocked = login(email, PASSWORD, freshIp());

		assertThat(blocked.getStatus()).isEqualTo(429);
		assertThat(Long.parseLong(blocked.getHeader("Retry-After"))).isBetween(1L, 900L);
		assertThat(correctPasswordWhileBlocked.getStatus()).isEqualTo(429);
		assertThat(login(uniqueEmail(), PASSWORD, freshIp()).getStatus()).as("outro e-mail não é afetado").isEqualTo(401);
	}

	@Test
	void anonymousFailuresFromOtherIpsCannotLockTheOwnerOutOfTheirUsualIp() throws Exception {
		String email = register(uniqueEmail()).read("$.user.email");
		String ownerIp = freshIp();
		assertThat(login(email, PASSWORD, ownerIp).getStatus()).as("o dono entra do IP de sempre").isEqualTo(200);

		for (int i = 1; i <= 21; i++) {
			login(email, "Errada" + i + "999", freshIp());
		}

		assertThat(login(email, "Errada22999", freshIp()).getStatus()).as("IP desconhecido está bloqueado").isEqualTo(429);
		assertThat(login(email, PASSWORD, ownerIp).getStatus()).as("o dono continua entrando do IP conhecido").isEqualTo(200);
	}

	@Test
	void withEveryHashSlotBusyLoginAndRegistrationGet429WithoutCountingALoginFailure() throws Exception {
		String email = register(uniqueEmail()).read("$.user.email");
		String ip = freshIp();
		int slots = Math.max(2, Runtime.getRuntime().availableProcessors());
		java.util.concurrent.CountDownLatch holding = new java.util.concurrent.CountDownLatch(slots);
		java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		for (int i = 0; i < slots; i++) {
			Thread.ofVirtual().start(() -> hashingGate.run(() -> {
				holding.countDown();
				try {
					release.await();
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				return null;
			}));
		}
		try {
			assertThat(holding.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

			MockHttpServletResponse login = login(email, PASSWORD, ip);
			MockHttpServletResponse registration = call("/api/v1/auth/register",
					registerBody("Clínica " + UUID.randomUUID(), uniqueEmail(), PASSWORD), freshIp());

			assertThat(login.getStatus()).isEqualTo(429);
			assertThat(login.getHeader("Retry-After")).isEqualTo("3");
			assertThat(body(login).<String>read("$.type")).isEqualTo("https://crm.local/problems/rate-limited");
			assertThat(registration.getStatus()).isEqualTo(429);
			assertThat(registration.getHeader("Retry-After")).isEqualTo("3");
		}
		finally {
			release.countDown();
		}
		// a falta de vaga não foi contada como senha errada: com as vagas livres o mesmo IP+e-mail entra
		assertThat(login(email, PASSWORD, ip).getStatus()).isEqualTo(200);
	}

	@Test
	void successfulLoginResetsTheFailureCounter() throws Exception {
		String email = register(uniqueEmail()).read("$.user.email");
		String ip = freshIp();

		for (int i = 0; i < 4; i++) {
			assertThat(login(email, "Errada999" + i, ip).getStatus()).isEqualTo(401);
		}
		assertThat(login(email, PASSWORD, ip).getStatus()).isEqualTo(200);
		for (int i = 0; i < 5; i++) {
			assertThat(login(email, "Errada888" + i, ip).getStatus()).as("falha %d após o sucesso", i + 1).isEqualTo(401);
		}
	}

	@Test
	void ipLimitOf20RequestsPerMinuteApplies() throws Exception {
		String ip = freshIp();
		for (int i = 0; i < 20; i++) {
			assertThat(login(uniqueEmail(), PASSWORD, ip).getStatus()).isEqualTo(401);
		}

		MockHttpServletResponse blocked = login(uniqueEmail(), PASSWORD, ip);

		assertThat(blocked.getStatus()).isEqualTo(429);
		assertThat(blocked.getHeader("Retry-After")).isNotBlank();
	}

	// ---- Refresh e logout ----

	@Test
	void refreshRotatesThePairAndReusingTheOldTokenRevokesTheWholeFamily() throws Exception {
		DocumentContext first = register(uniqueEmail());
		String refresh1 = first.read("$.refreshToken");

		MockHttpServletResponse rotated = refresh(refresh1);
		assertThat(rotated.getStatus()).isEqualTo(200);
		DocumentContext second = body(rotated);
		String refresh2 = second.read("$.refreshToken");
		assertThat(refresh2).isNotEqualTo(refresh1);
		assertThat(second.<String>read("$.accessToken")).isNotEqualTo(first.read("$.accessToken"));
		assertThat(status("/probe/auth/any", second.read("$.accessToken"))).isEqualTo(200);

		UUID familyId = admin.queryForObject("SELECT family_id FROM core.refresh_tokens WHERE token_hash = ?", UUID.class,
				sha256Hex(refresh1));
		assertThat(admin.queryForObject("SELECT family_id FROM core.refresh_tokens WHERE token_hash = ?", UUID.class,
				sha256Hex(refresh2))).as("a rotação mantém a família").isEqualTo(familyId);
		assertThat(admin.queryForObject("SELECT used_at IS NOT NULL FROM core.refresh_tokens WHERE token_hash = ?", Boolean.class,
				sha256Hex(refresh1))).isTrue();

		assertThat(refresh(refresh1).getStatus()).as("reapresentar o token antigo").isEqualTo(401);
		assertThat(refresh(refresh2).getStatus()).as("o novo também cai com a família").isEqualTo(401);

		assertThat(admin.queryForObject("SELECT count(*) FROM core.refresh_tokens WHERE family_id = ? AND revoked_at IS NULL",
				Long.class, familyId)).isZero();
		assertThat(events.stream(RefreshReuseDetected.class)).anySatisfy(
				event -> assertThat(event.familyId()).isEqualTo(familyId));
	}

	@Test
	void unknownExpiredAndRevokedRefreshTokensAre401() throws Exception {
		assertThat(refresh("A".repeat(43)).getStatus()).isEqualTo(401);

		String expired = register(uniqueEmail()).read("$.refreshToken");
		admin.update("UPDATE core.refresh_tokens SET expires_at = now() - interval '1 minute' WHERE token_hash = ?", sha256Hex(expired));
		assertThat(refresh(expired).getStatus()).isEqualTo(401);

		String revoked = register(uniqueEmail()).read("$.refreshToken");
		admin.update("UPDATE core.refresh_tokens SET revoked_at = now() WHERE token_hash = ?", sha256Hex(revoked));
		assertThat(refresh(revoked).getStatus()).isEqualTo(401);
	}

	@Test
	void refreshTokenLivesSevenDays() throws Exception {
		String token = register(uniqueEmail()).read("$.refreshToken");

		Double days = admin.queryForObject(
				"SELECT extract(epoch FROM (expires_at - created_at)) / 86400.0 FROM core.refresh_tokens WHERE token_hash = ?",
				Double.class, sha256Hex(token));

		assertThat(days).isBetween(6.99, 7.01);
	}

	@Test
	void deactivatedUserCanNeitherRefreshNorLogin() throws Exception {
		DocumentContext dentist = register(uniqueEmail());
		UUID clinicId = UUID.fromString(dentist.read("$.user.clinicId"));
		String email = createReceptionist(clinicId, true);
		DocumentContext session = body(login(email, PASSWORD, freshIp()));
		String refreshToken = session.read("$.refreshToken");

		admin.update("UPDATE core.users SET active = false WHERE email = ?", email);

		assertThat(refresh(refreshToken).getStatus()).isEqualTo(401);
		assertThat(login(email, PASSWORD, freshIp()).getStatus()).isEqualTo(401);
		assertThat(admin.queryForObject("SELECT count(*) FROM core.refresh_tokens WHERE token_hash = ? AND revoked_at IS NOT NULL",
				Long.class, sha256Hex(refreshToken))).as("a família foi revogada").isEqualTo(1L);
	}

	@Test
	void logoutRevokesTheSessionAndIsIdempotent() throws Exception {
		String token = register(uniqueEmail()).read("$.refreshToken");
		String logout = "{\"refreshToken\":\"%s\"}".formatted(token);

		assertThat(call("/api/v1/auth/logout", logout, freshIp()).getStatus()).isEqualTo(204);
		assertThat(refresh(token).getStatus()).isEqualTo(401);
		assertThat(call("/api/v1/auth/logout", logout, freshIp()).getStatus()).as("repetir o logout").isEqualTo(204);
		assertThat(call("/api/v1/auth/logout", "{\"refreshToken\":\"%s\"}".formatted("B".repeat(43)), freshIp()).getStatus())
				.as("token desconhecido").isEqualTo(204);
		assertThat(call("/api/v1/auth/logout", "{\"refreshToken\":\"curto\"}", freshIp()).getStatus()).isEqualTo(400);
	}

	@Test
	void logoutRevokesTheWholeFamilyIncludingTheCurrentToken() throws Exception {
		DocumentContext first = register(uniqueEmail());
		DocumentContext second = body(refresh(first.read("$.refreshToken")));
		String currentToken = second.read("$.refreshToken");

		assertThat(call("/api/v1/auth/logout", "{\"refreshToken\":\"%s\"}".formatted(currentToken), freshIp()).getStatus())
				.isEqualTo(204);

		assertThat(refresh(currentToken).getStatus()).isEqualTo(401);
	}

	@Test
	void concurrentRefreshOfTheSameTokenHasOneWinnerAndTheFamilyIsRevoked() throws Exception {
		String token = register(uniqueEmail()).read("$.refreshToken");
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				String ip = freshIp();
				futures.add(pool.submit(() -> call("/api/v1/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(token), ip)));
			}
			List<MockHttpServletResponse> responses = new ArrayList<>();
			for (Future<MockHttpServletResponse> future : futures) {
				responses.add(future.get());
			}
			List<Integer> statuses = responses.stream().map(MockHttpServletResponse::getStatus).sorted().toList();
			// no máximo um vence; o outro é tratado como reuso (401). Se ambos chegarem em ordem, o 2º vê used_at.
			assertThat(statuses).containsExactly(200, 401);

			String winnerToken = responses.stream().filter(r -> r.getStatus() == 200).map(r -> {
				try {
					return body(r).<String>read("$.refreshToken");
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
			}).findFirst().orElseThrow();
			assertThat(refresh(winnerToken).getStatus()).as("o reuso revoga a família inteira").isEqualTo(401);
		}
		finally {
			pool.shutdownNow();
		}
	}

	// ---- Proteção de rotas ----

	@Test
	void protectedRoutesRequireAValidAccessToken() throws Exception {
		DocumentContext session = register(uniqueEmail());
		UUID userId = UUID.fromString(session.read("$.user.id"));
		UUID clinicId = UUID.fromString(session.read("$.user.clinicId"));
		String valid = session.read("$.accessToken");
		Instant now = Instant.now();

		assertThat(status("/probe/auth/any", null)).as("sem token").isEqualTo(401);
		assertThat(status("/probe/auth/any", "lixo")).as("lixo").isEqualTo(401);
		assertThat(status("/probe/auth/any", valid)).as("válido").isEqualTo(200);

		String expired = signedToken("crm-clinica", now.minusSeconds(1000), now.minusSeconds(100), userId, clinicId, "DENTIST_ADMIN");
		assertThat(status("/probe/auth/any", expired)).as("expirado").isEqualTo(401);

		String wrongIssuer = signedToken("outro-emissor", now, now.plusSeconds(900), userId, clinicId, "DENTIST_ADMIN");
		assertThat(status("/probe/auth/any", wrongIssuer)).as("emissor errado").isEqualTo(401);

		String[] parts = valid.split("\\.");
		byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
		signature[0] ^= 1;
		String tamperedSignature = parts[0] + "." + parts[1] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
		assertThat(status("/probe/auth/any", tamperedSignature)).as("assinatura adulterada").isEqualTo(401);

		String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
				("{\"iss\":\"crm-clinica\",\"sub\":\"" + userId + "\",\"clinic_id\":\"" + UUID.randomUUID()
						+ "\",\"role\":\"DENTIST_ADMIN\",\"exp\":" + (now.getEpochSecond() + 900) + "}").getBytes(StandardCharsets.UTF_8));
		assertThat(status("/probe/auth/any", parts[0] + "." + forgedPayload + "." + parts[2])).as("payload trocado").isEqualTo(401);

		String noneHeader = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
		assertThat(status("/probe/auth/any", noneHeader + "." + parts[1] + ".")).as("alg none").isEqualTo(401);

		MockHttpServletResponse denied = mvc.perform(get("/probe/auth/any")).andReturn().getResponse();
		assertThat(denied.getContentType()).startsWith("application/problem+json");
		assertThat(body(denied).<String>read("$.type")).isEqualTo("https://crm.local/problems/unauthorized");
	}

	@Test
	void rolesAreEnforcedWithProblemJson() throws Exception {
		DocumentContext dentist = register(uniqueEmail());
		UUID clinicId = UUID.fromString(dentist.read("$.user.clinicId"));
		String receptionistToken = body(login(createReceptionist(clinicId, true), PASSWORD, freshIp())).read("$.accessToken");

		assertThat(status("/probe/auth/dentist", dentist.read("$.accessToken"))).isEqualTo(200);
		assertThat(status("/probe/auth/any", receptionistToken)).isEqualTo(200);

		MockHttpServletResponse forbidden = mvc.perform(get("/probe/auth/dentist").header("Authorization", "Bearer " + receptionistToken))
				.andReturn().getResponse();
		assertThat(forbidden.getStatus()).isEqualTo(403);
		assertThat(forbidden.getContentType()).startsWith("application/problem+json");
		assertThat(body(forbidden).<String>read("$.type")).isEqualTo("https://crm.local/problems/forbidden");
	}

	@Test
	void aStaleBearerTokenDoesNotBreakThePublicAuthRoutes() throws Exception {
		String email = register(uniqueEmail()).read("$.user.email");
		String staleToken = signedToken("crm-clinica", Instant.now().minusSeconds(2000), Instant.now().minusSeconds(1000),
				UUID.randomUUID(), UUID.randomUUID(), "DENTIST_ADMIN");

		MockHttpServletResponse response = mvc.perform(json("/api/v1/auth/login",
				"{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD), freshIp())
				.header("Authorization", "Bearer " + staleToken)).andReturn().getResponse();

		assertThat(response.getStatus()).isEqualTo(200);
	}

	// ---- Logs ----

	@Test
	void logsNeverContainPasswordsTokensHashesOrFullEmails(CapturedOutput output) throws Exception {
		String email = uniqueEmail();
		String secretPassword = "SenhaSecretaDoTeste77";
		MockHttpServletResponse registered = call("/api/v1/auth/register", registerBody("Clínica Logs", email, secretPassword), freshIp());
		DocumentContext session = body(registered);
		String refresh1 = session.read("$.refreshToken");
		String accessToken = session.read("$.accessToken");
		String passwordHash = admin.queryForObject("SELECT password_hash FROM core.users WHERE email = ?", String.class, email);

		login(email, "SenhaErrada12345", freshIp()); // falha de login
		login(uniqueEmail(), "OutraErrada12345", freshIp());
		refresh(refresh1); // rotação normal
		refresh(refresh1); // reuso: gera evento de segurança

		String logs = output.getAll();
		assertThat(logs).contains("Falha de login").contains("Reuso de refresh token detectado");
		assertThat(logs).doesNotContain(secretPassword, "SenhaErrada12345", "OutraErrada12345", refresh1, accessToken, passwordHash, email);
	}
}
