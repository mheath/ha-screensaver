package net.toadhead.ha.screensaver.ha;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class HomeAssistantStateTests {

	private final HomeAssistantState state = new HomeAssistantState();

	@Test
	void startsDisconnectedAndEmpty() {
		assertThat(this.state.connected()).isFalse();
		assertThat(this.state.get("sensor.a")).isEmpty();
	}

	@Test
	void markConnectedReplacesAllStates() {
		this.state.markConnected(List.of(entity("sensor.a", "1"), entity("sensor.b", "2")));
		this.state.markConnected(List.of(entity("sensor.b", "3")));
		assertThat(this.state.connected()).isTrue();
		assertThat(this.state.get("sensor.a")).isEmpty();
		assertThat(this.state.get("sensor.b")).map(EntityState::state).contains("3");
	}

	@Test
	void keepsLastKnownStatesWhileDisconnected() {
		this.state.markConnected(List.of(entity("sensor.a", "1")));
		this.state.markDisconnected();
		assertThat(this.state.connected()).isFalse();
		assertThat(this.state.get("sensor.a")).isPresent();
	}

	@Test
	void putsAndRemovesSingleStates() {
		this.state.put(entity("sensor.a", "1"));
		this.state.put(entity("sensor.a", "2"));
		assertThat(this.state.get("sensor.a")).map(EntityState::state).contains("2");
		this.state.remove("sensor.a");
		assertThat(this.state.get("sensor.a")).isEmpty();
	}

	@Test
	void allReturnsEveryEntity() {
		this.state.markConnected(List.of(entity("sensor.a", "1"), entity("sensor.b", "2")));
		assertThat(this.state.all()).extracting(EntityState::entityId).containsExactlyInAnyOrder("sensor.a", "sensor.b");
	}

	@Test
	void registryStartsEmptyAndSurvivesReconnect() {
		assertThat(this.state.registry()).isSameAs(DeviceRegistry.EMPTY);
		DeviceRegistry registry = new DeviceRegistry(Map.of("sensor.a", "d1"), Map.of("d1", "Lock"));
		this.state.setRegistry(registry);
		this.state.markDisconnected();
		this.state.markConnected(List.of(entity("sensor.a", "1")));
		assertThat(this.state.registry()).isSameAs(registry);
	}

	@Test
	void timeZoneDefaultsToTheJvmsUntilSet() {
		HomeAssistantState state = new HomeAssistantState();
		assertThat(state.timeZone()).isEqualTo(ZoneId.systemDefault());
		state.setTimeZone(ZoneId.of("Pacific/Auckland"));
		assertThat(state.timeZone()).isEqualTo(ZoneId.of("Pacific/Auckland"));
	}

	static EntityState entity(String id, String value) {
		return new EntityState(id, value, JsonMapper.shared().createObjectNode(), Instant.EPOCH);
	}

}
