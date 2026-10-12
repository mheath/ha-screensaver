# Calendar Scene — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a calendar scene that shows today's events on a timeline, switching to tomorrow (on an unmistakable sunset background) once today is done.

**Architecture:** The backend learns Home Assistant's time zone (`get_config`), gains a service-call API with `return_response`, and polls `calendar.get_events` for today and tomorrow every 15 minutes, on connect, and after midnight. Merged events go into the world state; resolved calendar colors go into the kiosk config. The browser decides which day to show from its own clock, lays out lanes and the hour span with pure functions, and renders a modern (not text-mode) timeline.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Jackson 3), JDK `java.net.http.WebSocket`, JUnit 6 + AssertJ; TypeScript 6.0.3, Vite 8.3.1, Vitest 5.0.1.

**Spec:** `docs/superpowers/specs/2026-10-09-calendar-scene-design.md` (extends `docs/superpowers/specs/2026-10-08-ha-screensaver-design.md`).

## Global Constraints

- Base package `net.toadhead.ha.screensaver`; build with `./mvnw`. Backend-only test runs add `-Dskip.npm -Dskip.installnodenpm`.
- Spring Boot 4.1.1 uses **Jackson 3**: `tools.jackson.databind.*` (`JsonMapper`, `JsonNode`, `ObjectNode`); annotations stay `com.fasterxml.jackson.annotation.*`. Use `asString("")`, `asInt(default)`, `asBoolean(default)`; never `asText()`.
- Configuration is plain `@ConfigurationProperties("screensaver")` records, `@Validated`, flat. Changes apply on restart.
- Calendar colors, exactly, in auto-assignment order: `blue, orange, green, pink, violet, yellow, teal, red, sky, lime, fuchsia, amber, lavender, emerald, rose, cyan, purple, brown, slate, stone` (hex values in spec §3.1). At most 20 calendars; configured colors distinct.
- `all-day-until` default `18:00`. Minimum timeline span 6 hours. Short events are under 40 minutes. The "now" line moves every 30 s. Polling every 15 minutes.
- Today's background `linear-gradient(160deg, #141a3a 0%, #2a1645 100%)`; tomorrow's `linear-gradient(160deg, #3a2410 0%, #5a1f3a 100%)`; tomorrow's tag `#ffb547` with text `#2a1406`.
- The calendar scene uses a modern system sans-serif look, **not** the DOS/VGA text-mode style of snakes and clock.
- Screens never show error text.
- Every commit message ends with a blank line and then `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## Review Focus

1. **An event ending exactly at midnight** (HA end times are exclusive): it belongs to today only and must not make tomorrow look busy or keep tomorrow eligible. Pinned by the "ends at midnight" `eventsOn` test in Task 6.
2. **A timed event from yesterday that runs past midnight** (a late party, an overnight shift): it must keep today on screen until it ends, drawn from the top of today's timeline. Pinned by the "spills over midnight" `chooseDay` test in Task 6.
3. **A typo in one calendar's entity id**: HA answers with an error every poll. The other calendars must keep updating and the scene must keep working. Pinned by `oneFailingCalendarDoesNotBlockTheOthers` in Task 4.
4. **Home Assistant reporting a time zone Java doesn't know** (or none): the connection must stay up and fall back to the JVM zone instead of reconnecting forever. Pinned by `ignoresUnknownTimeZone` in Task 2.
5. **Midnight passing while the backend is up**: the next poll must fetch the new today and tomorrow, not keep yesterday's window until the 15-minute interval comes around. Pinned by `tickPollsJustAfterMidnight` in Task 4.

---

### Task 1: Calendar configuration and colors

**Files:**
- Create: `src/main/java/net/toadhead/ha/screensaver/config/CalendarColor.java`
- Modify: `src/main/java/net/toadhead/ha/screensaver/config/ScreensaverProperties.java`
- Modify: `src/main/java/net/toadhead/ha/screensaver/config/SceneType.java`
- Modify: `config/application.example.yaml`
- Modify: `src/test/java/net/toadhead/ha/screensaver/config/TestProperties.java`
- Modify: `src/test/java/net/toadhead/ha/screensaver/kiosk/KioskConfigServiceTests.java`
- Test: `src/test/java/net/toadhead/ha/screensaver/config/CalendarColorTests.java`
- Test: `src/test/java/net/toadhead/ha/screensaver/config/ScreensaverPropertiesTests.java`

**Interfaces:**
- Produces: `enum CalendarColor` with `id()`, `background()`, `text()`, and `static Map<String, CalendarColor> assign(Map<String, ScreensaverProperties.CalendarEntry> calendars)`.
- Produces: `ScreensaverProperties.calendar()` returning `record Calendar(LocalTime allDayUntil, Map<String, CalendarEntry> calendars)`; `record CalendarEntry(String entity, String label, CalendarColor color)`. The `ScreensaverProperties` constructor is now `(homeAssistant, clock, snakes, calendar, rotation, kiosks)`.
- Produces: `SceneType.CALENDAR` (weight 2, duration 20 s), id `calendar`.
- Produces: `TestProperties.noCalendars()`.

- [ ] **Step 1: Write the failing color tests**

`src/test/java/net/toadhead/ha/screensaver/config/CalendarColorTests.java`:

```java
package net.toadhead.ha.screensaver.config;

import java.util.LinkedHashMap;
import java.util.Map;

import net.toadhead.ha.screensaver.config.ScreensaverProperties.CalendarEntry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class CalendarColorTests {

	@Test
	void keepsConfiguredColorsAndFillsTheRestInOrder() {
		Map<String, CalendarEntry> calendars = new LinkedHashMap<>();
		calendars.put("family", calendar(null));
		calendars.put("school", calendar(CalendarColor.BLUE));
		calendars.put("work", calendar(null));
		assertThat(CalendarColor.assign(calendars)).containsExactly(entry("family", CalendarColor.ORANGE),
				entry("school", CalendarColor.BLUE), entry("work", CalendarColor.GREEN));
	}

	@Test
	void twentyCalendarsUseEveryColorOnce() {
		Map<String, CalendarEntry> calendars = new LinkedHashMap<>();
		for (int i = 0; i < 20; i++) {
			calendars.put("c" + i, calendar(null));
		}
		assertThat(CalendarColor.assign(calendars).values()).containsExactly(CalendarColor.values());
	}

	@Test
	void idsAreLowerCaseNames() {
		assertThat(CalendarColor.LAVENDER.id()).isEqualTo("lavender");
	}

	@Test
	void lightColorsGetDarkText() {
		assertThat(CalendarColor.YELLOW.background()).isEqualTo("#facc15");
		assertThat(CalendarColor.YELLOW.text()).isEqualTo("#1f1a05");
		assertThat(CalendarColor.BLUE.text()).isEqualTo("#ffffff");
	}

	private static CalendarEntry calendar(CalendarColor color) {
		return new CalendarEntry("calendar.x", "X", color);
	}

}
```

- [ ] **Step 2: Add the failing binding tests to `ScreensaverPropertiesTests`**

Add `import java.time.LocalTime;` and these tests and helper inside the class:

```java
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
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest='CalendarColorTests,ScreensaverPropertiesTests' -Dskip.npm -Dskip.installnodenpm`
Expected: compilation FAILURE — `CalendarColor`, `CalendarEntry`, `calendar()` do not exist.

- [ ] **Step 4: Write `CalendarColor`**

`src/main/java/net/toadhead/ha/screensaver/config/CalendarColor.java`:

```java
package net.toadhead.ha.screensaver.config;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonValue;

import net.toadhead.ha.screensaver.config.ScreensaverProperties.CalendarEntry;

/**
 * Colors for calendar events, in auto-assignment order: the most distinct come
 * first. Each pairs a block color with a text color that stays readable on it.
 * {@code lavender} stands in for indigo, which looked the same as violet to a
 * color-blind viewer.
 */
public enum CalendarColor {

	BLUE("#3b82f6", "#ffffff"), ORANGE("#f97316", "#ffffff"), GREEN("#22c55e", "#ffffff"),
	PINK("#ec4899", "#ffffff"), VIOLET("#8b5cf6", "#ffffff"), YELLOW("#facc15", "#1f1a05"),
	TEAL("#14b8a6", "#ffffff"), RED("#ef4444", "#ffffff"), SKY("#38bdf8", "#06243a"), LIME("#a3e635", "#1a2805"),
	FUCHSIA("#d946ef", "#ffffff"), AMBER("#f59e0b", "#2a1a02"), LAVENDER("#c4b5fd", "#1e1438"),
	EMERALD("#10b981", "#ffffff"), ROSE("#f43f5e", "#ffffff"), CYAN("#22d3ee", "#05282e"),
	PURPLE("#a855f7", "#ffffff"), BROWN("#a16207", "#ffffff"), SLATE("#64748b", "#ffffff"),
	STONE("#a8a29e", "#1c1917");

	private final String background;

	private final String text;

	CalendarColor(String background, String text) {
		this.background = background;
		this.text = text;
	}

	public String background() {
		return this.background;
	}

	public String text() {
		return this.text;
	}

	/**
	 * The name used in configuration.
	 * @return the id, for example {@code lavender}
	 */
	@JsonValue
	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	/**
	 * Gives every calendar a color: its configured one, or else the first color that
	 * no calendar has yet, taking calendars in configuration order.
	 * @param calendars calendars by id, in configuration order, with distinct
	 * configured colors and at most one calendar per color
	 * @return each calendar id's color, in the same order
	 */
	public static Map<String, CalendarColor> assign(Map<String, CalendarEntry> calendars) {
		Set<CalendarColor> used = EnumSet.noneOf(CalendarColor.class);
		calendars.values().stream().map(CalendarEntry::color).filter(Objects::nonNull).forEach(used::add);
		Map<String, CalendarColor> assigned = new LinkedHashMap<>();
		for (Map.Entry<String, CalendarEntry> entry : calendars.entrySet()) {
			CalendarColor color = entry.getValue().color();
			if (color == null) {
				color = Arrays.stream(values()).filter((candidate) -> !used.contains(candidate)).findFirst().orElseThrow();
				used.add(color);
			}
			assigned.put(entry.getKey(), color);
		}
		return assigned;
	}

}
```

- [ ] **Step 5: Add the calendar section to `ScreensaverProperties`**

Add imports `java.time.LocalTime` and `org.springframework.format.annotation.DateTimeFormat`.

Change the record header and its Javadoc so `calendar` sits between `snakes` and `rotation`:

```java
/**
 * All screensaver configuration, bound once at startup from the {@code screensaver}
 * prefix.
 *
 * @param homeAssistant connection to Home Assistant
 * @param clock clock scene options
 * @param snakes snakes scene options
 * @param calendar calendar scene options
 * @param rotation default weight, duration and enablement for each scene type
 * @param kiosks per-kiosk overrides keyed by the {@code ?kiosk=} id
 */
