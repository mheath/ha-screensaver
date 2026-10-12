package net.toadhead.ha.screensaver.stream;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import net.toadhead.ha.screensaver.calendar.CalendarEvent;
import net.toadhead.ha.screensaver.config.ScreensaverProperties;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.Snake;
import net.toadhead.ha.screensaver.ha.EntityState;
import net.toadhead.ha.screensaver.ha.HomeAssistantState;
import net.toadhead.ha.screensaver.stream.WorldState.SnakeValue;

/**
 * Derives {@link WorldState} from configuration and Home Assistant state.
 */
final class WorldStateAssembler {

	private WorldStateAssembler() {
	}

	static WorldState assemble(ScreensaverProperties properties, HomeAssistantState ha,
			List<CalendarEvent> calendarEvents) {
		List<SnakeValue> snakes = Stream
			.concat(properties.snakes().pinned().entrySet().stream(), properties.snakes().guests().entrySet().stream())
			.map((entry) -> snakeValue(entry, ha))
			.toList();
		return new WorldState(ha.connected(), snakes, new WorldState.Calendar(calendarEvents));
	}

	private static SnakeValue snakeValue(Map.Entry<String, Snake> entry, HomeAssistantState ha) {
		Optional<EntityState> state = ha.get(entry.getValue().entity());
		Double value = state.map(EntityState::state).map(WorldStateAssembler::parse).orElse(null);
		String unit = (value != null) ? state.flatMap((s) -> s.attribute("unit_of_measurement")).orElse(null) : null;
		return new SnakeValue(entry.getKey(), value, unit, value != null);
	}

	private static Double parse(String raw) {
		try {
			double value = Double.parseDouble(raw);
			return Double.isFinite(value) ? value : null;
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

}
