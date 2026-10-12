package net.toadhead.ha.screensaver.kiosk;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import net.toadhead.ha.screensaver.config.CalendarColor;
import net.toadhead.ha.screensaver.config.SceneType;
import net.toadhead.ha.screensaver.config.ScreensaverProperties;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.Kiosk;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.SceneSettings;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.Snake;
import net.toadhead.ha.screensaver.kiosk.KioskConfig.CalendarDef;
import net.toadhead.ha.screensaver.kiosk.KioskConfig.CalendarOptions;
import net.toadhead.ha.screensaver.kiosk.KioskConfig.ClockOptions;
import net.toadhead.ha.screensaver.kiosk.KioskConfig.SceneConfig;
import net.toadhead.ha.screensaver.kiosk.KioskConfig.SnakeDef;
import net.toadhead.ha.screensaver.kiosk.KioskConfig.SnakeOptions;

import org.springframework.stereotype.Service;

/**
 * Merges the default rotation with a kiosk's overrides.
 */
@Service
public class KioskConfigService {

	static final String DEFAULT_KIOSK = "default";

	private final ScreensaverProperties properties;

	private final CalendarOptions calendarOptions;

	public KioskConfigService(ScreensaverProperties properties) {
		this.properties = properties;
		this.calendarOptions = calendarOptions(properties.calendar());
	}

	public KioskConfig resolve(String kioskId) {
		String id = (kioskId == null || kioskId.isBlank()) ? DEFAULT_KIOSK : kioskId;
		Kiosk kiosk = this.properties.kiosks().get(id);
		Map<SceneType, SceneSettings> overrides = (kiosk != null) ? kiosk.scenes() : Map.of();
		return new KioskConfig(id, scenes(overrides), new ClockOptions(this.properties.clock().use24Hour()),
				snakeOptions(), this.calendarOptions);
	}

	private List<SceneConfig> scenes(Map<SceneType, SceneSettings> overrides) {
		List<SceneConfig> scenes = new ArrayList<>();
		for (SceneType type : SceneType.values()) {
			SceneSettings override = overrides.get(type);
			SceneSettings base = this.properties.rotation().scenes().get(type);
			if (!pick(SceneSettings::enabled, true, override, base)) {
				continue;
			}
			double weight = pick(SceneSettings::weight, type.defaultWeight(), override, base);
			Duration duration = pick(SceneSettings::duration, type.defaultDuration(), override, base);
			scenes.add(new SceneConfig(type, weight, duration.toMillis()));
		}
		return scenes;
	}

	private SnakeOptions snakeOptions() {
		var snakes = this.properties.snakes();
		return new SnakeOptions(snakes.guestCount(), snakes.minLength(), snakes.maxLength(), snakes.minSpeed(),
				snakes.maxSpeed(), defs(snakes.pinned()), defs(snakes.guests()));
	}

	private static CalendarOptions calendarOptions(ScreensaverProperties.Calendar calendar) {
		List<CalendarDef> calendars = CalendarColor.assign(calendar.calendars())
			.entrySet()
			.stream()
			.map((entry) -> new CalendarDef(entry.getKey(), calendar.calendars().get(entry.getKey()).label(),
					entry.getValue().background(), entry.getValue().text()))
			.toList();
		return new CalendarOptions(calendar.allDayUntil().format(DateTimeFormatter.ofPattern("HH:mm")), calendars);
	}

	private static List<SnakeDef> defs(Map<String, Snake> snakes) {
		return snakes.entrySet()
			.stream()
			.map((entry) -> {
				Snake snake = entry.getValue();
				return new SnakeDef(entry.getKey(), snake.label(), snake.color(), snake.unit(), snake.precision(),
						snake.min(), snake.max());
			})
			.toList();
	}

	/**
	 * Returns the first non-null value of a field across the layers, most specific
	 * first.
	 */
	private static <T> T pick(Function<SceneSettings, T> field, T fallback, SceneSettings... layers) {
		for (SceneSettings layer : layers) {
			if (layer != null) {
				T value = field.apply(layer);
				if (value != null) {
					return value;
				}
			}
		}
		return fallback;
	}

}
