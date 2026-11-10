package net.toadhead.ha.screensaver.ha;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceRegistryTests {

	@Test
	void mapsEntitiesToDevicesAndPrefersTheUsersName() {
		DeviceRegistry registry = DeviceRegistry.fromJson(json("""
				[{"entity_id":"sensor.lock_battery","device_id":"d1"},{"entity_id":"sensor.orphan","device_id":null}]"""),
				json("""
						[{"id":"d1","name":"Schlage BE469","name_by_user":"Back door lock"},
						 {"id":"d2","name":"Motion sensor","name_by_user":null},
						 {"id":"d3","name":null,"name_by_user":""}]"""));
		assertThat(registry.deviceId("sensor.lock_battery")).contains("d1");
		assertThat(registry.deviceId("sensor.orphan")).isEmpty();
		assertThat(registry.deviceName("d1")).contains("Back door lock");
		assertThat(registry.deviceName("d2")).contains("Motion sensor");
		assertThat(registry.deviceName("d3")).isEmpty();
	}

	@Test
	void emptyKnowsNothing() {
		assertThat(DeviceRegistry.EMPTY.deviceId("sensor.lock_battery")).isEmpty();
		assertThat(DeviceRegistry.EMPTY.deviceName("d1")).isEmpty();
	}

	private static JsonNode json(String text) {
		return JsonMapper.shared().readTree(text);
	}

}
