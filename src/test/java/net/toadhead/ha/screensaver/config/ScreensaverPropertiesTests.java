package net.toadhead.ha.screensaver.config;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ScreensaverPropertiesTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class)
		.withPropertyValues("screensaver.home-assistant.url=ws://ha.test:8123/api/websocket",
				"screensaver.home-assistant.token=secret");

	@Test
	void bindsDefaults() {
		this.runner.run((context) -> {
			ScreensaverProperties properties = context.getBean(ScreensaverProperties.class);
			assertThat(properties.homeAssistant().enabled()).isTrue();
			assertThat(properties.homeAssistant().initialBackoff()).isEqualTo(Duration.ofSeconds(1));
			assertThat(properties.homeAssistant().maxBackoff()).isEqualTo(Duration.ofSeconds(60));
			assertThat(properties.clock().use24Hour()).isFalse();
			assertThat(properties.snakes().guestCount()).isZero();
			assertThat(properties.snakes().minLength()).isEqualTo(4);
			assertThat(properties.snakes().maxLength()).isEqualTo(30);
			assertThat(properties.snakes().minSpeed()).isEqualTo(2.0);
			assertThat(properties.snakes().maxSpeed()).isEqualTo(20.0);
			assertThat(properties.snakes().pinned()).isEmpty();
			assertThat(properties.snakes().guests()).isEmpty();
			assertThat(properties.rotation().scenes()).isEmpty();
			assertThat(properties.kiosks()).isEmpty();
		});
	}

	@Test
	void bindsSnakesClockRotationAndKiosks() {
		List<String> props = new ArrayList<>();
		props.add("screensaver.clock.use-24-hour=true");
		props.add("screensaver.snakes.guest-count=1");
		props.addAll(snake("pinned", "internet-download", "color=light-green"));
		props.addAll(snake("pinned", "house-power", "unit=kW", "precision=0"));
		props.addAll(snake("guests", "wind"));
		props.add("screensaver.rotation.scenes.snakes.weight=3");
		props.add("screensaver.rotation.scenes.clock.duration=30s");
		props.add("screensaver.kiosks.bedroom.scenes.snakes.enabled=false");
		this.runner.withPropertyValues(props.toArray(String[]::new)).run((context) -> {
			ScreensaverProperties properties = context.getBean(ScreensaverProperties.class);
			assertThat(properties.clock().use24Hour()).isTrue();
			assertThat(properties.snakes().guestCount()).isEqualTo(1);
			assertThat(properties.snakes().pinned()).containsOnlyKeys("internet-download", "house-power");
			ScreensaverProperties.Snake download = properties.snakes().pinned().get("internet-download");
			assertThat(download.entity()).isEqualTo("sensor.internet-download");
			assertThat(download.color()).isEqualTo(TextModeColor.LIGHT_GREEN);
			assertThat(download.precision()).isEqualTo(1);
			assertThat(properties.snakes().pinned().get("house-power").unit()).isEqualTo("kW");
			assertThat(properties.snakes().pinned().get("house-power").precision()).isZero();
			assertThat(properties.rotation().scenes().get(SceneType.SNAKES).weight()).isEqualTo(3.0);
			assertThat(properties.rotation().scenes().get(SceneType.CLOCK).duration()).isEqualTo(Duration.ofSeconds(30));
			assertThat(properties.kiosks().get("bedroom").scenes().get(SceneType.SNAKES).enabled()).isFalse();
		});
	}

	@Test
	void rejectsBlankToken() {
		assertFails("homeAssistant.token", "screensaver.home-assistant.token=");
	}

	@Test
	void rejectsSnakeWithMinNotBelowMax() {
		assertFails("min must be less than max", snake("pinned", "a", "min=10", "max=10"));
	}

	@Test
	void rejectsBlackSnake() {
		assertFails("black is the background color", snake("pinned", "a", "color=black"));
	}

	@Test
	void rejectsDuplicatePinnedColors() {
		List<String> props = new ArrayList<>(snake("pinned", "a", "color=yellow"));
		props.addAll(snake("pinned", "b", "color=yellow"));
		assertFails("pinned snakes must have distinct colors", props);
	}

	@Test
	void rejectsGuestCountLargerThanPool() {
		List<String> props = new ArrayList<>(snake("guests", "a"));
		props.add("screensaver.snakes.guest-count=2");
		assertFails("guest-count must not exceed the number of guest snakes", props);
	}

	@Test
	void rejectsMoreSnakesThanColors() {
		List<String> props = new ArrayList<>();
		for (int i = 0; i < 16; i++) {
			props.addAll(snake("pinned", "s" + i));
		}
		assertFails("must not exceed 15", props);
	}

	@Test
	void rejectsSnakeIdUsedAsPinnedAndGuest() {
		List<String> props = new ArrayList<>(snake("pinned", "a"));
		props.addAll(snake("guests", "a"));
		assertFails("snake ids must be unique across pinned and guests", props);
	}

	@Test
	void rejectsPrecisionAboveFour() {
		assertFails("must be less than or equal to 4", snake("pinned", "a", "precision=5"));
	}

	@Test
	void rejectsUnknownSceneType() {
		assertFails("screensaver.rotation.scenes", "screensaver.rotation.scenes.photos.weight=1");
	}

	@Test
	void rejectsNonPositiveWeight() {
		assertFails("must be greater than 0", "screensaver.rotation.scenes.clock.weight=0");
	}

	@Test
	void rejectsNonPositiveDuration() {
		assertFails("duration must be positive", "screensaver.kiosks.hall.scenes.clock.duration=0s");
	}

	@Test
	void bindsCalendarDefaults() {
		this.runner.run((context) -> {
			ScreensaverProperties properties = context.getBean(ScreensaverProperties.class);
			assertThat(properties.calendar().allDayUntil()).isEqualTo(LocalTime.of(18, 0));
			assertThat(properties.calendar().calendars()).isEmpty();
		});
	}

	@Test
	void bindsCalendarsInConfigurationOrder() {
		List<String> props = new ArrayList<>(calendar("family", "color=lavender"));
		props.addAll(calendar("school"));
		props.add("screensaver.calendar.all-day-until=17:30");
		props.add("screensaver.rotation.scenes.calendar.weight=4");
		this.runner.withPropertyValues(props.toArray(String[]::new)).run((context) -> {
			ScreensaverProperties properties = context.getBean(ScreensaverProperties.class);
			assertThat(properties.calendar().allDayUntil()).isEqualTo(LocalTime.of(17, 30));
			assertThat(properties.calendar().calendars()).containsOnlyKeys("family", "school");
			assertThat(properties.calendar().calendars().keySet()).containsExactly("family", "school");
			ScreensaverProperties.CalendarEntry family = properties.calendar().calendars().get("family");
			assertThat(family.entity()).isEqualTo("calendar.family");
			assertThat(family.label()).isEqualTo("Calendar family");
			assertThat(family.color()).isEqualTo(CalendarColor.LAVENDER);
			assertThat(properties.calendar().calendars().get("school").color()).isNull();
			assertThat(properties.rotation().scenes().get(SceneType.CALENDAR).weight()).isEqualTo(4.0);
		});
	}

	@Test
	void rejectsDuplicateCalendarColors() {
		List<String> props = new ArrayList<>(calendar("a", "color=teal"));
		props.addAll(calendar("b", "color=teal"));
		assertFails("calendars must have distinct colors", props);
	}

	@Test
	void rejectsMoreCalendarsThanColors() {
		List<String> props = new ArrayList<>();
		for (int i = 0; i < 21; i++) {
			props.addAll(calendar("c" + i));
		}
		assertFails("at most 20 calendars", props);
	}

	@Test
	void rejectsUnknownCalendarColor() {
		assertFails("CalendarColor", calendar("a", "color=indigo"));
	}

	@Test
	void rejectsCalendarWithoutEntity() {
		assertFails("must not be blank", calendar("a", "entity="));
	}

	private static List<String> calendar(String id, String... overrides) {
		String prefix = "screensaver.calendar.calendars." + id + ".";
		List<String> props = new ArrayList<>(List.of(prefix + "entity=calendar." + id, prefix + "label=Calendar " + id));
		for (String override : overrides) {
			props.add(prefix + override);
		}
		return props;
	}

	private void assertFails(String expected, List<String> properties) {
		assertFails(expected, properties.toArray(String[]::new));
	}

	private void assertFails(String expected, String... properties) {
		this.runner.withPropertyValues(properties).run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining(expected);
		});
	}

	private static List<String> snake(String group, String id, String... overrides) {
		String prefix = "screensaver.snakes." + group + "." + id + ".";
		List<String> props = new ArrayList<>(List.of(prefix + "entity=sensor." + id, prefix + "label=Snake " + id,
				prefix + "min=0", prefix + "max=100"));
		for (String override : overrides) {
			props.add(prefix + override);
		}
		return props;
	}

	@EnableConfigurationProperties(ScreensaverProperties.class)
	static class Config {

	}

}
