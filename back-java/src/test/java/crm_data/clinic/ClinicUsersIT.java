package crm_data.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import crm_data.auth.service.AuthEvents.UserSessionsRevoked;
import crm_data.clinic.service.ClinicEvents;
import crm_data.support.DatabaseContainer;

/**
 * Perfil, clínica e equipe de ponta a ponta contra o PostgreSQL real (RLS, funções de bootstrap) e tokens JWT
 * emitidos pela própria F04. BCrypt com custo 4 só para acelerar o teste.
 */
@SpringBootTest(properties = "crm.auth.bcrypt-strength=4")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@RecordApplicationEvents
@ExtendWith(OutputCaptureExtension.class)
class ClinicUsersIT {

	private static final String PASSWORD = "SenhaForte2026";
	private static final AtomicInteger IP_COUNTER = new AtomicInteger();

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		DatabaseContainer.register(registry);
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	ApplicationEvents events;

	final JdbcTemplate admin = DatabaseContainer.admin();

	/** Usuário autenticado de um teste. */
	record Account(String email, String password, String accessToken, String refreshToken, UUID userId, UUID clinicId) {
	}

	// ---- Auxiliares ----

	private static String freshIp() {
		int n = IP_COUNTER.incrementAndGet();
		return "10.2." + (n / 250) + "." + (n % 250 + 1);
	}

	private static String uniqueEmail() {
		return "usuario-" + UUID.randomUUID() + "@teste.local";
	}

