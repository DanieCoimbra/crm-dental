package crm_data.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * Prova que o upload ao Supabase é em streaming: o corpo não é materializado em memória antes de ser enviado. A
 * origem dos bytes só continua produzindo depois que o servidor confirma ter recebido parte deles; se o cliente
 * tentasse ler o conteúdo inteiro antes de enviar, a origem travaria esperando um servidor que ainda não recebeu nada.
 */
class SupabaseFileStorageStreamingTest {

	private static final long TOTAL = 64L * 1024 * 1024;
	/** A origem para de produzir aqui até o servidor ter recebido {@link #MIN_RECEIVED}. */
	private static final long GATE_AT = 32L * 1024 * 1024;
	private static final long MIN_RECEIVED = 8L * 1024 * 1024;

	private HttpServer server;
	private final AtomicLong received = new AtomicLong();
	private final AtomicLong receivedAtEnd = new AtomicLong();

	@BeforeEach
	void setUp() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			byte[] buffer = new byte[64 * 1024];
			try (InputStream in = exchange.getRequestBody()) {
				int read;
				while ((read = in.read(buffer)) >= 0) {
					received.addAndGet(read);
				}
			}
			receivedAtEnd.set(received.get());
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	/** Gera zeros sem guardá-los; trava no portão até o servidor ter recebido o mínimo (ou o prazo vencer). */
	private final class GatedZeros extends InputStream {

		private long produced;
		final AtomicBoolean gateTimedOut = new AtomicBoolean();
		private boolean gatePassed;
		private final Duration gateWait;

		GatedZeros(Duration gateWait) {
			this.gateWait = gateWait;
		}

		@Override
		public int read() {
			throw new UnsupportedOperationException();
		}

		@Override
		public int read(byte[] b, int off, int len) {
			if (produced >= TOTAL) {
				return -1;
			}
			if (!gatePassed && produced >= GATE_AT) {
				long deadline = System.nanoTime() + gateWait.toNanos();
				while (received.get() < MIN_RECEIVED && System.nanoTime() < deadline) {
					Thread.onSpinWait();
				}
				gateTimedOut.set(received.get() < MIN_RECEIVED);
				gatePassed = true;
			}
			int n = (int) Math.min(len, TOTAL - produced);
			java.util.Arrays.fill(b, off, off + n, (byte) 0);
			produced += n;
			return n;
		}
	}

	@Test
	void theBodyIsSentWhileItIsStillBeingProducedNeverBufferedWhole() throws IOException {
		SupabaseFileStorage storage = new SupabaseFileStorage("http://127.0.0.1:" + server.getAddress().getPort(),
				"service-key", "exames", Duration.ofSeconds(3), Duration.ofSeconds(60));
		GatedZeros source = new GatedZeros(Duration.ofSeconds(5));
		String key = StorageKeys.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

		storage.save(key, source, "application/pdf", TOTAL);

		assertThat(source.gateTimedOut).as("o servidor recebeu bytes antes de a origem terminar de produzir").isFalse();
		assertThat(receivedAtEnd.get()).isEqualTo(TOTAL);
	}

	/** Controle: se o conteúdo fosse lido por inteiro antes do envio, o portão nunca abriria e o teste acima falharia. */
	@Test
	void controlBufferingTheWholeBodyFirstWouldTripTheGate() throws IOException {
		try (GatedZeros source = new GatedZeros(Duration.ofMillis(500))) {
			byte[] buffered = source.readAllBytes();

			assertThat(buffered).hasSize((int) TOTAL);
			assertThat(source.gateTimedOut).isTrue();
		}
	}
}