@ConfigurationProperties("screensaver")
@Validated
public record ScreensaverProperties(@Valid @NotNull HomeAssistant homeAssistant, @Valid @DefaultValue Clock clock,
		@Valid @DefaultValue Snakes snakes, @Valid @DefaultValue Calendar calendar, @Valid @DefaultValue Rotation rotation,
		Map<String, @Valid Kiosk> kiosks) {
```

Add these nested records after the `Snake` record:

```java
	/**
	 * Calendar scene options.
	 *
	 * @param allDayUntil local time after which an all-day event alone no longer keeps
	 * today on screen
	 * @param calendars calendars to show, keyed by id, in configuration order
	 */
	public record Calendar(@DefaultValue("18:00") @DateTimeFormat(pattern = "H:mm") LocalTime allDayUntil,
			Map<String, @Valid CalendarEntry> calendars) {

		public Calendar {
			// Keep the bound map as-is: its order decides automatic colors.
			calendars = (calendars != null) ? calendars : Map.of();
		}

		@AssertTrue(message = "calendars must have distinct colors")
		public boolean isColorsDistinct() {
			List<CalendarColor> colors = this.calendars.values()
				.stream()
				.map(CalendarEntry::color)
				.filter(Objects::nonNull)
				.toList();
			return colors.size() == new HashSet<>(colors).size();
		}

		@AssertTrue(message = "there can be at most 20 calendars (one per color)")
		public boolean isWithinPalette() {
			return this.calendars.size() <= CalendarColor.values().length;
		}

	}

	/**
	 * One Home Assistant calendar.
	 *
	 * @param entity the calendar entity id, for example {@code calendar.family}
	 * @param label name used in logs
	 * @param color event color, or {@code null} to assign one automatically
	 */
	public record CalendarEntry(@NotBlank String entity, @NotBlank String label, CalendarColor color) {

	}
```

- [ ] **Step 6: Add the scene type**

In `SceneType`, change the constants to:

```java
	CLOCK(2, Duration.ofSeconds(20)),

	SNAKES(2, Duration.ofSeconds(45)),

	CALENDAR(2, Duration.ofSeconds(20));
```

- [ ] **Step 7: Update the test fixtures for the new constructor parameter and scene type**

In `TestProperties`, add the import `java.time.LocalTime` and `net.toadhead.ha.screensaver.config.ScreensaverProperties.Calendar`, add this method, and pass it in `withSnakes`:

```java
	public static Calendar noCalendars() {
		return new Calendar(LocalTime.of(18, 0), Map.of());
	}

	public static ScreensaverProperties withSnakes(Map<String, Snake> pinned, Map<String, Snake> guests) {
		return new ScreensaverProperties(homeAssistant(), new Clock(false),
				new Snakes(0, 4, 30, 2, 20, pinned, guests), noCalendars(), new Rotation(Map.of()), Map.of());
	}
```

In `KioskConfigServiceTests`:

1. In both `new ScreensaverProperties(...)` calls, insert `TestProperties.noCalendars(),` between the `new Snakes(...)` argument and the `new Rotation(...)` argument.
2. `usesSceneTypeDefaultsWhenNothingIsConfigured` now expects three scenes:

```java
		assertThat(config.scenes()).containsExactly(new SceneConfig(SceneType.CLOCK, 2, 20_000),
				new SceneConfig(SceneType.SNAKES, 2, 45_000), new SceneConfig(SceneType.CALENDAR, 2, 20_000));
```

3. In `kioskCanDisableAScene` and `sceneDisabledInRotationStaysDisabledWhenKioskOnlyChangesWeight`, change `.containsExactly(SceneType.CLOCK)` to `.containsExactly(SceneType.CLOCK, SceneType.CALENDAR)`.
4. In `unknownKioskGetsTheDefaults`, change `hasSize(2)` to `hasSize(3)`.

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./mvnw -q test -Dskip.npm -Dskip.installnodenpm`
Expected: BUILD SUCCESS. If `bindsCalendarsInConfigurationOrder` fails on `17:30`, the binder ignored `@DateTimeFormat`; stop and report rather than changing the format.

- [ ] **Step 9: Document the section in `config/application.example.yaml`**

Insert after the `snakes:` block (before `rotation:`):

```yaml
  calendar:
    all-day-until: "18:00"        # an all-day event alone keeps "today" on screen until this time
    calendars:
      family:
        entity: calendar.family
        label: Family
        color: blue               # blue, orange, green, pink, violet, yellow, teal, red, sky, lime,
                                  # fuchsia, amber, lavender, emerald, rose, cyan, purple, brown, slate, stone
      school:
        entity: calendar.school
        label: School             # no color: the first unused one is assigned
```

and add `calendar: { weight: 2, duration: 20s }` under `rotation.scenes`.

- [ ] **Step 10: Commit**

```bash
git add .gitignore config/application.example.yaml src/main/java/net/toadhead/ha/screensaver/config src/test/java/net/toadhead/ha/screensaver/config src/test/java/net/toadhead/ha/screensaver/kiosk/KioskConfigServiceTests.java
git commit -F - <<'EOF'
Add calendar configuration and colors

Twenty calendar colors with readable text colors, automatic assignment in
configuration order, an all-day cutoff, and the calendar scene type.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

(`.gitignore` carries the `.superpowers/` line added during brainstorming.)

---

### Task 2: Home Assistant time zone, connected event, and service calls

**Files:**
- Create: `src/main/java/net/toadhead/ha/screensaver/ha/HomeAssistantConnected.java`
- Create: `src/main/java/net/toadhead/ha/screensaver/ha/HomeAssistantServices.java`
- Modify: `src/main/java/net/toadhead/ha/screensaver/ha/HaSession.java`
- Modify: `src/main/java/net/toadhead/ha/screensaver/ha/HomeAssistantState.java`
- Modify: `src/main/java/net/toadhead/ha/screensaver/ha/HomeAssistantConnector.java`
- Modify: `src/test/java/fakeha/FakeHomeAssistant.java`
- Test: `src/test/java/net/toadhead/ha/screensaver/ha/HaSessionTests.java`
- Test: `src/test/java/net/toadhead/ha/screensaver/ha/HomeAssistantConnectorTests.java`
- Test: `src/test/java/net/toadhead/ha/screensaver/ha/HomeAssistantStateTests.java`

**Interfaces:**
- Produces: `HaSession.Listener.onTimeZone(ZoneId zone)`. After `auth_ok` the session sends `subscribe_events` (id 1), `get_config` (id 2), `get_states` (id 3).
- Produces: `HomeAssistantState.timeZone()` (JVM default until known) and `setTimeZone(ZoneId)`.
- Produces: `record HomeAssistantConnected()`, published after every successful states load.
- Produces: `interface HomeAssistantServices { CompletableFuture<JsonNode> callService(String domain, String service, JsonNode target, JsonNode data); }`, implemented by `HomeAssistantConnector`. The future holds the reply's `response` object.
- Produces (tests): `FakeHomeAssistant.timeZone(String)`, `calendar(String entityId, List<Map<String, Object>> events)`, `serviceCalls()`, `static event(String summary, String start, String end)`.

- [ ] **Step 1: Update and add `HaSessionTests`**

Add `import java.time.ZoneId;`. Replace `subscribesThenLoadsStatesAfterAuthOk` with:

```java
	@Test
	void subscribesReadsConfigAndLoadsStatesAfterAuthOk() {
		this.session.onMessage("{\"type\":\"auth_ok\"}");
		assertThat(this.listener.authenticated).isTrue();
		assertThat(sent(0).path("type").asString("")).isEqualTo("subscribe_events");
		assertThat(sent(0).path("event_type").asString("")).isEqualTo("state_changed");
		assertThat(sent(0).path("id").asInt(-1)).isEqualTo(1);
		assertThat(sent(1).path("type").asString("")).isEqualTo("get_config");
		assertThat(sent(1).path("id").asInt(-1)).isEqualTo(2);
		assertThat(sent(2).path("type").asString("")).isEqualTo("get_states");
		assertThat(sent(2).path("id").asInt(-1)).isEqualTo(3);
	}
```

In `parsesStatesFromGetStatesResult`, `reportsFailedStateLoad`, and `reportsStatesThatCannotBeParsed`, change the reply's `"id":2` to `"id":3`.

Add:

```java
	@Test
	void reportsTimeZoneFromConfig() {
		this.session.onMessage("{\"type\":\"auth_ok\"}");
		this.session.onMessage("""
				{"id":2,"type":"result","success":true,"result":{"time_zone":"America/Denver","version":"2026.10.0"}}""");
		assertThat(this.listener.timeZone).isEqualTo(ZoneId.of("America/Denver"));
		assertThat(this.listener.setupFailures).isEmpty();
	}

	@Test
	void ignoresUnknownTimeZone() {
		this.session.onMessage("{\"type\":\"auth_ok\"}");
		this.session.onMessage("""
				{"id":2,"type":"result","success":true,"result":{"time_zone":"Mars/Olympus_Mons"}}""");
		assertThat(this.listener.timeZone).isNull();
		assertThat(this.listener.setupFailures).isEmpty();
	}

	@Test
	void failedConfigIsNotASetupFailure() {
		this.session.onMessage("{\"type\":\"auth_ok\"}");
		this.session.onMessage("""
				{"id":2,"type":"result","success":false,"error":{"code":"unknown_command","message":"Unknown command."}}""");
		assertThat(this.listener.timeZone).isNull();
		assertThat(this.listener.setupFailures).isEmpty();
	}
```

In `RecordingListener`, add:

```java
		ZoneId timeZone;

		@Override
		public void onTimeZone(ZoneId zone) {
			this.timeZone = zone;
		}
```

- [ ] **Step 2: Add a `HomeAssistantStateTests` test**

Add `import java.time.ZoneId;` and:

```java
	@Test
	void timeZoneDefaultsToTheJvmsUntilSet() {
		HomeAssistantState state = new HomeAssistantState();
		assertThat(state.timeZone()).isEqualTo(ZoneId.systemDefault());
		state.setTimeZone(ZoneId.of("Pacific/Auckland"));
		assertThat(state.timeZone()).isEqualTo(ZoneId.of("Pacific/Auckland"));
	}
```

- [ ] **Step 3: Extend `FakeHomeAssistant`**

Add imports `java.util.concurrent.CopyOnWriteArrayList`. Add these public methods to `FakeHomeAssistant`:

```java
	public void timeZone(String zone) {
		this.handler.timeZone = zone;
	}

	/** Makes {@code calendar.get_events} for {@code entityId} return these events. */
	public void calendar(String entityId, List<Map<String, Object>> events) {
		this.handler.calendars.put(entityId, events);
	}

	/** Every {@code call_service} message received, oldest first. */
	public List<JsonNode> serviceCalls() {
		return List.copyOf(this.handler.serviceCalls);
	}

	public static Map<String, Object> event(String summary, String start, String end) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("start", start);
		event.put("end", end);
		event.put("summary", summary);
		return event;
	}
```

In `Handler`, add fields:

```java
		volatile String timeZone = "America/Denver";

		final Map<String, List<Map<String, Object>>> calendars = new ConcurrentHashMap<>();

		final List<JsonNode> serviceCalls = new CopyOnWriteArrayList<>();
```

add two cases to the `switch` (before `default`):

```java
				case "get_config" -> send(session, result(id, Map.of("time_zone", this.timeZone, "version", "2026.10.0")));
				case "call_service" -> {
					this.serviceCalls.add(request);
					send(session, serviceReply(id, request));
				}
```

and this method:

```java
		private Map<String, Object> serviceReply(int id, JsonNode request) {
			String entityId = request.path("target").path("entity_id").asString("");
			boolean getEvents = "calendar".equals(request.path("domain").asString(""))
					&& "get_events".equals(request.path("service").asString(""));
			List<Map<String, Object>> events = this.calendars.get(entityId);
			if (!getEvents || events == null) {
				return Map.of("id", id, "type", "result", "success", false, "error",
						Map.of("code", "service_validation_error", "message", "Entity " + entityId + " not found"));
			}
			return result(id, Map.of("context", Map.of("id", "ctx"), "response",
					Map.of(entityId, Map.of("events", events))));
		}
```

- [ ] **Step 4: Add the failing connector tests**

In `HomeAssistantConnectorTests`, add imports `java.time.ZoneId`, `java.util.concurrent.CompletableFuture`, `java.util.concurrent.CopyOnWriteArrayList`, `java.util.concurrent.ExecutionException`, `java.util.concurrent.TimeUnit`, `tools.jackson.databind.JsonNode`, `tools.jackson.databind.node.ObjectNode`. Add a field and record every published event in `start(...)`:

```java
	private final List<Object> published = new CopyOnWriteArrayList<>();
```

```java
		this.connector = new HomeAssistantConnector(config, this.state, (event) -> {
			this.updates.incrementAndGet();
			this.published.add(event);
		}, JsonMapper.shared());
```

Add tests:

```java
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
```

- [ ] **Step 5: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest='HaSessionTests,HomeAssistantStateTests,HomeAssistantConnectorTests' -Dskip.npm -Dskip.installnodenpm`
Expected: compilation FAILURE — `onTimeZone`, `timeZone()`, `HomeAssistantConnected`, `callService` do not exist.

- [ ] **Step 6: Add the new types**

