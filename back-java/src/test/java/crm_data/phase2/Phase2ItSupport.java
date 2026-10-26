package crm_data.phase2;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;
import crm_data.support.DatabaseContainer;
import crm_data.support.SqlRecordingDataSourceConfig;
import crm_data.support.tenant.TenantFixtures;
import crm_data.support.tenant.TenantFixtures.Clinic;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base dos ITs da Fase 2 (agenda). Mesma configuração da Fase 1, então o Spring reaproveita o contexto e o contêiner
 * PostgreSQL (V001–V021). Cada teste cria suas clínicas; nada depende de dados de outro teste.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SqlRecordingDataSourceConfig.class)
public abstract class Phase2ItSupport {

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		DatabaseContainer.register(registry);
	}

	@Autowired
	protected MockMvc mvc;
	@Autowired
	protected JsonMapper mapper;
	@Autowired
	protected JdbcTemplate jdbc;
	@Autowired
	protected PlatformTransactionManager transactionManager;

	@AfterEach
	void clearContexts() {
		TenantContext.clear();
		SecurityContextHolder.clearContext();
	}

	/** Clínica com dois usuários: o índice 0 faz o papel de dentista, o 1 o de recepcionista. */
	protected static Clinic newClinic() {
		return TenantFixtures.clinicWithUsers(2);
	}

	protected static UUID userOf(Clinic clinic, Role role) {
		return clinic.userIds().get(role == Role.DENTIST_ADMIN ? 0 : 1);
	}

	protected static RequestPostProcessor as(Clinic clinic, Role role) {
		return jwt().jwt(j -> j.subject(userOf(clinic, role).toString()).claim("clinic_id", clinic.id().toString())
				.claim("role", role.name())).authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	protected static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	protected JsonNode read(MvcResult result) throws Exception {
		return mapper.readTree(result.getResponse().getContentAsString());
	}

	/** Clínica com expediente de segunda a sexta (08–18, almoço 12–13) e um paciente ativo. */
	protected record Setup(Clinic clinic, String patient) {
	}

	/** Monta um {@link Setup} para subclasses de outros pacotes (o construtor do record é protegido). */
	protected static Setup setupOf(Clinic clinic, String patient) {
		return new Setup(clinic, patient);
	}

	protected Setup openClinic() throws Exception {
		Clinic clinic = newClinic();
		StringBuilder days = new StringBuilder();
		for (int weekday = 1; weekday <= 5; weekday++) {
			days.append(days.isEmpty() ? "" : ",").append(
					"{\"weekday\":%d,\"isOpen\":true,\"opensAt\":\"08:00\",\"closesAt\":\"18:00\",\"lunchStartsAt\":\"12:00\",\"lunchEndsAt\":\"13:00\"}"
							.formatted(weekday));
		}
		mvc.perform(json(put("/api/v1/business-hours"), "{\"days\":[" + days + "]}").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isOk());
		return new Setup(clinic, createPatient(clinic, "Maria Souza"));
	}

	/** Cria um paciente ativo pela API (como a recepção faria) e devolve o id. */
	protected String createPatient(Clinic clinic, String name) throws Exception {
		String body = "{\"name\":\"%s\",\"birthDate\":\"1988-04-12\",\"phone\":\"11987654321\"}".formatted(name);
		return read(mvc.perform(json(post("/api/v1/patients"), body).with(as(clinic, Role.RECEPTIONIST))).andExpect(status().isCreated())
				.andReturn()).get("id").asString();
	}

	/** Altera direto no banco com a sessão de dentista (a auditoria exige clínica e usuário na sessão). */
	protected void adminSql(Clinic clinic, String sql) {
		try (Connection connection = DatabaseContainer.admin().getDataSource().getConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("SELECT set_config('app.clinic_id', '%s', false), set_config('app.user_id', '%s', false), set_config('app.role', 'DENTIST_ADMIN', false)"
					.formatted(clinic.id(), userOf(clinic, Role.DENTIST_ADMIN)));
			statement.execute(sql);
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex.getMessage(), ex);
		}
	}

	/**
	 * Como {@link #asRole(Clinic, Role, Supplier)}, mas com usuário e papel livres ({@code null} = ausente), para provar o
	 * que o banco faz com um papel forjado, ausente ou sem usuário. Sem filtro Hibernate no caminho se o bloco usar SQL.
	 */
	protected <T> T asSession(Clinic clinic, UUID userId, Role role, Supplier<T> block) {
		TenantContext.set(clinic.id(), userId, role);
		try {
			return new TransactionTemplate(transactionManager).execute(status -> block.get());
		}
		finally {
			TenantContext.clear();
		}
	}

	/** Executa o bloco numa transação com o papel informado, como o filtro de tenant faria numa requisição. */
	protected <T> T asRole(Clinic clinic, Role role, Supplier<T> block) {
		TenantContext.set(clinic.id(), userOf(clinic, role), role);
		try {
			return new TransactionTemplate(transactionManager).execute(status -> block.get());
		}
		finally {
			TenantContext.clear();
		}
	}
}