	private MockHttpServletResponse send(HttpMethod method, String path, String token, String body) throws Exception {
		MockHttpServletRequestBuilder request = request(method, path).with(r -> {
			r.setRemoteAddr(freshIp());
			return r;
		});
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request).andReturn().getResponse();
	}

	private static DocumentContext json(MockHttpServletResponse response) throws Exception {
		return JsonPath.parse(response.getContentAsString(StandardCharsets.UTF_8));
	}

	private Account registerDentist() throws Exception {
		String email = uniqueEmail();
		MockHttpServletResponse response = send(HttpMethod.POST, "/api/v1/auth/register", null,
				"{\"clinicName\":\"Clínica %s\",\"name\":\"Dra. Ana\",\"email\":\"%s\",\"password\":\"%s\"}".formatted(UUID.randomUUID(), email, PASSWORD));
		assertThat(response.getStatus()).isEqualTo(201);
		return toAccount(json(response), email, PASSWORD);
	}

	private Account login(String email, String password) throws Exception {
		MockHttpServletResponse response = send(HttpMethod.POST, "/api/v1/auth/login", null,
				"{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password));
		assertThat(response.getStatus()).as("login de %s", email).isEqualTo(200);
		return toAccount(json(response), email, password);
	}

	private static Account toAccount(DocumentContext session, String email, String password) {
		return new Account(email, password, session.read("$.accessToken"), session.read("$.refreshToken"),
				UUID.fromString(session.read("$.user.id")), UUID.fromString(session.read("$.user.clinicId")));
	}

	/** Dentista cria um recepcionista pela API e este faz login. */
	private Account hireReceptionist(Account dentist, String name) throws Exception {
		String email = uniqueEmail();
		MockHttpServletResponse created = send(HttpMethod.POST, "/api/v1/users", dentist.accessToken(),
				"{\"name\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}".formatted(name, email, PASSWORD));
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return login(email, PASSWORD);
	}

	private int refreshStatus(String refreshToken) throws Exception {
		return send(HttpMethod.POST, "/api/v1/auth/refresh", null, "{\"refreshToken\":\"%s\"}".formatted(refreshToken)).getStatus();
	}

	private int loginStatus(String email, String password) throws Exception {
		return send(HttpMethod.POST, "/api/v1/auth/login", null, "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)).getStatus();
	}

	// ---- Matriz papel × endpoint ----

	@Test
	void receptionistGets403OnClinicEditAndEveryUsersRouteButCanReadClinicAndProfile() throws Exception {
		Account dentist = registerDentist();
		Account receptionist = hireReceptionist(dentist, "Bia");
		String token = receptionist.accessToken();
		UUID someId = UUID.randomUUID();

		assertThat(send(HttpMethod.GET, "/api/v1/clinic", token, null).getStatus()).isEqualTo(200);
		assertThat(send(HttpMethod.GET, "/api/v1/me", token, null).getStatus()).isEqualTo(200);

		assertThat(send(HttpMethod.PUT, "/api/v1/clinic", token, "{\"name\":\"Invasão\",\"version\":0}").getStatus()).isEqualTo(403);
		assertThat(send(HttpMethod.GET, "/api/v1/users", token, null).getStatus()).isEqualTo(403);
		assertThat(send(HttpMethod.POST, "/api/v1/users", token,
				"{\"name\":\"Xé\",\"email\":\"%s\",\"password\":\"%s\"}".formatted(uniqueEmail(), PASSWORD)).getStatus()).isEqualTo(403);
		assertThat(send(HttpMethod.PATCH, "/api/v1/users/" + dentist.userId() + "/active", token, "{\"active\":false}").getStatus()).isEqualTo(403);
		assertThat(send(HttpMethod.PUT, "/api/v1/users/" + someId + "/password", token, "{\"newPassword\":\"%s\"}".formatted(PASSWORD)).getStatus()).isEqualTo(403);

		MockHttpServletResponse forbidden = send(HttpMethod.GET, "/api/v1/users", token, null);
		assertThat(forbidden.getContentType()).startsWith("application/problem+json");
		assertThat(json(forbidden).<String>read("$.type")).isEqualTo("https://crm.local/problems/forbidden");
		assertThat(admin.queryForObject("SELECT active FROM core.users WHERE id = ?", Boolean.class, dentist.userId())).isTrue();
	}

	@Test
	void unauthenticatedRequestsGet401() throws Exception {
		for (String path : new String[] { "/api/v1/me", "/api/v1/clinic", "/api/v1/users" }) {
			assertThat(send(HttpMethod.GET, path, null, null).getStatus()).as(path).isEqualTo(401);
		}
	}

	// ---- Isolamento por clínica ----

	@Test
	void idsFromAnotherClinicAre404ForActivationAndPasswordResetAndTheOtherClinicIsUntouched() throws Exception {
		Account dentistA = registerDentist();
		Account dentistB = registerDentist();
		Account receptionistB = hireReceptionist(dentistB, "Bia da B");

		MockHttpServletResponse deactivate = send(HttpMethod.PATCH, "/api/v1/users/" + receptionistB.userId() + "/active",
				dentistA.accessToken(), "{\"active\":false}");
		MockHttpServletResponse reset = send(HttpMethod.PUT, "/api/v1/users/" + receptionistB.userId() + "/password",
				dentistA.accessToken(), "{\"newPassword\":\"%s\"}".formatted("OutraSenhaForte2026"));

		assertThat(deactivate.getStatus()).isEqualTo(404);
		assertThat(reset.getStatus()).isEqualTo(404);
		assertThat(json(deactivate).<String>read("$.type")).isEqualTo("https://crm.local/problems/not-found");
		assertThat(admin.queryForObject("SELECT active FROM core.users WHERE id = ?", Boolean.class, receptionistB.userId())).isTrue();
		assertThat(loginStatus(receptionistB.email(), PASSWORD)).as("a senha de B não mudou").isEqualTo(200);
		assertThat(refreshStatus(receptionistB.refreshToken())).as("a sessão de B não foi revogada").isEqualTo(200);
	}

	@Test
	void clinicEndpointAlwaysReturnsTheClinicOfTheToken() throws Exception {
		Account dentistA = registerDentist();
		Account dentistB = registerDentist();

		DocumentContext clinicA = json(send(HttpMethod.GET, "/api/v1/clinic", dentistA.accessToken(), null));
		DocumentContext clinicB = json(send(HttpMethod.GET, "/api/v1/clinic", dentistB.accessToken(), null));

		assertThat(clinicA.<String>read("$.id")).isEqualTo(dentistA.clinicId().toString());
		assertThat(clinicB.<String>read("$.id")).isEqualTo(dentistB.clinicId().toString());
	}

	// ---- Criar recepcionista ----

	@Test
	void dentistCreatesReceptionistInHisOwnClinicIgnoringRoleAndClinicInTheBody() throws Exception {
		Account dentist = registerDentist();
		Account otherClinic = registerDentist();
		String email = uniqueEmail();
		String body = """
				{"name":"  Bia Souza ","email":"  %s ","password":"%s","role":"DENTIST_ADMIN",
				 "clinicId":"%s","id":"%s","active":false}""".formatted(email.toUpperCase(), PASSWORD, otherClinic.clinicId(), UUID.randomUUID());

		MockHttpServletResponse response = send(HttpMethod.POST, "/api/v1/users", dentist.accessToken(), body);

		assertThat(response.getStatus()).isEqualTo(201);
		DocumentContext created = json(response);
		assertThat(created.<String>read("$.role")).isEqualTo("RECEPTIONIST");
		assertThat(created.<String>read("$.clinicId")).isEqualTo(dentist.clinicId().toString());
		assertThat(created.<Boolean>read("$.active")).isTrue();
		assertThat(created.<String>read("$.name")).isEqualTo("Bia Souza");
		assertThat(created.<String>read("$.email")).isEqualTo(email);
		assertThat(response.getContentAsString()).doesNotContain("password").doesNotContain("$2a$");

		var row = admin.queryForMap("SELECT created_by, password_hash FROM core.users WHERE email = ?", email);
		assertThat(row.get("created_by")).isEqualTo(dentist.userId());
		assertThat((String) row.get("password_hash")).startsWith("$2a$04$").doesNotContain(PASSWORD);
		assertThat(events.stream(ClinicEvents.UserCreated.class)).anySatisfy(event -> {
			assertThat(event.userId().toString()).isEqualTo(created.read("$.id"));
			assertThat(event.clinicId()).isEqualTo(dentist.clinicId());
		});
		assertThat(login(email, PASSWORD).clinicId()).isEqualTo(dentist.clinicId());
	}

	@Test
	void existingEmailIs409EvenFromAnotherClinicOrWithDifferentCase() throws Exception {
		Account dentistA = registerDentist();
		Account dentistB = registerDentist();
		String body = "{\"name\":\"Bia\",\"email\":\"%s\",\"password\":\"%s\"}";

		assertThat(send(HttpMethod.POST, "/api/v1/users", dentistA.accessToken(), body.formatted(dentistB.email().toUpperCase(), PASSWORD)).getStatus())
				.as("e-mail de outra clínica").isEqualTo(409);
		assertThat(send(HttpMethod.POST, "/api/v1/users", dentistA.accessToken(), body.formatted(dentistA.email(), PASSWORD)).getStatus())
				.as("e-mail do próprio dentista").isEqualTo(409);
		assertThat(admin.queryForObject("SELECT count(*) FROM core.users WHERE clinic_id = ?", Long.class, dentistA.clinicId())).isEqualTo(1L);
	}

	@Test
	void concurrentCreationWithTheSameEmailYieldsOneWinner() throws Exception {
		Account dentist = registerDentist();
		String email = uniqueEmail();
		ExecutorService pool = Executors.newFixedThreadPool(6);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < 6; i++) {
				Callable<Integer> task = () -> send(HttpMethod.POST, "/api/v1/users", dentist.accessToken(),
						"{\"name\":\"Bia\",\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)).getStatus();
				results.add(pool.submit(task));
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> result : results) {
				statuses.add(result.get());
			}
			assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
			assertThat(statuses).filteredOn(s -> s == 409).hasSize(5);
		}
		finally {
			pool.shutdownNow();
		}
	}

	// ---- Ativar / desativar ----

	@Test
	void dentistCannotDeactivateHimself_422() throws Exception {
		Account dentist = registerDentist();

		MockHttpServletResponse response = send(HttpMethod.PATCH, "/api/v1/users/" + dentist.userId() + "/active", dentist.accessToken(),
				"{\"active\":false}");

		assertThat(response.getStatus()).isEqualTo(422);
		assertThat(json(response).<String>read("$.type")).isEqualTo("https://crm.local/problems/business-rule");
		assertThat(admin.queryForObject("SELECT active FROM core.users WHERE id = ?", Boolean.class, dentist.userId())).isTrue();
		assertThat(refreshStatus(dentist.refreshToken())).as("a própria sessão continua válida").isEqualTo(200);
	}

	@Test
	void deactivatedUserCannotLoginNorRefreshAndHerSessionsAreRevoked() throws Exception {
		Account dentist = registerDentist();
		Account receptionist = hireReceptionist(dentist, "Bia");
		String secondRefresh = login(receptionist.email(), PASSWORD).refreshToken();

		MockHttpServletResponse response = send(HttpMethod.PATCH, "/api/v1/users/" + receptionist.userId() + "/active",
				dentist.accessToken(), "{\"active\":false}");

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(json(response).<Boolean>read("$.active")).isFalse();
		assertThat(refreshStatus(receptionist.refreshToken())).isEqualTo(401);
		assertThat(refreshStatus(secondRefresh)).as("todas as sessões, não só uma").isEqualTo(401);
		assertThat(loginStatus(receptionist.email(), PASSWORD)).isEqualTo(401);
		assertThat(admin.queryForObject("SELECT count(*) FROM core.refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
				Long.class, receptionist.userId())).isZero();
		assertThat(events.stream(UserSessionsRevoked.class)).anySatisfy(event -> assertThat(event.userId()).isEqualTo(receptionist.userId()));
	}

	@Test
	void reactivatedUserCanLoginAgainButOldSessionsStayRevoked() throws Exception {
		Account dentist = registerDentist();
		Account receptionist = hireReceptionist(dentist, "Bia");
		send(HttpMethod.PATCH, "/api/v1/users/" + receptionist.userId() + "/active", dentist.accessToken(), "{\"active\":false}");

		MockHttpServletResponse response = send(HttpMethod.PATCH, "/api/v1/users/" + receptionist.userId() + "/active",
				dentist.accessToken(), "{\"active\":true}");

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(json(response).<Boolean>read("$.active")).isTrue();
		assertThat(loginStatus(receptionist.email(), PASSWORD)).isEqualTo(200);
		assertThat(refreshStatus(receptionist.refreshToken())).as("sessão antiga continua revogada").isEqualTo(401);
	}

	@Test
	void activeRequiresTheFieldAndAValidUuid() throws Exception {
		Account dentist = registerDentist();

		assertThat(send(HttpMethod.PATCH, "/api/v1/users/" + UUID.randomUUID() + "/active", dentist.accessToken(), "{}").getStatus()).isEqualTo(400);
		assertThat(send(HttpMethod.PATCH, "/api/v1/users/nao-e-uuid/active", dentist.accessToken(), "{\"active\":true}").getStatus()).isEqualTo(400);
		assertThat(send(HttpMethod.PATCH, "/api/v1/users/" + UUID.randomUUID() + "/active", dentist.accessToken(), "{\"active\":true}").getStatus()).isEqualTo(404);
	}

	// ---- Redefinir senha de recepcionista ----

	@Test
	void resetPasswordRevokesTheTargetSessionsAndTheNewPasswordWorks() throws Exception {
		Account dentist = registerDentist();
		Account receptionist = hireReceptionist(dentist, "Bia");
		String newPassword = "NovaSenhaForte2026";

		MockHttpServletResponse response = send(HttpMethod.PUT, "/api/v1/users/" + receptionist.userId() + "/password",
				dentist.accessToken(), "{\"newPassword\":\"%s\"}".formatted(newPassword));

		assertThat(response.getStatus()).isEqualTo(204);
		assertThat(refreshStatus(receptionist.refreshToken())).as("refresh antigo do alvo").isEqualTo(401);
		assertThat(loginStatus(receptionist.email(), PASSWORD)).as("senha antiga").isEqualTo(401);
		assertThat(loginStatus(receptionist.email(), newPassword)).as("senha nova").isEqualTo(200);
		assertThat(events.stream(UserSessionsRevoked.class)).anySatisfy(event -> assertThat(event.userId()).isEqualTo(receptionist.userId()));
	}

	@Test
	void dentistPasswordCannotBeResetByThisRoute_422() throws Exception {
		Account dentist = registerDentist();

		MockHttpServletResponse response = send(HttpMethod.PUT, "/api/v1/users/" + dentist.userId() + "/password", dentist.accessToken(),
				"{\"newPassword\":\"NovaSenhaForte2026\"}");

		assertThat(response.getStatus()).isEqualTo(422);
		assertThat(loginStatus(dentist.email(), PASSWORD)).as("a senha do dentista não mudou").isEqualTo(200);
	}

	@Test
	void resetPasswordValidatesThePolicyAndTheTarget() throws Exception {
		Account dentist = registerDentist();
		Account receptionist = hireReceptionist(dentist, "Bia");

		assertThat(send(HttpMethod.PUT, "/api/v1/users/" + receptionist.userId() + "/password", dentist.accessToken(),
				"{\"newPassword\":\"fraca\"}").getStatus()).isEqualTo(400);
		assertThat(send(HttpMethod.PUT, "/api/v1/users/" + UUID.randomUUID() + "/password", dentist.accessToken(),
				"{\"newPassword\":\"NovaSenhaForte2026\"}").getStatus()).isEqualTo(404);
		assertThat(loginStatus(receptionist.email(), PASSWORD)).isEqualTo(200);
	}

	// ---- Meu perfil ----

	@Test
	void profileCanBeReadAndEditedByBothRolesAndOnlyAffectsTheTokenUser() throws Exception {
		Account dentist = registerDentist();
		Account receptionist = hireReceptionist(dentist, "Bia");
		String newEmail = uniqueEmail();

		MockHttpServletResponse updated = send(HttpMethod.PUT, "/api/v1/me", receptionist.accessToken(),
				("{\"name\":\"  Beatriz Lima \",\"email\":\"  %s \",\"role\":\"DENTIST_ADMIN\",\"clinicId\":\"%s\","
						+ "\"id\":\"%s\",\"active\":false}").formatted(newEmail.toUpperCase(), UUID.randomUUID(), dentist.userId()));

		assertThat(updated.getStatus()).isEqualTo(200);
		DocumentContext me = json(send(HttpMethod.GET, "/api/v1/me", receptionist.accessToken(), null));
		assertThat(me.<String>read("$.id")).isEqualTo(receptionist.userId().toString());
		assertThat(me.<String>read("$.name")).isEqualTo("Beatriz Lima");
		assertThat(me.<String>read("$.email")).isEqualTo(newEmail);
		assertThat(me.<String>read("$.role")).as("o papel não muda por esta rota").isEqualTo("RECEPTIONIST");
		assertThat(me.<String>read("$.clinicId")).isEqualTo(dentist.clinicId().toString());
		assertThat(me.<Boolean>read("$.active")).isTrue();
		assertThat(admin.queryForObject("SELECT name FROM core.users WHERE id = ?", String.class, dentist.userId())).isEqualTo("Dra. Ana");
		assertThat(send(HttpMethod.GET, "/api/v1/me", receptionist.accessToken(), null).getStatus())
				.as("trocar o e-mail não invalida o access token (o sub é o id)").isEqualTo(200);
		assertThat(loginStatus(newEmail, PASSWORD)).as("login com o novo e-mail").isEqualTo(200);
	}

	@Test
	void profileEmailInUseByAnotherUserIs409() throws Exception {
		Account dentist = registerDentist();
		Account other = registerDentist();

		MockHttpServletResponse response = send(HttpMethod.PUT, "/api/v1/me", dentist.accessToken(),
				"{\"name\":\"Ana\",\"email\":\"%s\"}".formatted(other.email()));

		assertThat(response.getStatus()).isEqualTo(409);
		assertThat(admin.queryForObject("SELECT email FROM core.users WHERE id = ?", String.class, dentist.userId())).isEqualTo(dentist.email());
	}

	@Test
	void keepingTheSameEmailIsNotAConflict() throws Exception {
		Account dentist = registerDentist();

		assertThat(send(HttpMethod.PUT, "/api/v1/me", dentist.accessToken(),
				"{\"name\":\"Ana Maria\",\"email\":\"%s\"}".formatted(dentist.email())).getStatus()).isEqualTo(200);
	}

	// ---- Trocar minha senha ----

	@Test
	void wrongCurrentPasswordIs401AndTheSameNewPasswordIs422() throws Exception {
		Account dentist = registerDentist();

		MockHttpServletResponse wrong = send(HttpMethod.PUT, "/api/v1/me/password", dentist.accessToken(),
				"{\"currentPassword\":\"SenhaErrada999\",\"newPassword\":\"NovaSenhaForte2026\"}");
		MockHttpServletResponse same = send(HttpMethod.PUT, "/api/v1/me/password", dentist.accessToken(),
				"{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(PASSWORD, PASSWORD));

		assertThat(wrong.getStatus()).isEqualTo(401);
		assertThat(json(wrong).<String>read("$.type")).isEqualTo("https://crm.local/problems/unauthorized");
		assertThat(same.getStatus()).isEqualTo(422);
		assertThat(refreshStatus(dentist.refreshToken())).as("nada foi revogado nos dois casos").isEqualTo(200);
		assertThat(loginStatus(dentist.email(), PASSWORD)).isEqualTo(200);
	}

	@Test
	void changingMyPasswordRevokesAllMySessionsAndTheNewPasswordWorks() throws Exception {
		Account dentist = registerDentist();
		String secondSession = login(dentist.email(), PASSWORD).refreshToken();
		String newPassword = "NovaSenhaForte2026";

		MockHttpServletResponse response = send(HttpMethod.PUT, "/api/v1/me/password", dentist.accessToken(),
				"{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(PASSWORD, newPassword));

		assertThat(response.getStatus()).isEqualTo(204);
		assertThat(refreshStatus(dentist.refreshToken())).isEqualTo(401);
		assertThat(refreshStatus(secondSession)).isEqualTo(401);
		assertThat(loginStatus(dentist.email(), PASSWORD)).isEqualTo(401);
		assertThat(loginStatus(dentist.email(), newPassword)).isEqualTo(200);
		assertThat(events.stream(UserSessionsRevoked.class)).anySatisfy(event -> assertThat(event.userId()).isEqualTo(dentist.userId()));
	}

	@Test
	void sixthWrongCurrentPasswordInAMinuteIs429EvenIfTheNextOneIsRight() throws Exception {
		Account dentist = registerDentist();
		String body = "{\"currentPassword\":\"%s\",\"newPassword\":\"NovaSenhaForte2026\"}";

		for (int i = 1; i <= 5; i++) {
			assertThat(send(HttpMethod.PUT, "/api/v1/me/password", dentist.accessToken(), body.formatted("Errada" + i + "999")).getStatus())
					.as("tentativa errada %d", i).isEqualTo(401);
		}
		MockHttpServletResponse sixth = send(HttpMethod.PUT, "/api/v1/me/password", dentist.accessToken(), body.formatted("Errada6999"));
		MockHttpServletResponse rightButBlocked = send(HttpMethod.PUT, "/api/v1/me/password", dentist.accessToken(), body.formatted(PASSWORD));

		assertThat(sixth.getStatus()).isEqualTo(429);
		assertThat(Long.parseLong(sixth.getHeader("Retry-After"))).isBetween(1L, 60L);
		assertThat(json(sixth).<String>read("$.type")).isEqualTo("https://crm.local/problems/rate-limited");
		assertThat(rightButBlocked.getStatus()).as("a senha certa também espera o bloqueio passar").isEqualTo(429);
		assertThat(loginStatus(dentist.email(), PASSWORD)).as("a senha não mudou").isEqualTo(200);
	}

	@Test
	void anotherUserIsNotAffectedByMyBlockedPasswordChange() throws Exception {
		Account dentist = registerDentist();
		Account other = registerDentist();
		for (int i = 0; i < 5; i++) {
			send(HttpMethod.PUT, "/api/v1/me/password", dentist.accessToken(),
					"{\"currentPassword\":\"Errada%d999\",\"newPassword\":\"NovaSenhaForte2026\"}".formatted(i));
		}

		assertThat(send(HttpMethod.PUT, "/api/v1/me/password", other.accessToken(),
				"{\"currentPassword\":\"%s\",\"newPassword\":\"NovaSenhaForte2026\"}".formatted(PASSWORD)).getStatus()).isEqualTo(204);
	}

	@Test
	void aSuccessfulChangeClearsTheFailureCounter() throws Exception {
		Account dentist = registerDentist();
		for (int i = 0; i < 4; i++) {
			send(HttpMethod.PUT, "/api/v1/me/password", dentist.accessToken(),
					"{\"currentPassword\":\"Errada%d999\",\"newPassword\":\"NovaSenhaForte2026\"}".formatted(i));
		}
		assertThat(send(HttpMethod.PUT, "/api/v1/me/password", dentist.accessToken(),
				"{\"currentPassword\":\"%s\",\"newPassword\":\"NovaSenhaForte2026\"}".formatted(PASSWORD)).getStatus()).isEqualTo(204);
		Account fresh = login(dentist.email(), "NovaSenhaForte2026");

		for (int i = 0; i < 5; i++) {
			assertThat(send(HttpMethod.PUT, "/api/v1/me/password", fresh.accessToken(),
					"{\"currentPassword\":\"Errada%d888\",\"newPassword\":\"OutraSenhaForte2026\"}".formatted(i)).getStatus())
					.as("falha %d após o sucesso", i + 1).isEqualTo(401);
		}
	}

	@Test
	void newPasswordMustFollowThePolicy() throws Exception {
		Account dentist = registerDentist();

		MockHttpServletResponse response = send(HttpMethod.PUT, "/api/v1/me/password", dentist.accessToken(),
				"{\"currentPassword\":\"%s\",\"newPassword\":\"curta1\"}".formatted(PASSWORD));

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(json(response).<List<?>>read("$.errors[?(@.field=='newPassword')]")).isNotEmpty();
	}

	// ---- Clínica ----

	@Test
	void dentistEditsTheClinicAndTheVersionAdvances() throws Exception {
		Account dentist = registerDentist();
		Account receptionist = hireReceptionist(dentist, "Bia");
		DocumentContext before = json(send(HttpMethod.GET, "/api/v1/clinic", dentist.accessToken(), null));
		long version = ((Number) before.read("$.version")).longValue();

		MockHttpServletResponse response = send(HttpMethod.PUT, "/api/v1/clinic", dentist.accessToken(),
				("{\"name\":\"  Clínica Sorriso Novo \",\"document\":\"12.345.678/0001-90\",\"phone\":\"11 3333-4444\","
						+ "\"email\":\"  CONTATO@clinica.com \",\"address\":\"Rua A, 10\",\"version\":%d,"
						+ "\"id\":\"%s\",\"clinicId\":\"%s\"}").formatted(version, UUID.randomUUID(), UUID.randomUUID()));

		assertThat(response.getStatus()).isEqualTo(200);
		DocumentContext after = json(response);
		assertThat(after.<String>read("$.id")).as("o id não vem do corpo").isEqualTo(dentist.clinicId().toString());
		assertThat(after.<String>read("$.name")).isEqualTo("Clínica Sorriso Novo");
		assertThat(after.<String>read("$.email")).isEqualTo("contato@clinica.com");
		assertThat(((Number) after.read("$.version")).longValue()).isEqualTo(version + 1);

		DocumentContext seenByReceptionist = json(send(HttpMethod.GET, "/api/v1/clinic", receptionist.accessToken(), null));
		assertThat(seenByReceptionist.<String>read("$.name")).isEqualTo("Clínica Sorriso Novo");
		assertThat(seenByReceptionist.<String>read("$.address")).isEqualTo("Rua A, 10");
		assertThat(events.stream(ClinicEvents.Updated.class)).anySatisfy(event -> assertThat(event.clinicId()).isEqualTo(dentist.clinicId()));
	}

	@Test
	void optionalClinicFieldsCanBeCleared() throws Exception {
		Account dentist = registerDentist();
		send(HttpMethod.PUT, "/api/v1/clinic", dentist.accessToken(), "{\"name\":\"Clínica\",\"phone\":\"11 9999-0000\",\"version\":0}");

		MockHttpServletResponse response = send(HttpMethod.PUT, "/api/v1/clinic", dentist.accessToken(),
				"{\"name\":\"Clínica\",\"phone\":\"   \",\"version\":1}");

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(json(response).<Object>read("$.phone")).isNull();
	}

	@Test
	void staleVersionIs409AndTheOtherEditIsKept() throws Exception {
		Account dentist = registerDentist();
		assertThat(send(HttpMethod.PUT, "/api/v1/clinic", dentist.accessToken(), "{\"name\":\"Primeira edição\",\"version\":0}").getStatus()).isEqualTo(200);

		MockHttpServletResponse stale = send(HttpMethod.PUT, "/api/v1/clinic", dentist.accessToken(), "{\"name\":\"Edição velha\",\"version\":0}");

		assertThat(stale.getStatus()).isEqualTo(409);
		assertThat(json(stale).<String>read("$.type")).isEqualTo("https://crm.local/problems/conflict");
		assertThat(admin.queryForObject("SELECT name FROM core.clinics WHERE id = ?", String.class, dentist.clinicId())).isEqualTo("Primeira edição");
	}

	@Test
	void twoSimultaneousEditsWithTheSameVersionHaveOneWinner() throws Exception {
		Account dentist = registerDentist();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				String name = "Clínica concorrente " + i;
				Callable<Integer> task = () -> send(HttpMethod.PUT, "/api/v1/clinic", dentist.accessToken(),
						"{\"name\":\"%s\",\"version\":0}".formatted(name)).getStatus();
				results.add(pool.submit(task));
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> result : results) {
				statuses.add(result.get());
			}
			assertThat(statuses).containsExactlyInAnyOrder(200, 409);
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void clinicEditRequiresNameAndVersion() throws Exception {
		Account dentist = registerDentist();

		MockHttpServletResponse response = send(HttpMethod.PUT, "/api/v1/clinic", dentist.accessToken(), "{}");

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(json(response).<List<?>>read("$.errors[?(@.field=='version')]")).isNotEmpty();
		assertThat(json(response).<List<?>>read("$.errors[?(@.field=='name')]")).isNotEmpty();
	}

	// ---- Listagem ----

	@Test
	void listIsPaginatedOrderedByNameAndLimitedToTheOwnClinic() throws Exception {
		Account dentist = registerDentist(); // "Dra. Ana"
		Account otherClinic = registerDentist();
		hireReceptionist(otherClinic, "Zzz de outra clínica");
		for (String name : new String[] { "Carla", "Bia", "Zeca", "Adriana" }) {
			hireReceptionist(dentist, name);
		}

		DocumentContext page0 = json(send(HttpMethod.GET, "/api/v1/users?page=0&size=2", dentist.accessToken(), null));
		DocumentContext page1 = json(send(HttpMethod.GET, "/api/v1/users?page=1&size=2", dentist.accessToken(), null));
		DocumentContext page2 = json(send(HttpMethod.GET, "/api/v1/users?page=2&size=2", dentist.accessToken(), null));

		assertThat(page0.<List<String>>read("$.content[*].name")).containsExactly("Adriana", "Bia");
		assertThat(page1.<List<String>>read("$.content[*].name")).containsExactly("Carla", "Dra. Ana");
		assertThat(page2.<List<String>>read("$.content[*].name")).containsExactly("Zeca");
		assertThat(page0.<Integer>read("$.totalElements")).isEqualTo(5);
		assertThat(page0.<Integer>read("$.totalPages")).isEqualTo(3);
		assertThat(page0.<Integer>read("$.size")).isEqualTo(2);
		assertThat(page1.<Integer>read("$.page")).isEqualTo(1);
		assertThat(page0.<List<String>>read("$.content[*].clinicId")).containsOnly(dentist.clinicId().toString());
	}

	@Test
	void listDefaultsTo20AndRejectsSizeAbove50OrBelow1() throws Exception {
		Account dentist = registerDentist();

		DocumentContext defaults = json(send(HttpMethod.GET, "/api/v1/users", dentist.accessToken(), null));
		assertThat(defaults.<Integer>read("$.size")).isEqualTo(20);
		assertThat(defaults.<Integer>read("$.page")).isZero();

		assertThat(send(HttpMethod.GET, "/api/v1/users?size=51", dentist.accessToken(), null).getStatus()).isEqualTo(400);
		assertThat(send(HttpMethod.GET, "/api/v1/users?size=0", dentist.accessToken(), null).getStatus()).isEqualTo(400);
		assertThat(send(HttpMethod.GET, "/api/v1/users?page=-1", dentist.accessToken(), null).getStatus()).isEqualTo(400);
		assertThat(send(HttpMethod.GET, "/api/v1/users?size=50", dentist.accessToken(), null).getStatus()).isEqualTo(200);
	}

	// ---- Dados sensíveis ----

	@Test
	void noResponseEverContainsThePasswordOrItsHash() throws Exception {
		Account dentist = registerDentist();
		Account receptionist = hireReceptionist(dentist, "Bia");
		List<MockHttpServletResponse> responses = List.of(
				send(HttpMethod.GET, "/api/v1/me", dentist.accessToken(), null),
				send(HttpMethod.PUT, "/api/v1/me", dentist.accessToken(), "{\"name\":\"Ana\",\"email\":\"%s\"}".formatted(dentist.email())),
				send(HttpMethod.GET, "/api/v1/clinic", dentist.accessToken(), null),
				send(HttpMethod.GET, "/api/v1/users", dentist.accessToken(), null),
				send(HttpMethod.PATCH, "/api/v1/users/" + receptionist.userId() + "/active", dentist.accessToken(), "{\"active\":true}"),
				send(HttpMethod.POST, "/api/v1/users", dentist.accessToken(), "{\"name\":\"Cris\",\"email\":\"%s\",\"password\":\"%s\"}".formatted(uniqueEmail(), PASSWORD)));

		for (MockHttpServletResponse response : responses) {
			String body = response.getContentAsString(StandardCharsets.UTF_8).toLowerCase();
			assertThat(body).doesNotContain("password").doesNotContain("hash").doesNotContain("$2a$").doesNotContain(PASSWORD.toLowerCase());
		}
	}

	@Test
	void logsNeverContainPasswordsOrHashes(CapturedOutput output) throws Exception {
		Account dentist = registerDentist();
		String secret = "SenhaSecretaDaRecepcao88";
		String email = uniqueEmail();
		send(HttpMethod.POST, "/api/v1/users", dentist.accessToken(), "{\"name\":\"Bia\",\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, secret));
		UUID id = admin.queryForObject("SELECT id FROM core.users WHERE email = ?", UUID.class, email);
		send(HttpMethod.PUT, "/api/v1/users/" + id + "/password", dentist.accessToken(), "{\"newPassword\":\"OutraSenhaSecreta99\"}");
		send(HttpMethod.PUT, "/api/v1/me/password", dentist.accessToken(), "{\"currentPassword\":\"AtualErradaSecreta11\",\"newPassword\":\"NovaSecretaDoDentista22\"}");
		String hash = admin.queryForObject("SELECT password_hash FROM core.users WHERE id = ?", String.class, id);

		assertThat(output.getAll()).doesNotContain(secret).doesNotContain("OutraSenhaSecreta99").doesNotContain("AtualErradaSecreta11")
				.doesNotContain("NovaSecretaDoDentista22").doesNotContain(hash);
	}
}
