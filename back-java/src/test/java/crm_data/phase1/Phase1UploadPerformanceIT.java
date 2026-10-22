package crm_data.phase1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import crm_data.record.service.PatientFileService;
import crm_data.record.service.PatientFileService.Upload;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;
import crm_data.support.tenant.TenantFixtures.Clinic;

/**
 * Upload de 10 MB com o {@code LocalFileStorage}: no máximo 5 s e sem o arquivo inteiro na memória. O conteúdo é gerado
 * sob demanda e o teste afirma que o service entrega ao Storage o próprio fluxo (nunca um {@code byte[]}), lido em
 * pedaços pequenos, e que a checagem do tipo consome só o início do arquivo.
 */
class Phase1UploadPerformanceIT extends Phase1ItSupport {

	private static final long TEN_MB = 10_485_760L;
	private static final long LIMIT_SECONDS = 5;

	@Autowired
	PatientFileService fileService;

	/** 10 MB de zeros depois do cabeçalho PNG, produzidos sob demanda; registra o maior pedido de leitura. */
	private static final class OnDemandPng extends InputStream {

		private long produced;
		final AtomicLong maxRequest = new AtomicLong();
		final AtomicLong totalRead = new AtomicLong();

		@Override
		public int read() {
			byte[] one = new byte[1];
			return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) {
			maxRequest.accumulateAndGet(length, Math::max);
			if (produced >= TEN_MB) {
				return -1;
			}
			int n = (int) Math.min(length, TEN_MB - produced);
			Arrays.fill(buffer, offset, offset + n, (byte) 0);
			for (int i = 0; i < n && produced + i < PNG_HEAD.length; i++) {
				buffer[offset + i] = PNG_HEAD[(int) (produced + i)];
			}
			produced += n;
			totalRead.addAndGet(n);
			return n;
		}
	}

	@Test
	void aTenMegabyteUploadThroughTheApiFinishesWithinFiveSeconds() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic, "Maria Souza", null);
		MockMultipartFile file = new MockMultipartFile("file", "limite.png", "image/png", Arrays.copyOf(PNG_HEAD, (int) TEN_MB));

		long start = System.nanoTime();
		mvc.perform(multipart("/api/v1/patients/" + patient + "/files").file(file).param("kind", "OTHER").with(as(clinic, Role.RECEPTIONIST)))
				.andExpect(status().isCreated());
		Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

		System.out.printf("F06 upload de 10 MB pela API: %d ms (limite %d s)%n", elapsed.toMillis(), LIMIT_SECONDS);
		assertThat(elapsed).isLessThanOrEqualTo(Duration.ofSeconds(LIMIT_SECONDS));
		assertThat(objectsOf(clinic)).isEqualTo(1);
	}

	@Test
	void theServiceStreamsTheFileToTheStorageInSmallChunksWithoutMaterializingIt() throws Exception {
		Clinic clinic = newClinic();
		UUID patient = UUID.fromString(createPatient(clinic, "Maria Souza", null));
		TenantContext.set(clinic.id(), clinic.userIds().get(1), Role.RECEPTIONIST);
		SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("recepcao", "n/a", "ROLE_RECEPTIONIST"));
		java.util.List<OnDemandPng> opened = new java.util.ArrayList<>();
		Upload upload = new Upload("OTHER", "image/png", "grande.png", TEN_MB, () -> {
			OnDemandPng stream = new OnDemandPng();
			opened.add(stream);
			return stream;
		});

		long start = System.nanoTime();
		fileService.upload(patient, upload);
		Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

		System.out.printf("F06 upload de 10 MB pelo service: %d ms; maior leitura %d bytes%n", elapsed.toMillis(),
				opened.get(1).maxRequest.get());
		assertThat(elapsed).isLessThanOrEqualTo(Duration.ofSeconds(LIMIT_SECONDS));
		assertThat(opened).as("uma abertura para checar os bytes e outra para enviar").hasSize(2);
		assertThat(opened.get(0).totalRead.get()).as("a checagem do tipo lê só o início").isLessThanOrEqualTo(64);
		assertThat(opened.get(1).totalRead.get()).as("o envio lê o arquivo todo, em streaming").isEqualTo(TEN_MB);
		assertThat(opened.get(1).maxRequest.get()).as("nenhuma leitura pede o arquivo inteiro de uma vez").isLessThanOrEqualTo(1 << 20);
		ArgumentCaptor<InputStream> handed = ArgumentCaptor.forClass(InputStream.class);
		verify(storage).save(anyString(), handed.capture(), eq("image/png"), eq(TEN_MB));
		assertThat(handed.getValue()).as("o Storage recebe o fluxo, não um byte[]").isSameAs(opened.get(1))
				.isNotInstanceOf(ByteArrayInputStream.class);
		assertThat(objectsOf(clinic)).isEqualTo(1);
		verify(storage, org.mockito.Mockito.never()).delete(any());
	}
}
