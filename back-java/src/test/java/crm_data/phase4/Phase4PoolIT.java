package crm_data.phase4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import crm_data.document.service.DocumentPdfModel;
import crm_data.document.service.DocumentPdfRenderer;
import crm_data.shared.security.Role;
import crm_data.support.PerfLimits;

/**
 * Pool de conexões sob PDFs lentos: o PDF segura uma conexão enquanto é renderizado (a transação grava o {@code DOWNLOAD}
 * antes). Com 3 PDFs simultâneos, cada um com a renderização demorando 700 ms, as leituras de documento continuam rápidas
 * (p95 ≤ 2 × o limite) e nenhuma falha por falta de conexão (o pool tem 10).
 */
@TestPropertySource(properties = { "crm.clinical-documents.pdfs-per-minute=600" })
class Phase4PoolIT extends Phase4ItSupport {

	@MockitoSpyBean
	DocumentPdfRenderer renderer;

	@AfterEach
	void reset() {
		org.mockito.Mockito.reset(renderer);
	}

	@Test
	void readsStayFastWhileThreeSlowPdfsHoldTheirConnections() throws Exception {
		Setup reader = openDentistSetup();
		String readable = read(mvc.perform(json(post("/api/v1/patients/" + reader.patient() + "/clinical-documents"),
				"{\"documentType\":\"CERTIFICATE\",\"restDays\":1}").with(as(reader.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated()).andReturn()).get("id").asString();
		List<Setup> pdfUsers = new ArrayList<>();
		List<String> pdfIds = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			Setup s = openDentistSetup();
			pdfUsers.add(s);
			pdfIds.add(read(mvc.perform(json(post("/api/v1/patients/" + s.patient() + "/clinical-documents"),
					"{\"documentType\":\"CERTIFICATE\",\"restDays\":1}").with(as(s.clinic(), Role.DENTIST_ADMIN)))
					.andExpect(status().isCreated()).andReturn()).get("id").asString());
		}
		for (int i = 0; i < 5; i++) { // aquece a leitura
			mvc.perform(get("/api/v1/clinical-documents/" + readable).with(as(reader.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk());
		}
		doAnswer(call -> {
			Thread.sleep(700);
			return call.callRealMethod();
		}).when(renderer).render(any(DocumentPdfModel.class));

		ExecutorService pool = Executors.newFixedThreadPool(3);
		List<Future<Integer>> pdfs = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			Setup s = pdfUsers.get(i);
			String id = pdfIds.get(i);
			pdfs.add(pool.submit(() -> mvc.perform(get("/api/v1/clinical-documents/" + id + "/pdf").with(as(s.clinic(), Role.DENTIST_ADMIN)))
					.andReturn().getResponse().getStatus()));
		}
		Thread.sleep(200); // os três PDFs já estão dentro da renderização lenta
		List<Long> samples = new ArrayList<>();
		int failed = 0;
		long deadline = System.currentTimeMillis() + 400;
		while (System.currentTimeMillis() < deadline || samples.size() < 20) {
			long start = System.nanoTime();
			int code = mvc.perform(get("/api/v1/clinical-documents/" + readable).with(as(reader.clinic(), Role.DENTIST_ADMIN)))
					.andReturn().getResponse().getStatus();
			samples.add((System.nanoTime() - start) / 1_000_000);
			if (code != 200) {
				failed++;
			}
			if (samples.size() >= 200) {
				break;
			}
		}
		for (Future<Integer> pdf : pdfs) {
			assertThat(pdf.get()).as("os PDFs lentos terminam bem").isEqualTo(200);
		}
		pool.shutdown();

		long p95 = p95(samples);
		System.out.println("P95 leitura-com-3-pdfs-lentos=" + p95 + " ms (" + samples.size() + " amostras)");
		assertThat(failed).as("nenhuma leitura falha por falta de conexão").isZero();
		assertThat(p95).as("p95 da leitura com 3 PDFs lentos (ms)").isLessThanOrEqualTo(2 * PerfLimits.p95Ms());
	}
}
