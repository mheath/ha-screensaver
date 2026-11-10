package net.toadhead.ha.screensaver.batteries;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import net.toadhead.ha.screensaver.batteries.LowBattery.Status;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.Batteries;
import net.toadhead.ha.screensaver.ha.DeviceRegistry;
import net.toadhead.ha.screensaver.ha.EntityState;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class LowBatteryDetectorTests {

	private static final Instant NOW = Instant.parse("2026-11-10T20:00:00Z");

	private static final Batteries SETTINGS = new Batteries(10, Duration.ofHours(1), List.of());

	@Test
	void lowPercentageSensorIsListedWithItsFriendlyName() {
		assertThat(detect(percent("sensor.kitchen_motion_battery", "3", "Kitchen motion battery")))
			.containsExactly(new LowBattery("sensor.kitchen_motion_battery", "Kitchen motion battery", Status.LOW, 3));
	}

	@Test
	void thresholdIsStrictAndLevelRoundsDown() {
		assertThat(detect(percent("sensor.a", "10", "A"))).isEmpty();
		assertThat(detect(percent("sensor.a", "9.6", "A"))).extracting(LowBattery::level).containsExactly(9);
	}

	@Test
	void ignoresEntitiesThatAreNotPercentageBatteries() {
		assertThat(detect(entity("sensor.temp", "3", """
				{"device_class":"temperature","unit_of_measurement":"%","friendly_name":"Temp"}"""))).isEmpty();
		assertThat(detect(entity("sensor.volts", "3", """
				{"device_class":"battery","unit_of_measurement":"V","friendly_name":"Volts"}"""))).isEmpty();
		assertThat(detect(entity("sensor.plain", "3", """
				{"unit_of_measurement":"%","friendly_name":"Plain"}"""))).isEmpty();
		assertThat(detect(entity("light.lamp", "3", """
				{"device_class":"battery","unit_of_measurement":"%","friendly_name":"Lamp"}"""))).isEmpty();
	}

	@Test
	void lowBinarySensorHasNoLevel() {
		assertThat(detect(binary("binary_sensor.remote_battery", "on", "Hall remote battery")))
			.containsExactly(new LowBattery("binary_sensor.remote_battery", "Hall remote battery", Status.LOW, null));
		assertThat(detect(binary("binary_sensor.remote_battery", "off", "Hall remote battery"))).isEmpty();
	}

	@Test
	void unavailableIsDeadOnlyAfterDeadAfter() {
		assertThat(detect(percent("sensor.a", "unavailable", "A", NOW.minus(Duration.ofHours(1)))))
			.containsExactly(new LowBattery("sensor.a", "A", Status.DEAD, null));
		assertThat(detect(percent("sensor.a", "unavailable", "A", NOW.minus(Duration.ofMinutes(59))))).isEmpty();
		assertThat(detect(binary("binary_sensor.b", "unavailable", "B", NOW.minus(Duration.ofHours(1)))))
			.containsExactly(new LowBattery("binary_sensor.b", "B", Status.DEAD, null));
		assertThat(detect(binary("binary_sensor.b", "unavailable", "B", NOW.minus(Duration.ofMinutes(59))))).isEmpty();
	}

	@Test
	void unavailableWithoutLastChangedIsNotDead() {
		assertThat(detect(percent("sensor.a", "unavailable", "A", null))).isEmpty();
	}

	@Test
	void unknownAndNonNumericStatesAreIgnored() {
		for (String state : List.of("unknown", "abc", "NaN", "Infinity", "")) {
			assertThat(detect(percent("sensor.a", state, "A"))).as(state).isEmpty();
		}
	}

	@Test
	void excludedEntitiesAreNeverListed() {
		Batteries settings = new Batteries(10, Duration.ofHours(1), List.of("sensor.low", "sensor.dead"));
		assertThat(LowBatteryDetector.detect(
				List.of(percent("sensor.low", "3", "Low"),
						percent("sensor.dead", "unavailable", "Dead", NOW.minus(Duration.ofHours(5)))),
				DeviceRegistry.EMPTY, settings, NOW))
			.isEmpty();
	}

	@Test
	void namesComeFromTheDevice() {
		DeviceRegistry registry = new DeviceRegistry(Map.of("sensor.lock_battery", "d1"), Map.of("d1", "Back door lock"));
		assertThat(LowBatteryDetector.detect(List.of(percent("sensor.lock_battery", "5", "Lock battery")), registry,
				SETTINGS, NOW))
			.containsExactly(new LowBattery("d1", "Back door lock", Status.LOW, 5));
	}

	@Test
	void namesFallBackWithoutRegistry() {
		assertThat(detect(percent("sensor.a", "5", "Friendly"))).extracting(LowBattery::name)
			.containsExactly("Friendly");
		assertThat(detect(entity("sensor.nameless", "5", """
				{"device_class":"battery","unit_of_measurement":"%"}"""))).extracting(LowBattery::name)
			.containsExactly("sensor.nameless");
		DeviceRegistry registry = new DeviceRegistry(Map.of("sensor.a", "d9"), Map.of());
		assertThat(LowBatteryDetector.detect(List.of(percent("sensor.a", "5", "Friendly")), registry, SETTINGS, NOW))
			.containsExactly(new LowBattery("d9", "Friendly", Status.LOW, 5));
	}

	@Test
	void oneEntryPerDevice() {
		DeviceRegistry registry = new DeviceRegistry(
				Map.of("sensor.s1", "d1", "binary_sensor.b1", "d1", "sensor.s2", "d2", "binary_sensor.b2", "d2",
						"sensor.s3", "d3", "binary_sensor.b3", "d3"),
				Map.of("d1", "One", "d2", "Two", "d3", "Three"));
		Instant twoHoursAgo = NOW.minus(Duration.ofHours(2));
		List<EntityState> states = List.of(percent("sensor.s1", "5", "S1"), binary("binary_sensor.b1", "on", "B1"),
				percent("sensor.s2", "unavailable", "S2", twoHoursAgo), binary("binary_sensor.b2", "on", "B2"),
				percent("sensor.s3", "50", "S3"), binary("binary_sensor.b3", "on", "B3"));
		assertThat(LowBatteryDetector.detect(states, registry, SETTINGS, NOW)).containsExactly(
				new LowBattery("d2", "Two", Status.DEAD, null), new LowBattery("d3", "Three", Status.LOW, null),
				new LowBattery("d1", "One", Status.LOW, 5));
	}

	@Test
	void ordersDeadThenLevellessLowThenAscendingLevel() {
		Instant twoHoursAgo = NOW.minus(Duration.ofHours(2));
		List<EntityState> states = List.of(percent("sensor.1", "unavailable", "office window", twoHoursAgo),
				percent("sensor.2", "unavailable", "Back door lock", twoHoursAgo),
				binary("binary_sensor.3", "on", "Hall remote"), percent("sensor.4", "0", "Kitchen motion"),
				percent("sensor.5", "7", "basement leak"), percent("sensor.6", "7", "Attic"),
				percent("sensor.7", "3", "Porch"));
		assertThat(LowBatteryDetector.detect(states, DeviceRegistry.EMPTY, SETTINGS, NOW))
			.extracting(LowBattery::name)
			.containsExactly("Back door lock", "office window", "Hall remote", "Kitchen motion", "Porch", "Attic",
					"basement leak");
	}

	private static List<LowBattery> detect(EntityState... states) {
		return LowBatteryDetector.detect(Arrays.asList(states), DeviceRegistry.EMPTY, SETTINGS, NOW);
	}

	private static EntityState percent(String id, String state, String friendlyName) {
		return percent(id, state, friendlyName, NOW.minus(Duration.ofHours(2)));
	}

	private static EntityState percent(String id, String state, String friendlyName, Instant lastChanged) {
		return new EntityState(id, state, json("""
				{"device_class":"battery","unit_of_measurement":"%%","friendly_name":"%s"}""".formatted(friendlyName)),
				lastChanged);
	}

	private static EntityState binary(String id, String state, String friendlyName) {
		return binary(id, state, friendlyName, NOW.minus(Duration.ofHours(2)));
	}

	private static EntityState binary(String id, String state, String friendlyName, Instant lastChanged) {
		return new EntityState(id, state,
				json("""
						{"device_class":"battery","friendly_name":"%s"}""".formatted(friendlyName)),
				lastChanged);
	}

	private static EntityState entity(String id, String state, String attributes) {
		return new EntityState(id, state, json(attributes), NOW.minus(Duration.ofHours(2)));
	}

	private static tools.jackson.databind.JsonNode json(String json) {
		return JsonMapper.shared().readTree(json);
	}

}
