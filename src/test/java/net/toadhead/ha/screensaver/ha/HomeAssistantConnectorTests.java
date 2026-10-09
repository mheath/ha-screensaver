package net.toadhead.ha.screensaver.ha;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import fakeha.FakeHomeAssistant;
import net.toadhead.ha.screensaver.config.ScreensaverProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class HomeAssistantConnectorTests {

	private final HomeAssistantState state = new HomeAssistantState();

	private final AtomicInteger updates = new AtomicInteger();

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

	private void start(List<Map<String, Object>> states, String token) {
		this.fake = FakeHomeAssistant.start(states);
		var config = new ScreensaverProperties.HomeAssistant(this.fake.uri(), token, true, Duration.ofMillis(50),
				Duration.ofMillis(200));
		this.connector = new HomeAssistantConnector(config, this.state, (event) -> this.updates.incrementAndGet(),
				JsonMapper.shared());
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
