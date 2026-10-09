package net.toadhead.ha.screensaver.stream;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import net.toadhead.ha.screensaver.ha.HomeAssistantState;
import net.toadhead.ha.screensaver.ha.HomeAssistantUpdated;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "screensaver.snakes.pinned.power.entity=sensor.power",
				"screensaver.snakes.pinned.power.label=Power", "screensaver.snakes.pinned.power.min=0",
				"screensaver.snakes.pinned.power.max=100",
				// Much shorter than the test's wait, to prove the stream ignores it.
				"spring.mvc.async.request-timeout=1s" })
class StreamControllerTests {

	@LocalServerPort
	int port;

	@Autowired
	HomeAssistantState ha;

	@Autowired
	ApplicationEventPublisher events;

	private InputStream body;

	@AfterEach
	void close() throws Exception {
		if (this.body != null) {
			this.body.close();
		}
	}

	@Test
	void streamsConfigThenStateThenUpdatesAndOutlivesTheAsyncTimeout() throws Exception {
		BlockingQueue<String> lines = open("/api/stream?kiosk=kitchen");

		Event config = nextEvent(lines);
		assertThat(config.name()).isEqualTo("config");
		assertThat(config.data()).contains("\"kiosk\":\"kitchen\"").contains("\"type\":\"snakes\"");

		Event initial = nextEvent(lines);
		assertThat(initial.name()).isEqualTo("state");
		assertThat(initial.data()).contains("\"haConnected\":false");

		update("42.5");
		Event changed = nextEvent(lines);
		assertThat(changed.name()).isEqualTo("state");
		assertThat(changed.data()).contains("\"value\":42.5").contains("\"unit\":\"W\"");

		Thread.sleep(2500);
		update("43");
		Event later = nextEvent(lines);
		assertThat(later.data()).contains("\"value\":43.0");
	}

	private void update(String value) {
		this.ha.markConnected(
				List.of(WorldStateAssemblerTests.entity("sensor.power", value, "{\"unit_of_measurement\":\"W\"}")));
		this.events.publishEvent(new HomeAssistantUpdated());
	}

	private BlockingQueue<String> open(String path) throws Exception {
		HttpResponse<InputStream> response = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path)).build(),
					HttpResponse.BodyHandlers.ofInputStream());
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				(type) -> assertThat(type).startsWith("text/event-stream"));
		this.body = response.body();
		BlockingQueue<String> lines = new LinkedBlockingQueue<>();
		Thread.ofVirtual().start(() -> {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(this.body, StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					lines.add(line);
				}
			}
			catch (Exception ex) {
				// Stream closed at the end of the test.
			}
		});
		return lines;
	}

	/** Reads the next named event, skipping heartbeat comments. */
	private static Event nextEvent(BlockingQueue<String> lines) throws InterruptedException {
		String name = null;
		StringBuilder data = new StringBuilder();
		while (true) {
			String line = lines.poll(5, TimeUnit.SECONDS);
			assertThat(line).as("next SSE line").isNotNull();
			if (line.isEmpty()) {
				if (name != null) {
					return new Event(name, data.toString());
				}
				continue;
			}
			if (line.startsWith("event:")) {
				name = line.substring("event:".length()).trim();
			}
			else if (line.startsWith("data:")) {
				data.append(line.substring("data:".length()));
			}
		}
	}

	record Event(String name, String data) {

	}

}
