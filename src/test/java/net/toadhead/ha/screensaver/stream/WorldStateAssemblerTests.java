package net.toadhead.ha.screensaver.stream;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.toadhead.ha.screensaver.calendar.CalendarEvent;
import net.toadhead.ha.screensaver.config.ScreensaverProperties;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.Snake;
import net.toadhead.ha.screensaver.config.TestProperties;
import net.toadhead.ha.screensaver.ha.EntityState;
import net.toadhead.ha.screensaver.ha.HomeAssistantState;
import net.toadhead.ha.screensaver.stream.WorldState.SnakeValue;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class WorldStateAssemblerTests {

	private final HomeAssistantState ha = new HomeAssistantState();

	@Test
	void numericStateBecomesValueWithUnit() {
		this.ha.markConnected(List.of(entity("sensor.power", "8.25", "{\"unit_of_measurement\":\"kW\"}")));
		WorldState state = WorldStateAssembler.assemble(properties("power", "sensor.power"), this.ha, List.of());
		assertThat(state.haConnected()).isTrue();
		assertThat(state.snakes()).containsExactly(new SnakeValue("power", 8.25, "kW", true));
	}

	@Test
	void entityWithoutUnitHasNullUnit() {
		this.ha.markConnected(List.of(entity("zone.home", "3", "{}")));
		WorldState state = WorldStateAssembler.assemble(properties("people", "zone.home"), this.ha, List.of());
		assertThat(state.snakes()).containsExactly(new SnakeValue("people", 3.0, null, true));
	}

	@Test
	void nonNumericStatesAreUnavailable() {
		for (String raw : List.of("unavailable", "unknown", "on", "", "NaN", "Infinity")) {
			this.ha.markConnected(List.of(entity("sensor.x", raw, "{}")));
			WorldState state = WorldStateAssembler.assemble(properties("x", "sensor.x"), this.ha, List.of());
			assertThat(state.snakes()).as(raw).containsExactly(new SnakeValue("x", null, null, false));
		}
	}

	@Test
	void missingEntityIsUnavailable() {
		this.ha.markConnected(List.of());
		WorldState state = WorldStateAssembler.assemble(properties("x", "sensor.missing"), this.ha, List.of());
		assertThat(state.snakes()).containsExactly(new SnakeValue("x", null, null, false));
	}

	@Test
	void keepsLastKnownValuesButReportsDisconnected() {
		this.ha.markConnected(List.of(entity("sensor.power", "5", "{}")));
		this.ha.markDisconnected();
		WorldState state = WorldStateAssembler.assemble(properties("power", "sensor.power"), this.ha, List.of());
		assertThat(state.haConnected()).isFalse();
		assertThat(state.snakes().get(0).value()).isEqualTo(5.0);
	}

	@Test
	void listsPinnedSnakesBeforeGuests() {
		Map<String, Snake> pinned = new LinkedHashMap<>();
		pinned.put("b", TestProperties.snake("sensor.b", "B"));
		pinned.put("a", TestProperties.snake("sensor.a", "A"));
		ScreensaverProperties properties = TestProperties.withSnakes(pinned,
				Map.of("g", TestProperties.snake("sensor.g", "G")));
		WorldState state = WorldStateAssembler.assemble(properties, this.ha, List.of());
		assertThat(state.snakes()).extracting(SnakeValue::id).containsExactly("b", "a", "g");
	}

	private static ScreensaverProperties properties(String id, String entity) {
		return TestProperties.withSnakes(Map.of(id, TestProperties.snake(entity, id)), Map.of());
	}

	@Test
	void includesCalendarEvents() {
		CalendarEvent birthday = new CalendarEvent("Birthday", "2026-10-09", "2026-10-10", true, "family");
		WorldState state = WorldStateAssembler.assemble(properties("power", "sensor.power"), this.ha, List.of(birthday));
		assertThat(state.calendar().events()).containsExactly(birthday);
	}

	@Test
	void serializesCalendarEventsForTheBrowser() {
		CalendarEvent soccer = new CalendarEvent("Soccer", "2026-10-09T15:00:00-06:00", "2026-10-09T17:00:00-06:00",
				false, "school");
		WorldState state = WorldStateAssembler.assemble(properties("power", "sensor.power"), this.ha, List.of(soccer));
		JsonNode json = JsonMapper.shared().readTree(JsonMapper.shared().writeValueAsString(state));
		JsonNode event = json.path("calendar").path("events").get(0);
		assertThat(event.path("title").asString("")).isEqualTo("Soccer");
		assertThat(event.path("start").asString("")).isEqualTo("2026-10-09T15:00:00-06:00");
		assertThat(event.path("end").asString("")).isEqualTo("2026-10-09T17:00:00-06:00");
		assertThat(event.path("allDay").asBoolean(true)).isFalse();
		assertThat(event.path("calendar").asString("")).isEqualTo("school");
	}

	static EntityState entity(String id, String state, String attributesJson) {
		return new EntityState(id, state, JsonMapper.shared().readTree(attributesJson), Instant.EPOCH);
	}

}