`src/main/java/net/toadhead/ha/screensaver/ha/HomeAssistantConnected.java`:

```java
package net.toadhead.ha.screensaver.ha;

/**
 * Published each time a connection to Home Assistant becomes ready: authenticated
 * and with every state loaded. Pollers use it to fetch right after (re)connecting.
 */
public record HomeAssistantConnected() {

}
```

`src/main/java/net/toadhead/ha/screensaver/ha/HomeAssistantServices.java`:

```java
package net.toadhead.ha.screensaver.ha;

import java.util.concurrent.CompletableFuture;

import tools.jackson.databind.JsonNode;

/**
 * Calls Home Assistant services that return data.
 */
public interface HomeAssistantServices {

	/**
	 * Calls a service with {@code return_response}.
	 * @param domain the service domain, for example {@code calendar}
	 * @param service the service, for example {@code get_events}
	 * @param target the service target, for example
	 * {@code {"entity_id": "calendar.family"}}
	 * @param data the service data
	 * @return the reply's {@code response} object; completes exceptionally with
	 * {@link HaRequestException} when Home Assistant reports an error or isn't
	 * connected, or with a timeout when no reply arrives in 30 seconds
	 */
	CompletableFuture<JsonNode> callService(String domain, String service, JsonNode target, JsonNode data);

}
```

- [ ] **Step 7: Read the time zone in `HaSession`**

Add imports `java.time.DateTimeException`, `java.time.ZoneId`, `org.slf4j.Logger`, `org.slf4j.LoggerFactory`, and a logger field:

```java
	private static final Logger logger = LoggerFactory.getLogger(HaSession.class);
```

Replace `subscribeAndLoad` and add `onConfig`:

```java
	private void subscribeAndLoad() {
		ObjectNode subscribe = this.mapper.createObjectNode()
			.put("type", "subscribe_events")
			.put("event_type", "state_changed");
		this.subscriptionId = this.ids.get() + 1;
		request(subscribe).whenComplete(this::reportSetupFailure);
		// Home Assistant answers in order, so the time zone is known before states load.
		request(this.mapper.createObjectNode().put("type", "get_config")).whenComplete(this::onConfig);
		request(this.mapper.createObjectNode().put("type", "get_states")).thenAccept((result) -> {
			List<EntityState> states = new ArrayList<>();
			result.forEach((node) -> states.add(EntityState.fromJson(node)));
			this.listener.onStates(states);
		}).whenComplete(this::reportSetupFailure);
	}

	/** The time zone only moves calendar day boundaries, so problems are logged, not fatal. */
	private void onConfig(JsonNode config, Throwable failure) {
		if (failure != null) {
			logger.warn("Could not read Home Assistant's configuration: {}", failure.getMessage());
			return;
		}
		String zone = config.path("time_zone").asString("");
		try {
			this.listener.onTimeZone(ZoneId.of(zone));
		}
		catch (DateTimeException ex) {
			logger.warn("Ignoring Home Assistant's unknown time zone '{}'", zone);
		}
	}
```

Add to `Listener`:

```java
		/**
		 * Called with Home Assistant's configured time zone.
		 * @param zone the time zone
		 */
		void onTimeZone(ZoneId zone);
```

- [ ] **Step 8: Hold the time zone in `HomeAssistantState`**

Add `import java.time.ZoneId;` and:

```java
	private volatile ZoneId timeZone = ZoneId.systemDefault();

	/**
	 * Home Assistant's time zone, or the JVM's until Home Assistant reports one.
	 * @return the time zone
	 */
	public ZoneId timeZone() {
		return this.timeZone;
	}

	public void setTimeZone(ZoneId timeZone) {
		this.timeZone = timeZone;
	}
```

- [ ] **Step 9: Implement service calls and the connected event in `HomeAssistantConnector`**

Add imports `java.time.ZoneId`, `tools.jackson.databind.JsonNode`, `tools.jackson.databind.node.ObjectNode`. Make the class `implements SmartLifecycle, HomeAssistantServices` and add:

```java
	private static final Duration SERVICE_CALL_TIMEOUT = Duration.ofSeconds(30);
```

```java
	@Override
	public CompletableFuture<JsonNode> callService(String domain, String service, JsonNode target, JsonNode data) {
		Connection current = this.connection;
		if (current == null || !current.ready()) {
			return CompletableFuture.failedFuture(new HaRequestException("not connected to Home Assistant"));
		}
		ObjectNode message = this.mapper.createObjectNode()
			.put("type", "call_service")
			.put("domain", domain)
			.put("service", service)
			.put("return_response", true);
		message.set("target", target);
		message.set("service_data", data);
		return current.session.request(message)
			.orTimeout(SERVICE_CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
			.thenApply((result) -> result.path("response"));
	}
```

In `Connection`, add:

```java
		boolean ready() {
			return this.authenticated && !this.closed.get() && HomeAssistantConnector.this.state.connected();
		}

		@Override
		public void onTimeZone(ZoneId zone) {
			HomeAssistantConnector.this.state.setTimeZone(zone);
			logger.info("Home Assistant's time zone is {}", zone);
		}
```

and change `onStates` to:

```java
		@Override
		public void onStates(List<EntityState> states) {
			HomeAssistantConnector.this.state.markConnected(states);
			publish();
			HomeAssistantConnector.this.events.publishEvent(new HomeAssistantConnected());
		}
```

- [ ] **Step 10: Run the tests to verify they pass**

Run: `./mvnw -q test -Dskip.npm -Dskip.installnodenpm`
Expected: BUILD SUCCESS.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/net/toadhead/ha/screensaver/ha src/test/java/fakeha src/test/java/net/toadhead/ha/screensaver/ha
git commit -F - <<'EOF'
Read Home Assistant's time zone and call services with responses

The session asks for get_config after authenticating, the connector
publishes HomeAssistantConnected once states load, and callService sends
call_service with return_response.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 3: Calendar events and parsing

**Files:**
- Create: `src/main/java/net/toadhead/ha/screensaver/calendar/CalendarEvent.java`
- Create: `src/main/java/net/toadhead/ha/screensaver/calendar/CalendarEventParser.java`
- Create: `src/main/java/net/toadhead/ha/screensaver/calendar/CalendarEvents.java`
- Create: `src/main/java/net/toadhead/ha/screensaver/calendar/CalendarUpdated.java`
- Test: `src/test/java/net/toadhead/ha/screensaver/calendar/CalendarEventParserTests.java`
- Test: `src/test/java/net/toadhead/ha/screensaver/calendar/CalendarEventsTests.java`

**Interfaces:**
- Produces: `record CalendarEvent(String title, String start, String end, boolean allDay, String calendar)`.
- Produces: `CalendarEventParser.parse(JsonNode response, String entityId, String calendarId)` returning `List<CalendarEvent>` (package-private).
- Produces: `@Component CalendarEvents` with `List<CalendarEvent> events()` and `boolean replace(String calendarId, List<CalendarEvent> events)`.
- Produces: `record CalendarUpdated()`.

- [ ] **Step 1: Write the failing parser tests**

`src/test/java/net/toadhead/ha/screensaver/calendar/CalendarEventParserTests.java`:

```java
package net.toadhead.ha.screensaver.calendar;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarEventParserTests {

	@Test
	void readsTimedEvents() {
		List<CalendarEvent> events = parse("""
				[{"start":"2026-10-09T15:00:00-06:00","end":"2026-10-09T17:00:00-06:00",
				  "summary":"Soccer practice","description":"Bring water","location":"Park"}]""");
		assertThat(events).containsExactly(new CalendarEvent("Soccer practice", "2026-10-09T15:00:00-06:00",
				"2026-10-09T17:00:00-06:00", false, "school"));
	}

	@Test
	void readsAllDayEvents() {
		List<CalendarEvent> events = parse("""
				[{"start":"2026-10-09","end":"2026-10-10","summary":"Grandma's birthday"}]""");
		assertThat(events)
			.containsExactly(new CalendarEvent("Grandma's birthday", "2026-10-09", "2026-10-10", true, "school"));
	}

	@Test
	void namesUntitledEvents() {
		List<CalendarEvent> events = parse("""
				[{"start":"2026-10-09","end":"2026-10-10"},
				 {"start":"2026-10-09","end":"2026-10-10","summary":"   "}]""");
		assertThat(events).extracting(CalendarEvent::title).containsExactly("(No title)", "(No title)");
	}

	@Test
	void trimsTitles() {
		assertThat(parse("""
				[{"start":"2026-10-09","end":"2026-10-10","summary":"  Trash day  "}]""").get(0).title())
			.isEqualTo("Trash day");
	}

	@Test
	void skipsEventsWithUnreadableOrMixedDates() {
		List<CalendarEvent> events = parse("""
				[{"start":"tomorrow-ish","end":"2026-10-10","summary":"Bad"},
				 {"start":"2026-10-09","end":"2026-10-09T17:00:00-06:00","summary":"Mixed"},
				 {"summary":"Missing"},
				 {"start":"2026-10-09","end":"2026-10-10","summary":"Good"}]""");
		assertThat(events).extracting(CalendarEvent::title).containsExactly("Good");
	}

	@Test
	void returnsNothingWhenTheEntityIsMissingFromTheResponse() {
		JsonNode response = JsonMapper.shared().readTree("{\"calendar.other\":{\"events\":[]}}");
		assertThat(CalendarEventParser.parse(response, "calendar.school", "school")).isEmpty();
	}

	private static List<CalendarEvent> parse(String eventsJson) {
		JsonNode response = JsonMapper.shared().readTree("{\"calendar.school\":{\"events\":" + eventsJson + "}}");
		return CalendarEventParser.parse(response, "calendar.school", "school");
	}

}
```

- [ ] **Step 2: Write the failing store tests**

`src/test/java/net/toadhead/ha/screensaver/calendar/CalendarEventsTests.java`:

```java
package net.toadhead.ha.screensaver.calendar;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarEventsTests {

	private final CalendarEvents events = new CalendarEvents();

	@Test
	void startsEmpty() {
		assertThat(this.events.events()).isEmpty();
	}

	@Test
	void mergesCalendarsInStartOrderWithAllDayEventsFirst() {
		this.events.replace("school", List.of(timed("Soccer", "15:00", "school"), timed("Piano", "15:30", "school")));
		this.events.replace("family", List.of(timed("Dentist", "09:00", "family"),
				new CalendarEvent("Birthday", "2026-10-09", "2026-10-10", true, "family")));
		assertThat(this.events.events()).extracting(CalendarEvent::title)
			.containsExactly("Birthday", "Dentist", "Soccer", "Piano");
	}

	@Test
	void replacingACalendarDropsItsOldEvents() {
		this.events.replace("family", List.of(timed("Dentist", "09:00", "family")));
		this.events.replace("family", List.of(timed("Lunch", "12:00", "family")));
		assertThat(this.events.events()).extracting(CalendarEvent::title).containsExactly("Lunch");
	}

	@Test
	void reportsWhetherTheMergedListChanged() {
		assertThat(this.events.replace("family", List.of(timed("Dentist", "09:00", "family")))).isTrue();
		assertThat(this.events.replace("family", List.of(timed("Dentist", "09:00", "family")))).isFalse();
		assertThat(this.events.replace("school", List.of())).isFalse();
	}

	private static CalendarEvent timed(String title, String time, String calendar) {
		return new CalendarEvent(title, "2026-10-09T" + time + ":00-06:00", "2026-10-09T23:00:00-06:00", false,
				calendar);
	}

}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest='CalendarEventParserTests,CalendarEventsTests' -Dskip.npm -Dskip.installnodenpm`
Expected: compilation FAILURE — package `net.toadhead.ha.screensaver.calendar` does not exist.

- [ ] **Step 4: Write `CalendarEvent` and `CalendarUpdated`**

`src/main/java/net/toadhead/ha/screensaver/calendar/CalendarEvent.java`:

