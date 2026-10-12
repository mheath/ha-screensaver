package net.toadhead.ha.screensaver.kiosk;

import java.time.Duration;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

import net.toadhead.ha.screensaver.config.CalendarColor;
import net.toadhead.ha.screensaver.config.SceneType;
import net.toadhead.ha.screensaver.config.ScreensaverProperties;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.Calendar;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.CalendarEntry;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.Clock;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.Kiosk;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.Rotation;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.SceneSettings;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.Snake;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.Snakes;
import net.toadhead.ha.screensaver.config.TestProperties;
import net.toadhead.ha.screensaver.config.TextModeColor;
import net.toadhead.ha.screensaver.kiosk.KioskConfig.SceneConfig;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class KioskConfigServiceTests {

	@Test
	void usesSceneTypeDefaultsWhenNothingIsConfigured() {
		KioskConfig config = service(Map.of(), Map.of()).resolve("kitchen");
		assertThat(config.kiosk()).isEqualTo("kitchen");
		assertThat(config.scenes()).containsExactly(new SceneConfig(SceneType.CLOCK, 2, 20_000),
				new SceneConfig(SceneType.SNAKES, 2, 45_000), new SceneConfig(SceneType.CALENDAR, 2, 20_000));
	}

	@Test
	void blankKioskIdMeansDefault() {
		assertThat(service(Map.of(), Map.of()).resolve(" ").kiosk()).isEqualTo("default");
		assertThat(service(Map.of(), Map.of()).resolve(null).kiosk()).isEqualTo("default");
	}

	@Test
	void rotationSettingsReplaceDefaults() {
		var rotation = Map.of(SceneType.SNAKES, new SceneSettings(null, 5.0, Duration.ofSeconds(60)));
		KioskConfig config = service(rotation, Map.of()).resolve("kitchen");
		assertThat(config.scenes()).contains(new SceneConfig(SceneType.SNAKES, 5, 60_000));
	}

	@Test
	void kioskOverrideReplacesOnlyTheFieldsItSets() {
		var rotation = Map.of(SceneType.SNAKES, new SceneSettings(null, 5.0, Duration.ofSeconds(60)));
		var kiosks = Map.of("garage", new Kiosk(Map.of(SceneType.SNAKES, new SceneSettings(null, 1.0, null))));
		KioskConfig config = service(rotation, kiosks).resolve("garage");
		assertThat(config.scenes()).contains(new SceneConfig(SceneType.SNAKES, 1, 60_000));
	}

	@Test
	void kioskCanDisableAScene() {
		var kiosks = Map.of("bedroom", new Kiosk(Map.of(SceneType.SNAKES, new SceneSettings(false, null, null))));
		KioskConfig config = service(Map.of(), kiosks).resolve("bedroom");
		assertThat(config.scenes()).extracting(SceneConfig::type).containsExactly(SceneType.CLOCK, SceneType.CALENDAR);
	}

	@Test
	void sceneDisabledInRotationStaysDisabledWhenKioskOnlyChangesWeight() {
		var rotation = Map.of(SceneType.SNAKES, new SceneSettings(false, null, null));
		var kiosks = Map.of("hall", new Kiosk(Map.of(SceneType.SNAKES, new SceneSettings(null, 9.0, null))));
		KioskConfig config = service(rotation, kiosks).resolve("hall");
		assertThat(config.scenes()).extracting(SceneConfig::type).containsExactly(SceneType.CLOCK, SceneType.CALENDAR);
	}

	@Test
	void unknownKioskGetsTheDefaults() {
		var kiosks = Map.of("bedroom", new Kiosk(Map.of(SceneType.SNAKES, new SceneSettings(false, null, null))));
		KioskConfig config = service(Map.of(), kiosks).resolve("somewhere-else");
		assertThat(config.scenes()).hasSize(3);
	}

	@Test
	void includesClockAndSnakeOptionsInConfigOrder() {
		Map<String, Snake> pinned = new LinkedHashMap<>();
		pinned.put("internet-download",
				new Snake("sensor.wan", "Internet download bandwidth", 0.0, 1000.0, TextModeColor.LIGHT_GREEN, null, 1));
		pinned.put("house-power", new Snake("sensor.power", "House power", 0.0, 12.0, null, "kW", 0));
		Map<String, Snake> guests = Map.of("wind", TestProperties.snake("sensor.wind", "Wind speed"));
		var properties = new ScreensaverProperties(TestProperties.homeAssistant(), new Clock(true),
				new Snakes(1, 3, 25, 1, 15, pinned, guests), TestProperties.noCalendars(), new Rotation(Map.of()),
				Map.of());

		KioskConfig config = new KioskConfigService(properties).resolve("kitchen");

		assertThat(config.clock().use24Hour()).isTrue();
		assertThat(config.snakes().guestCount()).isEqualTo(1);
		assertThat(config.snakes().minLength()).isEqualTo(3);
		assertThat(config.snakes().maxSpeed()).isEqualTo(15.0);
		assertThat(config.snakes().pinned()).containsExactly(
				new KioskConfig.SnakeDef("internet-download", "Internet download bandwidth", TextModeColor.LIGHT_GREEN,
						null, 1, 0, 1000),
				new KioskConfig.SnakeDef("house-power", "House power", null, "kW", 0, 0, 12));
		assertThat(config.snakes().guests()).extracting(KioskConfig.SnakeDef::id).containsExactly("wind");
	}

	@Test
	void includesCalendarsWithResolvedColorsInConfigOrder() {
		Map<String, CalendarEntry> calendars = new LinkedHashMap<>();
		calendars.put("family", new CalendarEntry("calendar.family", "Family", null));
		calendars.put("school", new CalendarEntry("calendar.school", "School", CalendarColor.YELLOW));
		var properties = new ScreensaverProperties(TestProperties.homeAssistant(), new Clock(false),
				new Snakes(0, 4, 30, 2, 20, Map.of(), Map.of()), new Calendar(LocalTime.of(17, 5), calendars),
				new Rotation(Map.of()), Map.of());

		KioskConfig config = new KioskConfigService(properties).resolve("kitchen");

		assertThat(config.calendar().allDayUntil()).isEqualTo("17:05");
		assertThat(config.calendar().calendars()).containsExactly(
				new KioskConfig.CalendarDef("family", "Family", "#3b82f6", "#ffffff"),
				new KioskConfig.CalendarDef("school", "School", "#facc15", "#1f1a05"));
		JsonNode json = JsonMapper.shared().readTree(JsonMapper.shared().writeValueAsString(config));
		assertThat(json.path("calendar").path("allDayUntil").asString("")).isEqualTo("17:05");
		assertThat(json.path("calendar").path("calendars").get(1).path("textColor").asString("")).isEqualTo("#1f1a05");
	}

	private static KioskConfigService service(Map<SceneType, SceneSettings> rotation, Map<String, Kiosk> kiosks) {
		var properties = new ScreensaverProperties(TestProperties.homeAssistant(), new Clock(false),
				new Snakes(0, 4, 30, 2, 20, Map.of(), Map.of()), TestProperties.noCalendars(), new Rotation(rotation),
				kiosks);
		return new KioskConfigService(properties);
	}

}
