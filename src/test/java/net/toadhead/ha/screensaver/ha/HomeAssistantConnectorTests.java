package net.toadhead.ha.screensaver.ha;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import fakeha.FakeHomeAssistant;
import net.toadhead.ha.screensaver.config.ScreensaverProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class HomeAssistantConnectorTests {

	private final HomeAssistantState state = new HomeAssistantState();

	private final AtomicInteger updates = new AtomicInteger();

	private final List<Object> published = new CopyOnWriteArrayList<>();

	private FakeHomeAssistant fake;

	private HomeAssistantConnector connector;

	@AfterEach
	void stop() {
		if (this.connector != null) {
			this.connector.stop();
		}
		if (this.fake != null) {
			this.fake.close();
		}
	}

	@Test
	void connectsAuthenticatesAndLoadsStates() {
		start(List.of(FakeHomeAssistant.entity("sensor.power", "42.5", Map.of("unit_of_measurement", "W"))),
				FakeHomeAssistant.TOKEN);
		await(this.state::connected, "connected");
		assertThat(this.state.get("sensor.power")).map(EntityState::state).contains("42.5");
		assertThat(this.updates.get()).isPositive();
	}

	@Test
	void appliesStateChanges() {
		start(List.of(FakeHomeAssistant.entity("sensor.power", "1", Map.of())), FakeHomeAssistant.TOKEN);
		await(this.state::connected, "connected");
		this.fake.pushStateChange("sensor.power", "2");
		await(() -> this.state.get("sensor.power").map(EntityState::state).orElse("").equals("2"), "state change");
	}

	@Test
	void reassemblesLargeMessagesSplitAcrossFrames() {
		List<Map<String, Object>> many = new ArrayList<>();
		for (int i = 0; i < 3000; i++) {
			many.add(FakeHomeAssistant.entity("sensor.s" + i, String.valueOf(i),
					Map.of("friendly_name", "Sensor number " + i + " with a reasonably long name")));
		}
		start(many, FakeHomeAssistant.TOKEN);
		await(this.state::connected, "connected");
		assertThat(this.state.get("sensor.s0")).isPresent();
		assertThat(this.state.get("sensor.s2999")).map(EntityState::state).contains("2999");
	}

	@Test
	void reconnectsAfterConnectionDrops() throws Exception {
		start(List.of(FakeHomeAssistant.entity("sensor.power", "1", Map.of())), FakeHomeAssistant.TOKEN);
		await(this.state::connected, "connected");
		this.fake.dropConnections();
		await(() -> this.fake.authAttempts() >= 2 && this.state.connected(), "reconnected");
	}

	@Test
	void keepsRetryingWithBadToken() {
		start(List.of(), "wrong-token");
		await(() -> this.fake.authAttempts() >= 2, "second auth attempt");
		assertThat(this.state.connected()).isFalse();
	}

	@Test
	void reconnectsWhenHomeAssistantStopsAnswering() {
		start(List.of(FakeHomeAssistant.entity("sensor.power", "1", Map.of())), FakeHomeAssistant.TOKEN);
		await(this.state::connected, "connected");
		this.fake.goSilent();
		await(() -> !this.state.connected(), "silent connection detected");
		this.fake.resume();
		await(() -> this.fake.authAttempts() >= 2 && this.state.connected(), "reconnected after silence");
	}

	@Test
	void keepsAHealthyConnection() throws InterruptedException {
		start(List.of(FakeHomeAssistant.entity("sensor.power", "1", Map.of())), FakeHomeAssistant.TOKEN);
		await(this.state::connected, "connected");
		Thread.sleep(1000);
		assertThat(this.state.connected()).isTrue();
		assertThat(this.fake.authAttempts()).isEqualTo(1);
	}

	@Test
	void storesHomeAssistantsTimeZone() {
		start(List.of(), FakeHomeAssistant.TOKEN);
		await(this.state::connected, "connected");
		assertThat(this.state.timeZone()).isEqualTo(ZoneId.of("America/Denver"));
	}

	@Test
	void publishesConnectedAfterEveryStatesLoad() throws Exception {
		start(List.of(), FakeHomeAssistant.TOKEN);
		await(() -> connectedEvents() == 1, "first connected event");
		this.fake.dropConnections();
		await(() -> connectedEvents() == 2, "connected event after reconnect");
	}

	@Test
	void callsServicesAndReturnsTheirResponse() throws Exception {
		start(List.of(), FakeHomeAssistant.TOKEN);
		await(this.state::connected, "connected");
		this.fake.calendar("calendar.family", List.of(FakeHomeAssistant.event("Dentist",
				"2026-10-09T09:00:00-06:00", "2026-10-09T10:30:00-06:00")));
		ObjectNode data = JsonMapper.shared()
			.createObjectNode()
			.put("start_date_time", "2026-10-09T00:00:00-06:00")
			.put("end_date_time", "2026-10-11T00:00:00-06:00");

		JsonNode response = this.connector.callService("calendar", "get_events", target("calendar.family"), data)
			.get(5, TimeUnit.SECONDS);

		assertThat(response.path("calendar.family").path("events").get(0).path("summary").asString(""))
			.isEqualTo("Dentist");
		JsonNode call = this.fake.serviceCalls().get(0);
		assertThat(call.path("return_response").asBoolean(false)).isTrue();
		assertThat(call.path("target").path("entity_id").asString("")).isEqualTo("calendar.family");
		assertThat(call.path("service_data").path("start_date_time").asString("")).isEqualTo("2026-10-09T00:00:00-06:00");
	}

	@Test
	void serviceErrorsFailTheCall() {
		start(List.of(), FakeHomeAssistant.TOKEN);
		await(this.state::connected, "connected");
		CompletableFuture<JsonNode> reply = this.connector.callService("calendar", "get_events",
				target("calendar.nope"), JsonMapper.shared().createObjectNode());
		assertThat(reply).failsWithin(Duration.ofSeconds(5))
			.withThrowableOfType(ExecutionException.class)
			.withCauseInstanceOf(HaRequestException.class)
			.withMessageContaining("not found");
	}

	@Test
	void serviceCallsFailImmediatelyWhenNotConnected() {
		start(List.of(), "wrong-token");
		CompletableFuture<JsonNode> reply = this.connector.callService("calendar", "get_events",
				target("calendar.family"), JsonMapper.shared().createObjectNode());
		assertThat(reply).isCompletedExceptionally();
		assertThat(reply.exceptionNow()).isInstanceOf(HaRequestException.class);
	}

	private long connectedEvents() {
		return this.published.stream().filter(HomeAssistantConnected.class::isInstance).count();
	}

	private static ObjectNode target(String entityId) {
		return JsonMapper.shared().createObjectNode().put("entity_id", entityId);
	}

	private void start(List<Map<String, Object>> states, String token) {
		this.fake = FakeHomeAssistant.start(states);
		var config = new ScreensaverProperties.HomeAssistant(this.fake.uri(), token, true, Duration.ofMillis(50),
				Duration.ofMillis(200), Duration.ofMillis(100), Duration.ofMillis(300));
		this.connector = new HomeAssistantConnector(config, this.state, (event) -> {
			this.updates.incrementAndGet();
			this.published.add(event);
		}, JsonMapper.shared());
		this.connector.start();
	}

	private static void await(BooleanSupplier condition, String what) {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			try {
				Thread.sleep(20);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				fail("interrupted waiting for " + what);
			}
		}
		fail("timed out waiting for " + what);
	}

}