```java
package net.toadhead.ha.screensaver.calendar;

/**
 * One calendar event, as the browser receives it.
 *
 * @param title the event's summary, or {@code (No title)}
 * @param start a date ({@code 2026-10-09}) for all-day events, otherwise an ISO
 * date-time with offset
 * @param end like {@code start}, and exclusive: a one-day all-day event ends the
 * next day
 * @param allDay whether the event is all-day
 * @param calendar the configured calendar id
 */
public record CalendarEvent(String title, String start, String end, boolean allDay, String calendar) {

}
```

`src/main/java/net/toadhead/ha/screensaver/calendar/CalendarUpdated.java`:

```java
package net.toadhead.ha.screensaver.calendar;

/**
 * Published when the merged calendar events change.
 */
public record CalendarUpdated() {

}
```

- [ ] **Step 5: Write `CalendarEventParser`**

`src/main/java/net/toadhead/ha/screensaver/calendar/CalendarEventParser.java`:

```java
package net.toadhead.ha.screensaver.calendar;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Reads events from a {@code calendar.get_events} response, which is keyed by
 * entity id: {@code {"calendar.family": {"events": [{"start", "end", "summary"}]}}}.
 */
final class CalendarEventParser {

	private static final Logger logger = LoggerFactory.getLogger(CalendarEventParser.class);

	static final String NO_TITLE = "(No title)";

	private CalendarEventParser() {
	}

	/**
	 * Reads one calendar's events, skipping (and logging) any whose dates can't be
	 * read.
	 * @param response the service response
	 * @param entityId the calendar entity the response is for
	 * @param calendarId the configured calendar id to tag events with
	 * @return the events, in response order
	 */
	static List<CalendarEvent> parse(JsonNode response, String entityId, String calendarId) {
		List<CalendarEvent> events = new ArrayList<>();
		response.path(entityId).path("events").forEach((node) -> {
			CalendarEvent event = parseEvent(node, calendarId);
			if (event != null) {
				events.add(event);
			}
			else {
				logger.warn("Skipping an event in {} with unreadable dates: {}", entityId, node);
			}
		});
		return events;
	}

	private static CalendarEvent parseEvent(JsonNode node, String calendarId) {
		String start = node.path("start").asString("");
		String end = node.path("end").asString("");
		String summary = node.path("summary").asString("").strip();
		String title = summary.isEmpty() ? NO_TITLE : summary;
		if (isDate(start) && isDate(end)) {
			return new CalendarEvent(title, start, end, true, calendarId);
		}
		if (isDateTime(start) && isDateTime(end)) {
			return new CalendarEvent(title, start, end, false, calendarId);
		}
		return null;
	}

	private static boolean isDate(String value) {
		try {
			LocalDate.parse(value);
			return true;
		}
		catch (DateTimeParseException ex) {
			return false;
		}
	}

	private static boolean isDateTime(String value) {
		try {
			OffsetDateTime.parse(value);
			return true;
		}
		catch (DateTimeParseException ex) {
			return false;
		}
	}

}
```

- [ ] **Step 6: Write `CalendarEvents`**

`src/main/java/net/toadhead/ha/screensaver/calendar/CalendarEvents.java`:

```java
package net.toadhead.ha.screensaver.calendar;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * The latest events of every calendar, merged into one list. A calendar that fails
 * to update keeps its previous events.
 */
@Component
public class CalendarEvents {

	/**
	 * ISO strings from one time zone sort chronologically, and a day's all-day date
	 * sorts before its times. The browser lays out each day itself; this order just
	 * keeps the world state stable.
	 */
	private static final Comparator<CalendarEvent> ORDER = Comparator.comparing(CalendarEvent::start)
		.thenComparing(CalendarEvent::title)
		.thenComparing(CalendarEvent::calendar);

	private final Map<String, List<CalendarEvent>> byCalendar = new ConcurrentHashMap<>();

	private volatile List<CalendarEvent> merged = List.of();

	public List<CalendarEvent> events() {
		return this.merged;
	}

	/**
	 * Replaces one calendar's events.
	 * @param calendarId the configured calendar id
	 * @param events its current events
	 * @return whether the merged list changed
	 */
	public synchronized boolean replace(String calendarId, List<CalendarEvent> events) {
		this.byCalendar.put(calendarId, List.copyOf(events));
		List<CalendarEvent> next = this.byCalendar.values().stream().flatMap(List::stream).sorted(ORDER).toList();
		boolean changed = !next.equals(this.merged);
		this.merged = next;
		return changed;
	}

}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest='CalendarEventParserTests,CalendarEventsTests' -Dskip.npm -Dskip.installnodenpm`
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/net/toadhead/ha/screensaver/calendar src/test/java/net/toadhead/ha/screensaver/calendar
git commit -F - <<'EOF'
Parse and merge calendar events

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 4: Calendar poller

**Files:**
- Create: `src/main/java/net/toadhead/ha/screensaver/calendar/CalendarPoller.java`
- Test: `src/test/java/net/toadhead/ha/screensaver/calendar/CalendarPollerTests.java`

**Interfaces:**
- Consumes: `HomeAssistantServices.callService(...)`, `HomeAssistantState.connected()` / `timeZone()`, `HomeAssistantConnected` (Task 2); `ScreensaverProperties.calendar().calendars()`, `CalendarEntry` (Task 1); `CalendarEvents.replace(...)`, `CalendarEventParser.parse(...)`, `CalendarUpdated` (Task 3).
- Produces: `@Component CalendarPoller` (only when `screensaver.home-assistant.enabled` is true or missing) with `onConnected(HomeAssistantConnected)`, `tick()` (scheduled every minute), and package-private `poll()`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/net/toadhead/ha/screensaver/calendar/CalendarPollerTests.java`:

```java
package net.toadhead.ha.screensaver.calendar;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import net.toadhead.ha.screensaver.config.ScreensaverProperties.CalendarEntry;
import net.toadhead.ha.screensaver.ha.HaRequestException;
import net.toadhead.ha.screensaver.ha.HomeAssistantConnected;
import net.toadhead.ha.screensaver.ha.HomeAssistantServices;
import net.toadhead.ha.screensaver.ha.HomeAssistantState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarPollerTests {

	private static final ZoneId DENVER = ZoneId.of("America/Denver");

	private final JsonMapper mapper = JsonMapper.shared();

	private final HomeAssistantState ha = new HomeAssistantState();

	private final CalendarEvents events = new CalendarEvents();

	private final List<Object> published = new ArrayList<>();

	private final List<JsonNode> calls = new ArrayList<>();

	private final Map<String, CompletableFuture<JsonNode>> replies = new HashMap<>();

	/** 14:00 on October 9 in Denver. */
	private final MutableClock clock = new MutableClock(Instant.parse("2026-10-09T20:00:00Z"));

	private final HomeAssistantServices services = (domain, service, target, data) -> {
		ObjectNode call = this.mapper.createObjectNode().put("domain", domain).put("service", service);
		call.set("target", target);
		call.set("data", data);
		this.calls.add(call);
		return this.replies.getOrDefault(target.path("entity_id").asString(""),
				CompletableFuture.failedFuture(new HaRequestException("Entity not found")));
	};

	@BeforeEach
	void connect() {
		this.ha.markConnected(List.of());
		this.ha.setTimeZone(DENVER);
	}

	@Test
	void requestsTodayAndTomorrowInHomeAssistantsTimeZone() {
		poller("family").poll();
		assertThat(this.calls).hasSize(1);
		JsonNode call = this.calls.get(0);
		assertThat(call.path("domain").asString("")).isEqualTo("calendar");
		assertThat(call.path("service").asString("")).isEqualTo("get_events");
		assertThat(call.path("target").path("entity_id").asString("")).isEqualTo("calendar.family");
		assertThat(call.path("data").path("start_date_time").asString("")).isEqualTo("2026-10-09T00:00:00-06:00");
		assertThat(call.path("data").path("end_date_time").asString("")).isEqualTo("2026-10-11T00:00:00-06:00");
	}

	@Test
	void usesHomeAssistantsDateNotUtcs() {
		this.clock.set(Instant.parse("2026-10-10T03:00:00Z"));
		poller("family").poll();
		assertThat(this.calls.get(0).path("data").path("start_date_time").asString(""))
			.isEqualTo("2026-10-09T00:00:00-06:00");
	}

	@Test
	void mergesCalendarsAndPublishesUpdates() {
		reply("calendar.family", "Dentist", "09:00");
		reply("calendar.school", "Soccer", "15:00");
		poller("family", "school").poll();
		assertThat(this.events.events()).extracting(CalendarEvent::title).containsExactly("Dentist", "Soccer");
		assertThat(this.events.events()).extracting(CalendarEvent::calendar).containsExactly("family", "school");
		assertThat(this.published).hasSize(2).allMatch(CalendarUpdated.class::isInstance);
	}

	@Test
	void doesNotPublishWhenNothingChanged() {
		reply("calendar.family", "Dentist", "09:00");
		CalendarPoller poller = poller("family");
		poller.poll();
		poller.poll();
		assertThat(this.published).hasSize(1);
	}

	@Test
	void oneFailingCalendarDoesNotBlockTheOthers() {
		reply("calendar.school", "Soccer", "15:00");
		poller("typo", "school").poll();
		assertThat(this.events.events()).extracting(CalendarEvent::title).containsExactly("Soccer");
	}

	@Test
	void keepsLastEventsWhenACalendarFails() {
		reply("calendar.family", "Dentist", "09:00");
		CalendarPoller poller = poller("family");
		poller.poll();
		this.replies.put("calendar.family", CompletableFuture.failedFuture(new HaRequestException("Boom")));
		poller.poll();
		assertThat(this.events.events()).extracting(CalendarEvent::title).containsExactly("Dentist");
	}

	@Test
	void skipsWhileDisconnected() {
		this.ha.markDisconnected();
		poller("family").poll();
		assertThat(this.calls).isEmpty();
	}

	@Test
	void doesNothingWithoutCalendars() {
		poller().poll();
		assertThat(this.calls).isEmpty();
	}

	@Test
	void pollsWhenHomeAssistantConnects() {
		poller("family").onConnected(new HomeAssistantConnected());
		assertThat(this.calls).hasSize(1);
	}

	@Test
	void tickPollsAgainOnlyAfterTheInterval() {
		CalendarPoller poller = poller("family");
		poller.tick();
		this.clock.advance(Duration.ofMinutes(14));
		poller.tick();
		assertThat(this.calls).hasSize(1);
		this.clock.advance(Duration.ofMinutes(1));
		poller.tick();
		assertThat(this.calls).hasSize(2);
	}

	@Test
	void tickPollsJustAfterMidnight() {
		this.clock.set(Instant.parse("2026-10-10T05:55:00Z"));
		CalendarPoller poller = poller("family");
		poller.tick();
		this.clock.set(Instant.parse("2026-10-10T06:01:00Z"));
		poller.tick();
		assertThat(this.calls).hasSize(2);
		assertThat(this.calls.get(1).path("data").path("start_date_time").asString(""))
			.isEqualTo("2026-10-10T00:00:00-06:00");
	}

	@Test
	void tickKeepsTryingUntilConnected() {
		this.ha.markDisconnected();
		CalendarPoller poller = poller("family");
		poller.tick();
		this.ha.markConnected(List.of());
		this.clock.advance(Duration.ofMinutes(1));
		poller.tick();
		assertThat(this.calls).hasSize(1);
	}

	private CalendarPoller poller(String... ids) {
		Map<String, CalendarEntry> calendars = new LinkedHashMap<>();
		for (String id : ids) {
			calendars.put(id, new CalendarEntry("calendar." + id, "Calendar " + id, null));
		}
		return new CalendarPoller(calendars, this.services, this.ha, this.events, this.published::add, this.mapper,
				this.clock);
	}

	private void reply(String entityId, String title, String time) {
		JsonNode response = this.mapper.readTree("{\"" + entityId + "\":{\"events\":[{\"summary\":\"" + title
				+ "\",\"start\":\"2026-10-09T" + time + ":00-06:00\",\"end\":\"2026-10-09T23:00:00-06:00\"}]}}");
		this.replies.put(entityId, CompletableFuture.completedFuture(response));
	}

	static final class MutableClock extends Clock {

		private Instant instant;

		MutableClock(Instant instant) {
			this.instant = instant;
		}

		void set(Instant instant) {
			this.instant = instant;
		}

		void advance(Duration duration) {
			this.instant = this.instant.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return this.instant;
		}

	}

}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest=CalendarPollerTests -Dskip.npm -Dskip.installnodenpm`
Expected: compilation FAILURE — `CalendarPoller` does not exist.

- [ ] **Step 3: Write `CalendarPoller`**

`src/main/java/net/toadhead/ha/screensaver/calendar/CalendarPoller.java`:

```java
package net.toadhead.ha.screensaver.calendar;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import net.toadhead.ha.screensaver.config.ScreensaverProperties;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.CalendarEntry;
import net.toadhead.ha.screensaver.ha.HomeAssistantConnected;
import net.toadhead.ha.screensaver.ha.HomeAssistantServices;
import net.toadhead.ha.screensaver.ha.HomeAssistantState;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fetches today's and tomorrow's events from every configured calendar: when Home
 * Assistant connects, every 15 minutes, and just after midnight in Home
 * Assistant's time zone.
 */
