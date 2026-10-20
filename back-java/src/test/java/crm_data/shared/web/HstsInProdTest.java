package crm_data.shared.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import crm_data.support.PlatformWebTest;

/** Com {@code crm.hsts-enabled=true} (perfil prod) o HSTS é enviado em requisições HTTPS. */
@PlatformWebTest
@TestPropertySource(properties = "crm.hsts-enabled=true")
class HstsInProdTest {

	@Autowired
	MockMvc mvc;

	@Test
	void sendsStrictTransportSecurityOverHttps() throws Exception {
		mvc.perform(get("/probe/ok").secure(true).with(user("u")))
				.andExpect(header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"));
	}
}
