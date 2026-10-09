package net.toadhead.ha.screensaver.stream;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import net.toadhead.ha.screensaver.HaScreensaverApplication;
import org.junit.jupiter.api.Test;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class StreamShutdownTests {

	@Test
	void closingTheApplicationEndsOpenStreamsPromptly() throws Exception {
		ConfigurableApplicationContext context = new SpringApplicationBuilder(HaScreensaverApplication.class)
			.properties("server.port=0", "spring.main.banner-mode=off")
			.run();
		int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
		HttpResponse<InputStream> response = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/stream")).build(),
					HttpResponse.BodyHandlers.ofInputStream());
		InputStream body = response.body();
		assertThat(body.read()).isNotEqualTo(-1);
		CompletableFuture<Void> drained = CompletableFuture.runAsync(() -> {
			try {
				body.transferTo(OutputStreamSink.INSTANCE);
			}
			catch (Exception ex) {
				// Connection closed by the server: also fine.
			}
		});

		long started = System.nanoTime();
		context.close();
		Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

		assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
		drained.get(5, TimeUnit.SECONDS);
	}

	private static final class OutputStreamSink extends java.io.OutputStream {

		static final OutputStreamSink INSTANCE = new OutputStreamSink();

		@Override
		public void write(int b) {
		}

	}

}