@Component
@ConditionalOnBooleanProperty(name = "screensaver.home-assistant.enabled", matchIfMissing = true)
public class CalendarPoller {

	static final Duration INTERVAL = Duration.ofMinutes(15);

	private static final Logger logger = LoggerFactory.getLogger(CalendarPoller.class);

	private final Map<String, CalendarEntry> calendars;

	private final HomeAssistantServices services;

	private final HomeAssistantState ha;

	private final CalendarEvents events;

	private final ApplicationEventPublisher publisher;

	private final JsonMapper mapper;

	private final Clock clock;

	private Instant lastPoll;

	private LocalDate lastPollDate;

	@Autowired
	public CalendarPoller(ScreensaverProperties properties, HomeAssistantServices services, HomeAssistantState ha,
			CalendarEvents events, ApplicationEventPublisher publisher, JsonMapper mapper) {
		this(properties.calendar().calendars(), services, ha, events, publisher, mapper, Clock.systemUTC());
	}

	CalendarPoller(Map<String, CalendarEntry> calendars, HomeAssistantServices services, HomeAssistantState ha,
			CalendarEvents events, ApplicationEventPublisher publisher, JsonMapper mapper, Clock clock) {
		this.calendars = calendars;
		this.services = services;
		this.ha = ha;
		this.events = events;
		this.publisher = publisher;
		this.mapper = mapper;
		this.clock = clock;
	}

	@EventListener
	public void onConnected(HomeAssistantConnected event) {
		poll();
	}

	/**
	 * Polls once the interval has passed or the day has changed since the last poll.
	 */
	@Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
	public synchronized void tick() {
		Instant now = this.clock.instant();
		LocalDate today = LocalDate.ofInstant(now, this.ha.timeZone());
		if (this.lastPoll == null || !today.equals(this.lastPollDate) || !now.isBefore(this.lastPoll.plus(INTERVAL))) {
			poll();
		}
	}

	synchronized void poll() {
		if (this.calendars.isEmpty() || !this.ha.connected()) {
			return;
		}
		ZoneId zone = this.ha.timeZone();
		Instant now = this.clock.instant();
		LocalDate today = LocalDate.ofInstant(now, zone);
		this.lastPoll = now;
		this.lastPollDate = today;
		ObjectNode data = this.mapper.createObjectNode()
			.put("start_date_time", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(today.atStartOfDay(zone)))
			.put("end_date_time", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(today.plusDays(2).atStartOfDay(zone)));
		this.calendars.forEach((id, calendar) -> fetch(id, calendar, data));
	}

	private void fetch(String id, CalendarEntry calendar, ObjectNode data) {
		ObjectNode target = this.mapper.createObjectNode().put("entity_id", calendar.entity());
		this.services.callService("calendar", "get_events", target, data).whenComplete((response, failure) -> {
			if (failure != null) {
				logger.warn("Could not read calendar {} ({}), keeping its last events: {}", calendar.label(),
						calendar.entity(), failure.getMessage());
				return;
			}
			if (this.events.replace(id, CalendarEventParser.parse(response, calendar.entity(), id))) {
				this.publisher.publishEvent(new CalendarUpdated());
			}
		});
	}

}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q test -Dskip.npm -Dskip.installnodenpm`
Expected: BUILD SUCCESS (the whole backend suite, so the new component doesn't break context tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/toadhead/ha/screensaver/calendar/CalendarPoller.java src/test/java/net/toadhead/ha/screensaver/calendar/CalendarPollerTests.java
git commit -F - <<'EOF'
Poll Home Assistant calendars for today and tomorrow

Fetches on connect, every 15 minutes, and just after midnight in Home
Assistant's time zone. A calendar that fails keeps its last events.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 5: Calendar in the world state and kiosk config

**Files:**
- Modify: `src/main/java/net/toadhead/ha/screensaver/stream/WorldState.java`
- Modify: `src/main/java/net/toadhead/ha/screensaver/stream/WorldStateAssembler.java`
- Modify: `src/main/java/net/toadhead/ha/screensaver/stream/WorldStateService.java`
- Modify: `src/main/java/net/toadhead/ha/screensaver/kiosk/KioskConfig.java`
- Modify: `src/main/java/net/toadhead/ha/screensaver/kiosk/KioskConfigService.java`
- Modify: `src/test/java/net/toadhead/ha/screensaver/stream/StreamBroadcasterTests.java`
- Test: `src/test/java/net/toadhead/ha/screensaver/stream/WorldStateAssemblerTests.java`
- Test: `src/test/java/net/toadhead/ha/screensaver/stream/WorldStateServiceTests.java`
- Test: `src/test/java/net/toadhead/ha/screensaver/kiosk/KioskConfigServiceTests.java`

**Interfaces:**
- Consumes: `CalendarEvents`, `CalendarEvent`, `CalendarUpdated` (Task 3); `CalendarColor.assign`, `ScreensaverProperties.Calendar` (Task 1).
- Produces: `WorldState(boolean haConnected, List<SnakeValue> snakes, WorldState.Calendar calendar)`, `record WorldState.Calendar(List<CalendarEvent> events)`. JSON: `"calendar": {"events": [{"title","start","end","allDay","calendar"}]}`.
- Produces: `WorldStateAssembler.assemble(ScreensaverProperties, HomeAssistantState, List<CalendarEvent>)`; `WorldStateService(ScreensaverProperties, HomeAssistantState, CalendarEvents)` with `onCalendarUpdated(CalendarUpdated)`.
- Produces: `KioskConfig(kiosk, scenes, clock, snakes, CalendarOptions calendar)`, `record CalendarOptions(String allDayUntil, List<CalendarDef> calendars)`, `record CalendarDef(String id, String label, String color, String textColor)`. JSON: `"calendar": {"allDayUntil": "18:00", "calendars": [{"id","label","color","textColor"}]}`.

- [ ] **Step 1: Update existing tests for the new signatures**

- `WorldStateAssemblerTests`: in every `WorldStateAssembler.assemble(..., this.ha)` call, add a third argument `List.of()` (add `import java.util.List;` if missing).
- `StreamBroadcasterTests`: change `new WorldStateService(<properties>, this.ha)` to `new WorldStateService(<properties>, this.ha, new CalendarEvents())` and import `net.toadhead.ha.screensaver.calendar.CalendarEvents`.
- `WorldStateServiceTests`: import `net.toadhead.ha.screensaver.calendar.CalendarEvents` and replace the two fields at the top with:

```java
	private final HomeAssistantState ha = new HomeAssistantState();

	private final CalendarEvents calendar = new CalendarEvents();

	private final WorldStateService service = new WorldStateService(
			TestProperties.withSnakes(Map.of("power", TestProperties.snake("sensor.power", "Power")), Map.of()),
			this.ha, this.calendar);
```
- `StreamBroadcasterTests`: the `KioskConfig` constructor gains a last argument `new KioskConfig.CalendarOptions("18:00", List.of())`.

- [ ] **Step 2: Write the failing tests**

In `WorldStateAssemblerTests`, add (with imports `net.toadhead.ha.screensaver.calendar.CalendarEvent`, `tools.jackson.databind.JsonNode`):

```java
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
```

In `WorldStateServiceTests`, add (imports `net.toadhead.ha.screensaver.calendar.CalendarEvent`, `net.toadhead.ha.screensaver.calendar.CalendarUpdated`):

```java
	@Test
	void bumpsVersionWhenCalendarChanges() {
		this.calendar.replace("family", List.of(new CalendarEvent("Birthday", "2026-10-09", "2026-10-10", true, "family")));
		this.service.onCalendarUpdated(new CalendarUpdated());
		assertThat(this.service.current().version()).isEqualTo(1);
		assertThat(this.service.current().state().calendar().events()).extracting(CalendarEvent::title)
			.containsExactly("Birthday");
	}
```

In `KioskConfigServiceTests`, add (imports `java.time.LocalTime`, `net.toadhead.ha.screensaver.config.CalendarColor`, `net.toadhead.ha.screensaver.config.ScreensaverProperties.Calendar`, `net.toadhead.ha.screensaver.config.ScreensaverProperties.CalendarEntry`, `tools.jackson.databind.JsonNode`, `tools.jackson.databind.json.JsonMapper`):

```java
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
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest='WorldStateAssemblerTests,WorldStateServiceTests,StreamBroadcasterTests,KioskConfigServiceTests' -Dskip.npm -Dskip.installnodenpm`
Expected: compilation FAILURE — new constructors and `calendar()` accessors do not exist.

- [ ] **Step 4: Add the calendar to `WorldState`**

Replace the record header and Javadoc, add the import `net.toadhead.ha.screensaver.calendar.CalendarEvent`, and add the nested record:

```java
/**
 * The derived data the scenes need, streamed to every kiosk as the {@code state}
 * event.
 *
 * @param haConnected whether Home Assistant is currently connected
 * @param snakes current value of every configured snake, pinned first
 * @param calendar today's and tomorrow's calendar events
 */
public record WorldState(boolean haConnected, List<SnakeValue> snakes, Calendar calendar) {

	/**
	 * Events from every configured calendar, from the start of today to the end of
	 * tomorrow in Home Assistant's time zone.
	 *
	 * @param events merged events
	 */
	public record Calendar(List<CalendarEvent> events) {

	}
```

(keep the existing `SnakeValue` record).

- [ ] **Step 5: Pass events through the assembler and service**

In `WorldStateAssembler`, import `net.toadhead.ha.screensaver.calendar.CalendarEvent` and change `assemble`:

```java
	static WorldState assemble(ScreensaverProperties properties, HomeAssistantState ha,
			List<CalendarEvent> calendarEvents) {
		List<SnakeValue> snakes = Stream
			.concat(properties.snakes().pinned().entrySet().stream(), properties.snakes().guests().entrySet().stream())
			.map((entry) -> snakeValue(entry, ha))
			.toList();
		return new WorldState(ha.connected(), snakes, new WorldState.Calendar(calendarEvents));
	}
```

In `WorldStateService`, import `net.toadhead.ha.screensaver.calendar.CalendarEvents` and `net.toadhead.ha.screensaver.calendar.CalendarUpdated`, then:

```java
	private final CalendarEvents calendar;

	public WorldStateService(ScreensaverProperties properties, HomeAssistantState ha, CalendarEvents calendar) {
		this.properties = properties;
		this.ha = ha;
		this.calendar = calendar;
		this.current = new Versioned(0, WorldStateAssembler.assemble(properties, ha, calendar.events()));
	}
```

```java
	@EventListener
	public void onCalendarUpdated(CalendarUpdated event) {
		refresh();
	}

	synchronized void refresh() {
		WorldState next = WorldStateAssembler.assemble(this.properties, this.ha, this.calendar.events());
		if (!next.equals(this.current.state())) {
			this.current = new Versioned(this.current.version() + 1, next);
		}
	}
```

- [ ] **Step 6: Add calendar options to `KioskConfig` and `KioskConfigService`**

`KioskConfig`: add `@param calendar calendar scene options` to the Javadoc, change the header to `public record KioskConfig(String kiosk, List<SceneConfig> scenes, ClockOptions clock, SnakeOptions snakes, CalendarOptions calendar) {`, and add:

```java
	/**
	 * @param allDayUntil {@code HH:mm} local time after which an all-day event alone no
	 * longer keeps today on screen
	 * @param calendars every configured calendar, in configuration order
	 */
	public record CalendarOptions(String allDayUntil, List<CalendarDef> calendars) {

	}

	/**
	 * @param id calendar id, matching {@code calendar} on events
	 * @param label calendar label
	 * @param color event block color, as CSS
	 * @param textColor text color on the block, as CSS
	 */
	public record CalendarDef(String id, String label, String color, String textColor) {

	}
```

`KioskConfigService`: add imports `java.time.format.DateTimeFormatter`, `net.toadhead.ha.screensaver.config.CalendarColor`, `net.toadhead.ha.screensaver.kiosk.KioskConfig.CalendarDef`, `net.toadhead.ha.screensaver.kiosk.KioskConfig.CalendarOptions`. Compute the options once:

```java
	private final CalendarOptions calendarOptions;

	public KioskConfigService(ScreensaverProperties properties) {
		this.properties = properties;
		this.calendarOptions = calendarOptions(properties.calendar());
	}
```

pass `this.calendarOptions` as the last `KioskConfig` argument in `resolve`, and add:

```java
	private static CalendarOptions calendarOptions(ScreensaverProperties.Calendar calendar) {
		List<CalendarDef> calendars = CalendarColor.assign(calendar.calendars())
			.entrySet()
			.stream()
			.map((entry) -> new CalendarDef(entry.getKey(), calendar.calendars().get(entry.getKey()).label(),
					entry.getValue().background(), entry.getValue().text()))
			.toList();
		return new CalendarOptions(calendar.allDayUntil().format(DateTimeFormatter.ofPattern("HH:mm")), calendars);
	}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw -q test -Dskip.npm -Dskip.installnodenpm`
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/net/toadhead/ha/screensaver/stream src/main/java/net/toadhead/ha/screensaver/kiosk src/test/java/net/toadhead/ha/screensaver/stream src/test/java/net/toadhead/ha/screensaver/kiosk
git commit -F - <<'EOF'
Stream calendar events and calendar colors to kiosks

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 6: Calendar layout logic (frontend)

**Files:**
- Modify: `src/main/frontend/src/types.ts`
- Create: `src/main/frontend/src/scenes/calendar/model.ts`
- Test: `src/main/frontend/src/scenes/calendar/model.test.ts`

**Interfaces:**
- Consumes: `formatDigital(date, use24Hour)` from `src/main/frontend/src/scenes/clock/layout.ts`.
- Produces in `types.ts`: `interface CalendarEvent { title: string; start: string; end: string; allDay: boolean; calendar: string }` (only this type in this task, so the build stays green).
- Produces in `model.ts`: `type Day = 'today' | 'tomorrow'`; `interface TimedEvent { event; start: Date; end: Date }`; `interface DayEvents { allDay: CalendarEvent[]; timed: TimedEvent[] }`; `interface PlacedEvent extends TimedEvent { lane: number; laneCount: number }`; functions `startOfDay(date)`, `addDays(date, days)`, `eventsOn(events, day): DayEvents`, `chooseDay(events, now, allDayUntil): Day | null`, `hourOfDay(date, dayStart): number`, `hourSpan(timed, dayStart): { startHour; endHour }`, `packLanes(timed): PlacedEvent[]`, `isShort(timed): boolean`, `formatRange(start, end, use24Hour): string`, `hourLabel(hour, use24Hour): string`; constants `MIN_SPAN_HOURS = 6`, `SHORT_EVENT_MINUTES = 40`.

- [ ] **Step 1: Add the event type**

Append to `src/main/frontend/src/types.ts`:

```ts
/** One calendar event (net.toadhead.ha.screensaver.calendar.CalendarEvent). */
export interface CalendarEvent {
  title: string;
  /** `2026-10-09` for all-day events, otherwise an ISO date-time with offset. */
  start: string;
  /** Exclusive, like `start`. */
  end: string;
  allDay: boolean;
  calendar: string;
}
```

- [ ] **Step 2: Write the failing tests**

`src/main/frontend/src/scenes/calendar/model.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import type { CalendarEvent } from '../../types';
import {
  addDays, chooseDay, eventsOn, formatRange, hourLabel, hourSpan, isShort, packLanes, startOfDay, type TimedEvent,
} from './model';

/** Local time on Friday, October 9 2026 (`day` shifts the date). */
const at = (hour: number, minute = 0, day = 0) => new Date(2026, 9, 9 + day, hour, minute);
const FRIDAY = startOfDay(at(0));

const timed = (title: string, start: Date, end: Date, calendar = 'family'): CalendarEvent => ({
  title, start: start.toISOString(), end: end.toISOString(), allDay: false, calendar,
});
const allDay = (title: string, start: string, end: string): CalendarEvent => ({
  title, start, end, allDay: true, calendar: 'family',
});
const clipped = (start: Date, end: Date, title = 'x'): TimedEvent => ({ event: timed(title, start, end), start, end });

describe('eventsOn', () => {
  it('sorts timed events by start', () => {
    const day = eventsOn([timed('Late', at(18), at(19)), timed('Early', at(9), at(10))], FRIDAY);
    expect(day.timed.map((t) => t.event.title)).toEqual(['Early', 'Late']);
  });

  it('shows a multi-day all-day event on each day it covers, but not its exclusive end date', () => {
    const trip = allDay('Trip', '2026-10-08', '2026-10-11');
    expect(eventsOn([trip], at(12, 0, -1)).allDay).toHaveLength(1);
    expect(eventsOn([trip], at(12)).allDay).toHaveLength(1);
    expect(eventsOn([trip], at(12, 0, 1)).allDay).toHaveLength(1);
    expect(eventsOn([trip], at(12, 0, 2)).allDay).toHaveLength(0);
  });

  it('clips a timed event that crosses midnight to each day', () => {
    const party = timed('Party', at(22), at(2, 0, 1));
    const friday = eventsOn([party], FRIDAY).timed[0];
    const saturday = eventsOn([party], addDays(FRIDAY, 1)).timed[0];
    expect([friday.start, friday.end]).toEqual([at(22), at(0, 0, 1)]);
    expect([saturday.start, saturday.end]).toEqual([at(0, 0, 1), at(2, 0, 1)]);
  });

  it('keeps an event that ends exactly at midnight off the next day', () => {
    const late = timed('Late show', at(22), at(0, 0, 1));
    expect(eventsOn([late], FRIDAY).timed).toHaveLength(1);
    expect(eventsOn([late], addDays(FRIDAY, 1)).timed).toHaveLength(0);
  });
});

describe('chooseDay', () => {
  const tomorrowSoccer = timed('Soccer', at(8, 0, 1), at(9, 0, 1));

  it('shows today while a timed event is still to come', () => {
    expect(chooseDay([timed('Book club', at(18), at(19)), tomorrowSoccer], at(14), '18:00')).toBe('today');
  });

  it('shows today while a timed event is in progress', () => {
    expect(chooseDay([timed('Lunch', at(12), at(13))], at(12, 30), '18:00')).toBe('today');
  });

  it('flips to tomorrow when the last timed event ends exactly now', () => {
    expect(chooseDay([timed('Lunch', at(12), at(13)), tomorrowSoccer], at(13), '18:00')).toBe('tomorrow');
  });

  it('keeps today for an all-day event until the cutoff, then flips', () => {
    const events = [allDay('Birthday', '2026-10-09', '2026-10-10'), tomorrowSoccer];
    expect(chooseDay(events, at(17, 59), '18:00')).toBe('today');
    expect(chooseDay(events, at(18), '18:00')).toBe('tomorrow');
  });

  it('keeps today for a timed event from yesterday that spills over midnight', () => {
    const overnight = timed('Overnight shift', at(22, 0, -1), at(6));
    expect(chooseDay([overnight, tomorrowSoccer], at(3), '18:00')).toBe('today');
    expect(chooseDay([overnight, tomorrowSoccer], at(6), '18:00')).toBe('tomorrow');
  });

  it('shows tomorrow when it has only all-day events', () => {
    expect(chooseDay([allDay('Holiday', '2026-10-10', '2026-10-11')], at(20), '18:00')).toBe('tomorrow');
  });

  it('is null when nothing is left today and tomorrow is empty', () => {
    expect(chooseDay([timed('Lunch', at(12), at(13))], at(20), '18:00')).toBeNull();
    expect(chooseDay([], at(9), '18:00')).toBeNull();
  });
});

describe('hourSpan', () => {
  it('covers every event, rounded out to whole hours', () => {
    const span = hourSpan([clipped(at(9, 30), at(13, 15)), clipped(at(18), at(19, 30))], FRIDAY);
    expect(span).toEqual({ startHour: 9, endHour: 20 });
  });

  it('stretches a short span to six hours', () => {
    expect(hourSpan([clipped(at(10), at(11))], FRIDAY)).toEqual({ startHour: 10, endHour: 16 });
  });

  it('stretches earlier when the end reaches midnight', () => {
    expect(hourSpan([clipped(at(22), at(23, 30))], FRIDAY)).toEqual({ startHour: 18, endHour: 24 });
  });

  it('treats an event clipped at midnight as ending at hour 24', () => {
    expect(hourSpan([clipped(at(17), at(0, 0, 1))], FRIDAY)).toEqual({ startHour: 17, endHour: 24 });
  });
});

describe('packLanes', () => {
  const lanes = (placed: ReturnType<typeof packLanes>) =>
    placed.map((p) => `${p.event.title}:${p.lane}/${p.laneCount}`);

  it('gives separate events the full width', () => {
    expect(lanes(packLanes([clipped(at(9), at(10), 'A'), clipped(at(11), at(12), 'B')]))).toEqual(['A:0/1', 'B:0/1']);
  });

  it('puts overlapping events side by side', () => {
    expect(lanes(packLanes([clipped(at(15), at(17), 'Soccer'), clipped(at(15, 30), at(16, 30), 'Piano')])))
      .toEqual(['Soccer:0/2', 'Piano:1/2']);
  });

  it('reuses a lane once its event ends, across a chain of overlaps', () => {
    const placed = packLanes([clipped(at(13), at(15), 'A'), clipped(at(14), at(16), 'B'), clipped(at(15, 30), at(17), 'C')]);
    expect(lanes(placed)).toEqual(['A:0/2', 'B:1/2', 'C:0/2']);
  });

  it('uses three lanes when three overlap at once', () => {
    const placed = packLanes([clipped(at(9), at(12), 'A'), clipped(at(10), at(11), 'B'), clipped(at(10, 30), at(11, 30), 'C')]);
    expect(lanes(placed)).toEqual(['A:0/3', 'B:1/3', 'C:2/3']);
  });

  it('does not treat a shared endpoint as an overlap', () => {
    expect(lanes(packLanes([clipped(at(9), at(10), 'A'), clipped(at(10), at(11), 'B')]))).toEqual(['A:0/1', 'B:0/1']);
  });
});

describe('isShort', () => {
  it('is true under 40 minutes', () => {
    expect(isShort(clipped(at(9), at(9, 39)))).toBe(true);
    expect(isShort(clipped(at(9), at(9, 40)))).toBe(false);
  });
});

describe('formatRange', () => {
  it('shares the suffix when both ends are on the same half of the day', () => {
    expect(formatRange(at(15), at(17), false)).toBe('3:00 – 5:00 PM');
  });

  it('shows both suffixes across noon', () => {
    expect(formatRange(at(11), at(13), false)).toBe('11:00 AM – 1:00 PM');
  });

  it('uses 24-hour times when asked', () => {
    expect(formatRange(at(15), at(17, 30), true)).toBe('15:00 – 17:30');
  });
});

describe('hourLabel', () => {
  it('labels 12-hour clocks', () => {
    expect([0, 9, 12, 15, 24].map((h) => hourLabel(h, false))).toEqual(['12 AM', '9 AM', '12 PM', '3 PM', '12 AM']);
  });

  it('labels 24-hour clocks', () => {
    expect([0, 9, 15, 24].map((h) => hourLabel(h, true))).toEqual(['00:00', '09:00', '15:00', '00:00']);
  });
});
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd src/main/frontend && npx vitest run src/scenes/calendar; cd -`
Expected: FAIL — cannot resolve `./model`.

- [ ] **Step 4: Write `model.ts`**

`src/main/frontend/src/scenes/calendar/model.ts`:

```ts
import type { CalendarEvent } from '../../types';
import { formatDigital } from '../clock/layout';

/** The timeline never shows fewer hours than this. */
export const MIN_SPAN_HOURS = 6;
/** Shorter events put their title and time on one line. */
export const SHORT_EVENT_MINUTES = 40;

export type Day = 'today' | 'tomorrow';

/** A timed event clipped to one day. */
export interface TimedEvent {
  event: CalendarEvent;
  start: Date;
  end: Date;
}

export interface DayEvents {
  allDay: CalendarEvent[];
  timed: TimedEvent[];
}

/** A timed event placed in one of `laneCount` side-by-side lanes. */
export interface PlacedEvent extends TimedEvent {
  lane: number;
  laneCount: number;
}

export function startOfDay(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

/** Calendar-day arithmetic, so results stay on midnight across daylight-saving changes. */
export function addDays(date: Date, days: number): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate() + days);
}

/** All-day dates (`2026-10-09`) are local midnights; timed values are instants. */
function parseTime(value: string, allDay: boolean): Date {
  if (!allDay) return new Date(value);
  const [year, month, day] = value.split('-').map(Number);
  return new Date(year, month - 1, day);
}

/** The events touching the local day that contains `day`; timed ones clipped to it, in start order. */
export function eventsOn(events: CalendarEvent[], day: Date): DayEvents {
  const dayStart = startOfDay(day);
  const dayEnd = addDays(dayStart, 1);
  const allDay: CalendarEvent[] = [];
  const timed: TimedEvent[] = [];
  for (const event of events) {
    const start = parseTime(event.start, event.allDay);
    const end = parseTime(event.end, event.allDay);
    if (!(start < dayEnd && end > dayStart)) continue;
    if (event.allDay) {
      allDay.push(event);
    } else {
      timed.push({ event, start: start < dayStart ? dayStart : start, end: end > dayEnd ? dayEnd : end });
    }
  }
  timed.sort((a, b) => a.start.getTime() - b.start.getTime() || a.event.title.localeCompare(b.event.title));
  return { allDay, timed };
}

/** `HH:mm` → minutes after midnight. */
function minutesOf(time: string): number {
  const [hours, minutes] = time.split(':').map(Number);
  return hours * 60 + minutes;
}

/**
 * Today while it has a timed event that hasn't ended, or an all-day event before
 * `allDayUntil`; otherwise tomorrow if it has any events; otherwise nothing.
 */
export function chooseDay(events: CalendarEvent[], now: Date, allDayUntil: string): Day | null {
  const today = eventsOn(events, now);
  if (today.timed.some((t) => t.end > now)) return 'today';
  if (today.allDay.length > 0 && now.getHours() * 60 + now.getMinutes() < minutesOf(allDayUntil)) return 'today';
  const tomorrow = eventsOn(events, addDays(now, 1));
  return tomorrow.allDay.length + tomorrow.timed.length > 0 ? 'tomorrow' : null;
}

/** Wall-clock hours since `dayStart`; the next midnight counts as 24. */
export function hourOfDay(date: Date, dayStart: Date): number {
  if (date >= addDays(dayStart, 1)) return 24;
  return date.getHours() + date.getMinutes() / 60 + date.getSeconds() / 3600;
}

/** Whole hours covering every event, at least MIN_SPAN_HOURS long and within the day. `timed` must not be empty. */
export function hourSpan(timed: TimedEvent[], dayStart: Date): { startHour: number; endHour: number } {
  let startHour = Math.floor(Math.min(...timed.map((t) => hourOfDay(t.start, dayStart))));
  let endHour = Math.ceil(Math.max(...timed.map((t) => hourOfDay(t.end, dayStart))));
  if (endHour - startHour < MIN_SPAN_HOURS) {
    endHour = Math.min(24, startHour + MIN_SPAN_HOURS);
    startHour = Math.max(0, endHour - MIN_SPAN_HOURS);
  }
  return { startHour, endHour };
}

/**
 * Splits overlapping events into side-by-side lanes. Each event takes the first
 * free lane; every event in an overlapping group shares the group's lane count.
 * Events that only share an endpoint don't overlap.
 */
export function packLanes(timed: TimedEvent[]): PlacedEvent[] {
  const sorted = [...timed].sort((a, b) => a.start.getTime() - b.start.getTime() || b.end.getTime() - a.end.getTime());
  const placed: PlacedEvent[] = [];
  let group: PlacedEvent[] = [];
  let laneEnds: number[] = [];
  let groupEnd = -Infinity;
  const closeGroup = () => {
    for (const member of group) member.laneCount = laneEnds.length;
    group = [];
    laneEnds = [];
  };
  for (const event of sorted) {
    const start = event.start.getTime();
    const end = event.end.getTime();
    if (start >= groupEnd) closeGroup();
    let lane = laneEnds.findIndex((laneEnd) => laneEnd <= start);
    if (lane === -1) {
      lane = laneEnds.length;
      laneEnds.push(end);
    } else {
      laneEnds[lane] = end;
    }
    const member: PlacedEvent = { ...event, lane, laneCount: 1 };
    group.push(member);
    placed.push(member);
    groupEnd = group.length === 1 ? end : Math.max(groupEnd, end);
  }
  closeGroup();
  return placed;
}

export function isShort(event: TimedEvent): boolean {
  return event.end.getTime() - event.start.getTime() < SHORT_EVENT_MINUTES * 60_000;
}

/** `3:00 – 5:00 PM`, `11:00 AM – 1:00 PM`, or `15:00 – 17:00`. */
export function formatRange(start: Date, end: Date, use24Hour: boolean): string {
  const from = formatDigital(start, use24Hour);
  const to = formatDigital(end, use24Hour);
  if (use24Hour) return `${from.time} – ${to.time}`;
  return from.suffix === to.suffix
    ? `${from.time} – ${to.time} ${to.suffix}`
    : `${from.time} ${from.suffix} – ${to.time} ${to.suffix}`;
}

/** Label for an hour line from 0 to 24. */
export function hourLabel(hour: number, use24Hour: boolean): string {
  const h = hour % 24;
  if (use24Hour) return `${String(h).padStart(2, '0')}:00`;
  return `${h % 12 === 0 ? 12 : h % 12} ${h < 12 ? 'AM' : 'PM'}`;
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd src/main/frontend && npx vitest run src/scenes/calendar && npx tsc --noEmit; cd -`
Expected: all calendar tests PASS; `tsc` reports no errors.

- [ ] **Step 6: Commit**

```bash
git add src/main/frontend/src/types.ts src/main/frontend/src/scenes/calendar
git commit -F - <<'EOF'
Add calendar day choice and timeline layout logic

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 7: Calendar scene, wiring, and demo data

**Files:**
- Create: `src/main/frontend/src/scenes/calendar/calendar.ts`
- Create: `src/main/frontend/src/scenes/calendar/calendar.css`
- Modify: `src/main/frontend/src/types.ts`
- Modify: `src/main/frontend/src/main.ts`
- Modify: `src/main/frontend/src/demo.ts`
- Modify: `README.md`
- Test: `src/main/frontend/src/demo.test.ts`

**Interfaces:**
- Consumes: everything exported by `scenes/calendar/model.ts` (Task 6); the kiosk config and world state JSON from Task 5.
- Produces: `calendarModule: SceneModule` (type `calendar`); `SceneType` includes `'calendar'`; `KioskConfig.calendar: CalendarOptions`; `WorldState.calendar: { events: CalendarEvent[] }`; `demoCalendar(timeMs): CalendarEvent[]`.

- [ ] **Step 1: Extend the shared types**

In `src/main/frontend/src/types.ts`:

```ts
/** Matches net.toadhead.ha.screensaver.config.SceneType ids. */
export type SceneType = 'clock' | 'snakes' | 'calendar';
```

Add:

```ts
export interface CalendarDef {
  id: string;
  label: string;
  /** Block color, as CSS. */
  color: string;
  /** Text color on the block, as CSS. */
  textColor: string;
}

export interface CalendarOptions {
  /** `HH:mm`: after this, an all-day event alone no longer keeps today on screen. */
  allDayUntil: string;
  calendars: CalendarDef[];
}
```

Add `calendar: CalendarOptions;` to `KioskConfig` and `calendar: { events: CalendarEvent[] };` to `WorldState`.

- [ ] **Step 2: Write the failing demo tests**

Append to `src/main/frontend/src/demo.test.ts` (and add `import { addDays, eventsOn } from './scenes/calendar/model';` at the top):

```ts
describe('demo calendar', () => {
  it('only uses configured calendars', () => {
    const ids = DEMO_CONFIG.calendar.calendars.map((c) => c.id);
    for (const event of demoState(Date.now()).calendar.events) {
      expect(ids).toContain(event.calendar);
    }
  });

  it('has events today and tomorrow, including an overlap and an all-day event', () => {
    const noon = new Date(2026, 9, 9, 12, 0).getTime();
    const events = demoState(noon).calendar.events;
    const today = eventsOn(events, new Date(noon));
    const tomorrow = eventsOn(events, addDays(new Date(noon), 1));
    expect(today.allDay.length).toBeGreaterThan(0);
    expect(today.timed.length).toBeGreaterThanOrEqual(4);
    expect(tomorrow.timed.length).toBeGreaterThan(0);
  });

  it('lists the calendar scene', () => {
    expect(DEMO_CONFIG.scenes.map((s) => s.type)).toContain('calendar');
  });
});
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd src/main/frontend && npx vitest run src/demo.test.ts; cd -`
Expected: FAIL — `DEMO_CONFIG.calendar` is undefined.

- [ ] **Step 4: Add demo calendars and events**

In `src/main/frontend/src/demo.ts`, change the type import to `import type { CalendarEvent, KioskConfig, WorldState } from './types';`. Add the scene and the calendar options to `DEMO_CONFIG`:

```ts
  scenes: [
    { type: 'clock', weight: 1, durationMs: 15_000 },
    { type: 'snakes', weight: 2, durationMs: 30_000 },
    { type: 'calendar', weight: 2, durationMs: 20_000 },
  ],
```

```ts
  calendar: {
    allDayUntil: '18:00',
    calendars: [
      { id: 'family', label: 'Family', color: '#3b82f6', textColor: '#ffffff' },
      { id: 'school', label: 'School', color: '#f97316', textColor: '#ffffff' },
      { id: 'work', label: 'Work', color: '#22c55e', textColor: '#ffffff' },
    ],
  },
```

Add this function and include it in `demoState`'s result as `calendar: { events: demoCalendar(timeMs) },`:

```ts
/** Events placed around the current hour, so `?demo&scene=calendar` always has something to show. */
export function demoCalendar(timeMs: number): CalendarEvent[] {
  const now = new Date(timeMs);
  const pad = (n: number) => String(n).padStart(2, '0');
  const date = (dayOffset: number) => {
    const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() + dayOffset);
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  };
  /** Minutes from the start of the current hour today. */
  const fromNow = (minutes: number) =>
    new Date(now.getFullYear(), now.getMonth(), now.getDate(), now.getHours(), minutes).toISOString();
  /** A clock time tomorrow. */
  const tomorrowAt = (hour: number, minute = 0) =>
    new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, hour, minute).toISOString();
  const timed = (title: string, start: string, end: string, calendar: string): CalendarEvent =>
    ({ title, start, end, allDay: false, calendar });
  return [
    { title: "Grandma's birthday", start: date(0), end: date(1), allDay: true, calendar: 'family' },
    timed('Dentist', fromNow(-180), fromNow(-90), 'family'),
    timed('Lunch with Dave', fromNow(-60), fromNow(30), 'work'),
    timed('Soccer practice', fromNow(60), fromNow(180), 'school'),
    timed('Piano lesson', fromNow(90), fromNow(150), 'family'),
    timed('Book club', fromNow(240), fromNow(330), 'family'),
    timed('Soccer game', tomorrowAt(8), tomorrowAt(9, 30), 'school'),
    timed("Farmers' market", tomorrowAt(10), tomorrowAt(11, 30), 'family'),
    timed('Dinner at the Hendersons', tomorrowAt(17), tomorrowAt(18, 45), 'family'),
  ];
}
```

- [ ] **Step 5: Write the scene**

`src/main/frontend/src/scenes/calendar/calendar.ts`:

```ts
import './calendar.css';
import type { Scene, SceneContext, SceneModule } from '../../scene';
import type { CalendarDef, CalendarEvent, KioskConfig, WorldState } from '../../types';
import {
  addDays, chooseDay, type Day, eventsOn, formatRange, hourLabel, hourOfDay, hourSpan, isShort, packLanes, startOfDay,
} from './model';

const NOW_TICK_MS = 30_000;
/** For events whose calendar isn't in the config (shouldn't happen). */
const FALLBACK_COLORS = { color: '#64748b', textColor: '#ffffff' };

export const calendarModule: SceneModule = {
  type: 'calendar',
  isEligible: (config, state) => chooseDay(state.calendar.events, new Date(), config.calendar.allDayUntil) !== null,
  create: () => new CalendarScene(),
};

class CalendarScene implements Scene {
  private root!: HTMLElement;
  private config!: KioskConfig;
  private events: CalendarEvent[] = [];
  private day: Day = 'today';
  private timer = 0;

  mount(container: HTMLElement, ctx: SceneContext): void {
    this.config = ctx.config;
    this.events = ctx.state.calendar.events;
    // Chosen once per appearance.
    this.day = chooseDay(this.events, new Date(), this.config.calendar.allDayUntil) ?? 'today';
    this.root = el('div', `calendar-scene ${this.day}`);
    container.append(this.root);
    this.render();
    if (this.day === 'today') {
      this.timer = window.setInterval(() => this.render(), NOW_TICK_MS);
    }
  }

  update(state: WorldState): void {
    this.events = state.calendar.events;
    this.render();
  }

  unmount(): void {
    window.clearInterval(this.timer);
  }

  private colorsFor(event: CalendarEvent): Pick<CalendarDef, 'color' | 'textColor'> {
    return this.config.calendar.calendars.find((c) => c.id === event.calendar) ?? FALLBACK_COLORS;
  }

  private render(): void {
    const now = new Date();
    const dayStart = startOfDay(this.day === 'today' ? now : addDays(now, 1));
    const { allDay, timed } = eventsOn(this.events, dayStart);
    const use24Hour = this.config.clock.use24Hour;

    this.root.replaceChildren(header(this.day, dayStart));
    this.root.classList.toggle('all-day-only', timed.length === 0);
    if (allDay.length > 0) {
      const strip = el('div', 'all-day');
      for (const event of allDay) strip.append(colored(el('span', 'pill', event.title), this.colorsFor(event)));
      this.root.append(strip);
    }
    if (timed.length === 0) return;

    const { startHour, endHour } = hourSpan(timed, dayStart);
    const top = (hour: number) => `${((hour - startHour) / (endHour - startHour)) * 100}%`;
    const grid = el('div', 'grid');
    for (let hour = startHour; hour <= endHour; hour++) {
      const line = el('div', 'hour');
      line.style.top = top(hour);
      line.append(el('span', 'label', hourLabel(hour, use24Hour)));
      grid.append(line);
    }
    for (const placed of packLanes(timed)) {
      const from = top(hourOfDay(placed.start, dayStart));
      const to = top(hourOfDay(placed.end, dayStart));
      const block = colored(el('div', 'event'), this.colorsFor(placed.event));
      block.classList.toggle('past', this.day === 'today' && placed.end <= now);
      block.classList.toggle('short', isShort(placed));
      block.style.top = from;
      block.style.height = `calc(${to} - ${from} - 2px)`;
      block.style.left = `${(placed.lane / placed.laneCount) * 100}%`;
      block.style.width = `calc(${100 / placed.laneCount}% - 4px)`;
      block.append(
        el('span', 'title', placed.event.title),
        el('span', 'time', formatRange(placed.start, placed.end, use24Hour)),
      );
      grid.append(block);
    }
    if (this.day === 'today') {
      const hour = hourOfDay(now, dayStart);
      if (hour >= startHour && hour <= endHour) {
        const line = el('div', 'now');
        line.style.top = top(hour);
        grid.append(line);
      }
    }
    this.root.append(grid);
  }
}

function el(tag: string, className: string, text?: string): HTMLElement {
  const node = document.createElement(tag);
  node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function colored(node: HTMLElement, colors: Pick<CalendarDef, 'color' | 'textColor'>): HTMLElement {
  node.style.background = colors.color;
  node.style.color = colors.textColor;
  return node;
}

function header(day: Day, dayStart: Date): HTMLElement {
  const head = el('div', 'head');
  head.append(
    el('span', 'day-tag', day === 'today' ? 'Today' : 'Tomorrow'),
    el('span', 'date', dayStart.toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric' })),
  );
  return head;
}
```

`src/main/frontend/src/scenes/calendar/calendar.css`:

```css
/* Modern look on purpose: the DOS text-mode style belongs to snakes and the clock. */
.calendar-scene {
  position: absolute; inset: 0; box-sizing: border-box;
  display: flex; flex-direction: column;
  padding: 5vmin 6vmin 4vmin;
  color: #ffffff;
  font-family: system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif;
  background: linear-gradient(160deg, #141a3a 0%, #2a1645 100%);
}
.calendar-scene.tomorrow { background: linear-gradient(160deg, #3a2410 0%, #5a1f3a 100%); }

.calendar-scene .head { display: flex; align-items: baseline; gap: 2vmin; margin-bottom: 3vmin; }
.calendar-scene .day-tag {
  font-size: 2.6vmin; font-weight: 700; letter-spacing: 0.12em; text-transform: uppercase;
  padding: 0.5vmin 1.6vmin; border-radius: 1vmin; background: rgba(255, 255, 255, 0.14);
}
.calendar-scene.tomorrow .day-tag { background: #ffb547; color: #2a1406; }
.calendar-scene .date { font-size: 6vmin; font-weight: 700; }

.calendar-scene .all-day { display: flex; flex-wrap: wrap; gap: 1.2vmin; margin: 0 0 3vmin 9vmin; }
.calendar-scene .pill { font-size: 2.4vmin; font-weight: 600; padding: 0.7vmin 2vmin; border-radius: 999px; }
.calendar-scene.all-day-only .all-day { flex: 1; align-content: flex-start; margin-left: 0; gap: 2.4vmin; }
.calendar-scene.all-day-only .pill { font-size: 4.5vmin; padding: 1.4vmin 3.5vmin; }

.calendar-scene .grid { position: relative; flex: 1; margin-left: 9vmin; }
.calendar-scene .hour { position: absolute; left: 0; right: 0; border-top: 1px solid rgba(255, 255, 255, 0.08); }
.calendar-scene .hour .label {
  position: absolute; right: calc(100% + 1.5vmin); top: -1.1vmin;
  font-size: 1.8vmin; color: rgba(255, 255, 255, 0.5); white-space: nowrap;
}
.calendar-scene .event {
  position: absolute; box-sizing: border-box; overflow: hidden;
  min-height: 3.4vmin; padding: 0.6vmin 1.4vmin; border-radius: 1vmin;
  box-shadow: 0 0.6vmin 2vmin rgba(0, 0, 0, 0.3);
  display: flex; flex-direction: column; line-height: 1.25;
}
.calendar-scene .event .title {
  font-size: 2.4vmin; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.calendar-scene .event .time { font-size: 1.9vmin; opacity: 0.85; white-space: nowrap; }
.calendar-scene .event.short { flex-direction: row; align-items: baseline; gap: 1.2vmin; }
.calendar-scene .event.past { opacity: 0.4; }
.calendar-scene .now { position: absolute; left: -1vmin; right: 0; border-top: 0.35vmin solid #ffffff; z-index: 1; }
.calendar-scene .now::before {
  content: ''; position: absolute; left: -0.8vmin; top: -1.15vmin;
  width: 2vmin; height: 2vmin; border-radius: 50%; background: #ffffff;
}
```

- [ ] **Step 6: Register the scene and fix the fallbacks in `main.ts`**

Add `import { calendarModule } from './scenes/calendar/calendar';` and change:

```ts
const MODULES: Record<SceneType, SceneModule> = { clock: clockModule, snakes: snakesModule, calendar: calendarModule };
```

In `showFallbackClock`, add `calendar: { allDayUntil: '18:00', calendars: [] },` to the fallback `KioskConfig`, and change the fallback state to `state ?? { haConnected: false, snakes: [], calendar: { events: [] } }`.

- [ ] **Step 7: Update the README**

In `README.md`, change "This version has the clock and the NetWare-style snakes." to "This version has the clock, the NetWare-style snakes, and today's (or tomorrow's) calendar.", add `docs/superpowers/specs/2026-10-09-calendar-scene-design.md` after the existing design link, and change the `?scene=` line to:

```markdown
- `?scene=snakes`, `?scene=clock`, or `?scene=calendar` — show only that scene.
```

- [ ] **Step 8: Run the frontend checks**

Run: `cd src/main/frontend && npx tsc --noEmit && npm test; cd -`
Expected: no type errors; all Vitest tests PASS.

- [ ] **Step 9: Look at it in a browser**

Run: `cd src/main/frontend && npx vite --port 5173; cd -` (leave it running), then open `http://localhost:5173/?demo&scene=calendar`.

Check against the spec (§2.2–2.4): navy-to-purple background with a translucent TODAY tag and the full date; a birthday pill above the timeline; Dentist dimmed; Lunch spanning the white "now" line; Soccer practice and Piano lesson side by side; hour labels on the left. To see tomorrow, temporarily change `allDayUntil` in `DEMO_CONFIG` to `'00:00'` and remove the today timed events from `demoCalendar` in your editor (do not commit that): the sunset background and the amber TOMORROW tag must appear. Stop the dev server with Ctrl-C.

- [ ] **Step 10: Run the full build**

Run: `./mvnw -q package`
Expected: BUILD SUCCESS; all Java and Vitest tests pass.

- [ ] **Step 11: Commit**

```bash
git add README.md src/main/frontend/src
git commit -F - <<'EOF'
Add the calendar scene

A modern timeline of today's events, switching to tomorrow on a sunset
background once today is done. Overlapping events sit side by side, past
events are dimmed, and a line marks the current time.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 8: Verify against the real Home Assistant

**Files:** none (verification only).

- [ ] **Step 1: Add calendars to the local config**

In `src/main/resources/application-local.yml` (git-ignored), add two or three real calendar entities under `screensaver:` (ask the user which entity ids to use), plus the calendar scene under the existing `rotation.scenes`. Do not commit this file.

```yaml
  calendar:
    calendars:
      family:
        entity: calendar.<real entity>
        label: Family
      school:
        entity: calendar.<real entity>
        label: School
```

```yaml
      calendar: { weight: 2, duration: 20s }
```

- [ ] **Step 2: Run the app against Home Assistant**

Run: `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`
Expected in the log: `Home Assistant's time zone is <zone>` after authenticating, and no `Could not read calendar` warnings for correctly spelled entities.

- [ ] **Step 3: Check the stream**

Run: `curl -sN 'http://localhost:8080/api/stream?kiosk=default' | head -c 4000`
Expected: the `config` event contains `"calendar":{"allDayUntil":"18:00","calendars":[...]}` with hex colors; the `state` event contains `"calendar":{"events":[...]}` with today's and tomorrow's events (all-day ones as plain dates).

- [ ] **Step 4: Look at the scene**

Open `http://localhost:8080/?scene=calendar` and compare with the real calendars in Home Assistant. Stop the app afterwards.
