# Low Batteries Scene — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a scene that shows every dead or nearly dead battery as a row of battery drawings, named by the device each battery powers.

**Architecture:** The Home Assistant session also loads the entity and device registries (and reloads them when Home Assistant says they changed), so the backend can map a battery entity to its device's name. A pure detector turns entity states + registry + settings + the current time into a sorted `lowBatteries` list in the world state, re-evaluated on every state change and once a minute (a battery becomes "dead" just by staying `unavailable`). The browser draws up to 10 batteries in one or two rows of SVG drawings.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Jackson 3), JDK `java.net.http.WebSocket`, JUnit 6 + AssertJ; TypeScript 6.0.3, Vite 8.3.1, Vitest 5.0.1 (Node environment, no DOM).

**Spec:** `docs/superpowers/specs/2026-11-10-low-batteries-scene-design.md` (extends `docs/superpowers/specs/2026-10-08-ha-screensaver-design.md`). Approved mockups: `.superpowers/brainstorm/3099715-1794349814/content/battery-layout.html` (option B) and `battery-layout-v2.html`.

## Global Constraints

- Base package `net.toadhead.ha.screensaver`; build with `./mvnw`. Backend-only test runs add `-Dskip.npm -Dskip.installnodenpm`. Frontend tests: `cd src/main/frontend && npx vitest run`.
- Spring Boot 4.1.1 uses **Jackson 3**: `tools.jackson.databind.*` (`JsonMapper`, `JsonNode`, `ObjectNode`); annotations stay `com.fasterxml.jackson.annotation.*`. Use `asString("")`, `asInt(default)`, `asBoolean(default)`; never `asText()`.
- Configuration is plain `@ConfigurationProperties("screensaver")` records, `@Validated`, flat. Changes apply on restart.
- Settings, exactly: `screensaver.batteries.threshold` (default `10`, 1–100, strictly below is low), `screensaver.batteries.dead-after` (default `1h`, positive), `screensaver.batteries.exclude` (list of entity ids, default empty).
- Scene type `LOW_BATTERIES`, JSON id `low-batteries`, default weight `3`, default duration `20s`.
- Look: modern system sans-serif (like the calendar), **not** the DOS/VGA style. Background `linear-gradient(160deg, #10302c 0%, #1c2348 100%)`. Heading text exactly `Running low`. Low fill `#ffb547`. Dead outline dashed and dimmed gray with "z z" and the word `DEAD`; low binary sensors show `LOW`; low sensors show the level as `N%`. No red, no flashing. Dead versus low must differ in shape and wording, not only color.
- At most 5 batteries per row, at most 10 drawn; more than 10 adds `and N more`.
- Screens never show error text.
- Every commit message ends with a blank line and then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Home Assistant restarts and every battery goes `unavailable` at once**: nothing may be listed as dead until `dead-after` has passed. Pinned by `unavailableIsDeadOnlyAfterDeadAfter` (Task 3) and `tickPicksUpBatteriesThatBecomeDead` (Task 4).
2. **The registry fetch fails or isn't supported** (older Home Assistant, permissions): the connection must stay up and names fall back to friendly names. Pinned by `registryFailureIsNotFatal` (Task 2) and `namesFallBackWithoutRegistry` (Task 3).
3. **A burst of registry-updated events** (an integration reload renames dozens of entities): the session must not fire dozens of overlapping registry fetches. Pinned by `coalescesRegistryReloadsWhileOneIsInFlight` (Task 2).
4. **A battery at 9.6%** must count as low and show `9%`, never a `10%` that looks above the threshold. Pinned by `thresholdIsStrictAndLevelRoundsDown` (Task 3).
5. **A battery entity with no `last_changed`** (malformed or restored state) must not be reported dead forever. Pinned by `unavailableWithoutLastChangedIsNotDead` (Task 3).

---

### Task 1: Battery settings and the scene type

**Files:**
- Modify: `src/main/java/net/toadhead/ha/screensaver/config/ScreensaverProperties.java`
- Modify: `src/main/java/net/toadhead/ha/screensaver/config/SceneType.java`
- Modify: `src/test/java/net/toadhead/ha/screensaver/config/TestProperties.java`
- Modify: `src/test/java/net/toadhead/ha/screensaver/kiosk/KioskConfigServiceTests.java` (constructor calls and scene-list assertions)
- Modify: `config/application.example.yaml`
- Test: `src/test/java/net/toadhead/ha/screensaver/config/ScreensaverPropertiesTests.java`

**Interfaces:**
- Produces: `ScreensaverProperties.batteries()` returning `record Batteries(int threshold, Duration deadAfter, List<String> exclude)`; `ScreensaverProperties` gains the component `@Valid @DefaultValue Batteries batteries` **directly after `calendar`**; `SceneType.LOW_BATTERIES`; `TestProperties.defaultBatteries()` returning `new Batteries(10, Duration.ofHours(1), List.of())`.

- [ ] **Step 1: Write the failing tests**

Add to `ScreensaverPropertiesTests`:

```java
@Test
void bindsBatteryDefaults() {
	this.runner.run((context) -> {
		ScreensaverProperties.Batteries batteries = context.getBean(ScreensaverProperties.class).batteries();
		assertThat(batteries.threshold()).isEqualTo(10);
		assertThat(batteries.deadAfter()).isEqualTo(Duration.ofHours(1));
		assertThat(batteries.exclude()).isEmpty();
	});
}

@Test
void bindsBatterySettings() {
	this.runner
		.withPropertyValues("screensaver.batteries.threshold=15", "screensaver.batteries.dead-after=30m",
				"screensaver.batteries.exclude[0]=sensor.phone_battery_level",
				"screensaver.rotation.scenes.low-batteries.weight=4")
		.run((context) -> {
			ScreensaverProperties properties = context.getBean(ScreensaverProperties.class);
			assertThat(properties.batteries().threshold()).isEqualTo(15);
			assertThat(properties.batteries().deadAfter()).isEqualTo(Duration.ofMinutes(30));
			assertThat(properties.batteries().exclude()).containsExactly("sensor.phone_battery_level");
			assertThat(properties.rotation().scenes().get(SceneType.LOW_BATTERIES).weight()).isEqualTo(4.0);
		});
}

@Test
void rejectsThresholdOutOfRange() {
	assertFails("must be greater than or equal to 1", "screensaver.batteries.threshold=0");
	assertFails("must be less than or equal to 100", "screensaver.batteries.threshold=101");
}

@Test
void rejectsNonPositiveDeadAfter() {
	assertFails("dead-after must be positive", "screensaver.batteries.dead-after=0s");
}
```

In `KioskConfigServiceTests`, add `new SceneConfig(SceneType.LOW_BATTERIES, 3, 20_000)` as the last entry of the default scene-list assertion in `usesSceneTypeDefaultsWhenNothingIsConfigured`, and `SceneType.LOW_BATTERIES` as the last entry of the `containsExactly(SceneType.CLOCK, SceneType.CALENDAR)` assertions in `kioskCanDisableAScene` and `sceneDisabledInRotationStaysDisabledWhenKioskOnlyChangesWeight`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dskip.npm -Dskip.installnodenpm -Dtest='ScreensaverPropertiesTests,KioskConfigServiceTests'`
Expected: compilation failure (`batteries()` and `LOW_BATTERIES` don't exist).

- [ ] **Step 3: Add `Batteries` and `LOW_BATTERIES`**

- `record Batteries(@DefaultValue("10") @Min(1) @Max(100) int threshold, @DefaultValue("1h") Duration deadAfter, List<String> exclude)`, with `exclude` defaulting to `List.of()` (copy it) and `@AssertTrue(message = "dead-after must be positive") isDeadAfterPositive()`. Javadoc each parameter as the other records do.
- `LOW_BATTERIES(3, Duration.ofSeconds(20))` after `CALENDAR` in `SceneType`.
- Add `TestProperties.defaultBatteries()` and pass it in `withSnakes` and in the three `new ScreensaverProperties(...)` calls in `KioskConfigServiceTests`.
- In `config/application.example.yaml`, add after the `calendar:` block:

```yaml
  batteries:
    threshold: 10            # percent; strictly below this counts as low
    dead-after: 1h           # how long a battery must stay unavailable to count as dead
    exclude:                 # battery entity ids never shown (a phone reports its own battery)
      - sensor.phone_battery_level
```

and `low-batteries: { weight: 3, duration: 20s }` under `rotation.scenes`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw test -Dskip.npm -Dskip.installnodenpm -Dtest='ScreensaverPropertiesTests,KioskConfigServiceTests'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/toadhead/ha/screensaver/config src/test/java/net/toadhead/ha/screensaver/config src/test/java/net/toadhead/ha/screensaver/kiosk config/application.example.yaml
git commit -m "Add battery settings and the low-batteries scene type"
```

---

### Task 2: Device and entity registries from Home Assistant

**Files:**
- Create: `src/main/java/net/toadhead/ha/screensaver/ha/DeviceRegistry.java`
- Modify: `src/main/java/net/toadhead/ha/screensaver/ha/HaSession.java`
- Modify: `src/main/java/net/toadhead/ha/screensaver/ha/HomeAssistantState.java`
- Modify: `src/main/java/net/toadhead/ha/screensaver/ha/HomeAssistantConnector.java` (the inner `Connection` listener)
- Modify: `src/test/java/fakeha/FakeHomeAssistant.java`
- Test: `src/test/java/net/toadhead/ha/screensaver/ha/HaSessionTests.java`, `HomeAssistantStateTests.java`, `DeviceRegistryTests.java` (new)

**Interfaces:**
- Produces:
  - `public record DeviceRegistry(Map<String, String> deviceIds, Map<String, String> deviceNames)` — entity id → device id, device id → display name. `public static final DeviceRegistry EMPTY`; `public Optional<String> deviceId(String entityId)`; `public Optional<String> deviceName(String deviceId)`; `static DeviceRegistry fromJson(JsonNode entities, JsonNode devices)`.
  - `HaSession.Listener.onRegistry(DeviceRegistry registry)`.
  - `HomeAssistantState.all(): Collection<EntityState>` (a snapshot copy), `registry(): DeviceRegistry` (initially `DeviceRegistry.EMPTY`), `setRegistry(DeviceRegistry)`. The registry is kept across reconnects.

- [ ] **Step 1: Write the failing tests**

`DeviceRegistryTests`:

```java
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
```

`HomeAssistantStateTests`: `allReturnsEveryEntity` (after `markConnected` of two entities, `all()` has both), and `registryStartsEmptyAndSurvivesReconnect` (`registry()` is `EMPTY`; after `setRegistry(r)`, `markDisconnected()` and `markConnected(...)`, it is still `r`).

`HaSessionTests` — after `auth_ok`, requests 1–3 are unchanged; the session then sends, in order:

```java
@Test
void subscribesToRegistryChangesAndLoadsRegistriesAfterStates() {
	this.session.onMessage("{\"type\":\"auth_ok\"}");
	assertThat(sent(3).path("type").asString("")).isEqualTo("subscribe_events");
	assertThat(sent(3).path("event_type").asString("")).isEqualTo("entity_registry_updated");
	assertThat(sent(4).path("event_type").asString("")).isEqualTo("device_registry_updated");
	assertThat(sent(5).path("type").asString("")).isEqualTo("config/entity_registry/list");
	assertThat(sent(5).path("id").asInt(-1)).isEqualTo(6);
	assertThat(sent(6).path("type").asString("")).isEqualTo("config/device_registry/list");
	assertThat(sent(6).path("id").asInt(-1)).isEqualTo(7);
}

@Test
void reportsTheRegistry() {
	this.session.onMessage("{\"type\":\"auth_ok\"}");
	answerRegistries();
	assertThat(this.listener.registry.deviceId("sensor.lock_battery")).contains("d1");
	assertThat(this.listener.registry.deviceName("d1")).contains("Back door lock");
}

@Test
void registryFailureIsNotFatal() {
	this.session.onMessage("{\"type\":\"auth_ok\"}");
	this.session.onMessage("{\"id\":6,\"type\":\"result\",\"success\":false,\"error\":{\"message\":\"Unknown command\"}}");
	this.session.onMessage("{\"id\":7,\"type\":\"result\",\"success\":true,\"result\":[]}");
	assertThat(this.listener.registry).isNull();
	assertThat(this.listener.setupFailures).isEmpty();
}

@Test
void reloadsRegistriesWhenHomeAssistantSaysTheyChanged() {
	this.session.onMessage("{\"type\":\"auth_ok\"}");
	answerRegistries();
	this.session.onMessage("{\"id\":4,\"type\":\"event\",\"event\":{\"event_type\":\"entity_registry_updated\",\"data\":{}}}");
	assertThat(sent(7).path("type").asString("")).isEqualTo("config/entity_registry/list");
	assertThat(sent(8).path("type").asString("")).isEqualTo("config/device_registry/list");
}

@Test
void coalescesRegistryReloadsWhileOneIsInFlight() {
	this.session.onMessage("{\"type\":\"auth_ok\"}");
	for (int i = 0; i < 5; i++) {
		this.session.onMessage("{\"id\":5,\"type\":\"event\",\"event\":{\"event_type\":\"device_registry_updated\",\"data\":{}}}");
	}
	assertThat(this.sent).hasSize(7);  // nothing new while the first load is in flight
	answerRegistries();
	assertThat(this.sent).hasSize(9);  // exactly one reload afterwards
}
```

Add a private helper `answerRegistries()` that sends successful `result` messages for ids 6 (`[{"entity_id":"sensor.lock_battery","device_id":"d1"}]`) and 7 (`[{"id":"d1","name":"Schlage BE469","name_by_user":"Back door lock"}]`), and a `DeviceRegistry registry` field in `RecordingListener` set by `onRegistry`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dskip.npm -Dskip.installnodenpm -Dtest='DeviceRegistryTests,HomeAssistantStateTests,HaSessionTests'`
Expected: compilation failure.

- [ ] **Step 3: Implement**

- `DeviceRegistry.fromJson`: entity ids with a non-null, non-blank `device_id`; device name is `name_by_user` if non-blank, else `name` if non-blank, else absent.
- `HaSession.subscribeAndLoad()`: after the `get_states` request, subscribe to `entity_registry_updated` and `device_registry_updated` (record both ids in a set; a failed subscription is logged with `logger.warn`, not reported as a setup failure), then `loadRegistries()`.
- `loadRegistries()`: if a load is in flight, set a "reload requested" flag and return. Otherwise send `config/entity_registry/list` and `config/device_registry/list`, combine the results with `DeviceRegistry.fromJson`, call `listener.onRegistry`. On failure log `Could not read Home Assistant's device registry: {}` at warn. When the load finishes (either way), if a reload was requested, clear the flag and load again.
- `onEvent`: the existing state-change path only for `subscriptionId`; for a registry subscription id, call `loadRegistries()`.
- `Connection.onRegistry`: `state.setRegistry(registry)`, then `publish()`.
- `FakeHomeAssistant`: in `subscribe_events`, set `subscriptionId` only when `event_type` is `state_changed` (otherwise registry subscriptions would steal the state-event id), and answer `config/entity_registry/list` and `config/device_registry/list` with `result(id, List.of())`.

- [ ] **Step 4: Run all `ha` tests to verify they pass**

Run: `./mvnw test -Dskip.npm -Dskip.installnodenpm -Dtest='DeviceRegistryTests,HomeAssistantStateTests,HaSessionTests,HomeAssistantConnectorTests'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/toadhead/ha/screensaver/ha src/test/java/net/toadhead/ha/screensaver/ha src/test/java/fakeha
git commit -m "Load Home Assistant's device and entity registries"
```

---

### Task 3: Low-battery detector

**Files:**
- Create: `src/main/java/net/toadhead/ha/screensaver/batteries/LowBattery.java`
- Create: `src/main/java/net/toadhead/ha/screensaver/batteries/LowBatteryDetector.java`
- Test: `src/test/java/net/toadhead/ha/screensaver/batteries/LowBatteryDetectorTests.java`

**Interfaces:**
- Consumes: `EntityState`, `DeviceRegistry` (Task 2); `ScreensaverProperties.Batteries` (Task 1).
- Produces:
  - `public record LowBattery(String id, String name, Status status, Integer level)` with nested `public enum Status { DEAD, LOW }` whose `@JsonValue id()` is the lowercase name (`"dead"`, `"low"`).
  - `public final class LowBatteryDetector` with `public static List<LowBattery> detect(Collection<EntityState> states, DeviceRegistry registry, Batteries settings, Instant now)`.

- [ ] **Step 1: Write the failing tests**

Helpers in the test: `NOW = Instant.parse("2026-11-10T20:00:00Z")`; `SETTINGS = new Batteries(10, Duration.ofHours(1), List.of())`; `percent(id, state, friendlyName)` builds a `sensor.*` `EntityState` with attributes `{"device_class":"battery","unit_of_measurement":"%","friendly_name":…}` and `lastChanged = NOW.minus(2h)`; `binary(id, state, friendlyName)` the same for `binary_sensor.*` without a unit; `.changedAgo(Duration)` or an overload sets `lastChanged`.

```java
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
void ignoresEntitiesThatAreNotPercentageBatteries()  // device_class temperature; unit "V"; no device_class;
                                                      // a light.* with battery attributes -> all empty

@Test
void lowBinarySensorHasNoLevel() {
	assertThat(detect(binary("binary_sensor.remote_battery", "on", "Hall remote battery")))
		.containsExactly(new LowBattery("binary_sensor.remote_battery", "Hall remote battery", Status.LOW, null));
	assertThat(detect(binary("binary_sensor.remote_battery", "off", "Hall remote battery"))).isEmpty();
}

@Test
void unavailableIsDeadOnlyAfterDeadAfter() {
	// percent(...,"unavailable",...) changed exactly 1h ago -> DEAD with level null; changed 59m ago -> empty.
	// The same for a binary sensor.
}

@Test
void unavailableWithoutLastChangedIsNotDead()  // lastChanged null -> empty

@Test
void unknownAndNonNumericStatesAreIgnored()  // "unknown", "abc", "NaN", "Infinity", "" -> empty

@Test
void excludedEntitiesAreNeverListed()  // exclude contains both a low and a dead entity -> empty

@Test
void namesComeFromTheDevice() {
	// registry: sensor.lock_battery -> d1 -> "Back door lock"
	// -> new LowBattery("d1", "Back door lock", Status.LOW, 5)
}

@Test
void namesFallBackWithoutRegistry() {
	// DeviceRegistry.EMPTY: name is friendly_name; with no friendly_name, the entity id.
	// Entity mapped to a device the registry has no name for: friendly_name.
}

@Test
void oneEntryPerDevice() {
	// d1 has sensor "5" and binary "on" -> one entry ("d1", name, LOW, 5)
	// d2 has sensor "unavailable" (2h) and binary "on" -> one entry DEAD, level null
	// d3 has sensor "50" and binary "on" -> one entry LOW, level null (only *low* sensors give the level;
	//    "50%" on a "Running low" screen would contradict itself)
}

@Test
void ordersDeadThenLevellessLowThenAscendingLevel() {
	// dead "office window", dead "Back door lock", binary-low "Hall remote", 0% "Kitchen motion",
	// 7% "basement leak", 7% "Attic", 3% "Porch"
	// -> names in order: Back door lock, office window, Hall remote, Kitchen motion, Porch, Attic, basement leak
	//    (ties case-insensitive alphabetical)
}
```

Write each commented test out in full with the values given; the comments are the assertions.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dskip.npm -Dskip.installnodenpm -Dtest=LowBatteryDetectorTests`
Expected: compilation failure.

- [ ] **Step 3: Implement `LowBatteryDetector.detect`**

Per spec §2: a battery entity is `sensor.*` with `device_class` `battery` and `unit_of_measurement` `%`, or `binary_sensor.*` with `device_class` `battery`; skip excluded ids. Dead: state `unavailable` and `lastChanged` non-null and `!now.isBefore(lastChanged.plus(deadAfter))`. Low: sensor value finite and `< threshold` (level = `(int) Math.floor(value)`), or binary state `on` (level null). Group by `registry.deviceId(entityId)` falling back to the entity id; a group is dead if any member is dead, otherwise low with level = the minimum level of its low sensors (null if none). Name: `registry.deviceName(deviceId)`, else the first member's `friendly_name`, else its entity id. Sort with a comparator: dead before low; among low, null level before numbers; then level ascending; then name with `String.CASE_INSENSITIVE_ORDER`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw test -Dskip.npm -Dskip.installnodenpm -Dtest=LowBatteryDetectorTests`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/toadhead/ha/screensaver/batteries src/test/java/net/toadhead/ha/screensaver/batteries
git commit -m "Detect dead and low batteries"
```

---

### Task 4: Low batteries in the world state, re-checked every minute

**Files:**
- Modify: `src/main/java/net/toadhead/ha/screensaver/stream/WorldState.java`
- Modify: `src/main/java/net/toadhead/ha/screensaver/stream/WorldStateAssembler.java`
- Modify: `src/main/java/net/toadhead/ha/screensaver/stream/WorldStateService.java`
- Create: `src/test/java/net/toadhead/ha/screensaver/MutableClock.java` (moved out of `CalendarPollerTests`)
- Modify: `src/test/java/net/toadhead/ha/screensaver/calendar/CalendarPollerTests.java` (use the shared `MutableClock`)
- Test: `src/test/java/net/toadhead/ha/screensaver/stream/WorldStateAssemblerTests.java`, `WorldStateServiceTests.java`

**Interfaces:**
- Consumes: `LowBatteryDetector.detect`, `LowBattery` (Task 3); `HomeAssistantState.all()`, `registry()` (Task 2); `ScreensaverProperties.batteries()` (Task 1).
- Produces:
  - `WorldState(boolean haConnected, List<SnakeValue> snakes, Calendar calendar, List<LowBattery> lowBatteries)` — JSON field `lowBatteries`, items `{id, name, status: "dead"|"low", level: int|null}`.
  - `WorldStateAssembler.assemble(ScreensaverProperties properties, HomeAssistantState ha, List<CalendarEvent> calendarEvents, Instant now)`.
  - `WorldStateService(properties, ha, calendar)` stays public and delegates to a package-private `WorldStateService(properties, ha, calendar, Clock clock)`; new `@Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES) public void tick()` that calls `refresh()`.
  - `net.toadhead.ha.screensaver.MutableClock` (test sources, public): `MutableClock(Instant)`, `set(Instant)`, `advance(Duration)` plus the `Clock` methods. Keep whatever methods `CalendarPollerTests` already uses.

- [ ] **Step 1: Write the failing tests**

`WorldStateAssemblerTests` (pass `Instant.now()` to the existing `assemble` calls):

```java
@Test
void includesLowBatteries() {
	this.ha.markConnected(List.of(new EntityState("sensor.porch_battery", "4",
			json("{\"device_class\":\"battery\",\"unit_of_measurement\":\"%\",\"friendly_name\":\"Porch motion\"}"),
			Instant.now())));
	WorldState state = WorldStateAssembler.assemble(TestProperties.withSnakes(Map.of(), Map.of()), this.ha,
			List.of(), Instant.now());
	assertThat(state.lowBatteries())
		.containsExactly(new LowBattery("sensor.porch_battery", "Porch motion", LowBattery.Status.LOW, 4));
}

@Test
void serializesLowBatteriesForTheBrowser() {
	// JSON of a WorldState with LowBattery("d1", "Back door lock", DEAD, null):
	// lowBatteries[0].status == "dead", level is JSON null, name == "Back door lock"
}
```

`WorldStateServiceTests`:

```java
@Test
void tickPicksUpBatteriesThatBecomeDead() {
	MutableClock clock = new MutableClock(Instant.parse("2026-11-10T20:00:00Z"));
	WorldStateService service = new WorldStateService(TestProperties.withSnakes(Map.of(), Map.of()), this.ha,
			this.calendar, clock);
	this.ha.markConnected(List.of(new EntityState("sensor.lock_battery", "unavailable",
			json("{\"device_class\":\"battery\",\"unit_of_measurement\":\"%\"}"), clock.instant())));
	service.onHomeAssistantUpdated(new HomeAssistantUpdated());
	assertThat(service.current().state().lowBatteries()).isEmpty();
	clock.advance(Duration.ofMinutes(59));
	service.tick();
	assertThat(service.current().state().lowBatteries()).isEmpty();
	clock.advance(Duration.ofMinutes(1));
	service.tick();
	assertThat(service.current().state().lowBatteries()).extracting(LowBattery::status)
		.containsExactly(LowBattery.Status.DEAD);
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dskip.npm -Dskip.installnodenpm -Dtest='WorldStateAssemblerTests,WorldStateServiceTests,CalendarPollerTests'`
Expected: compilation failure.

- [ ] **Step 3: Implement**

Add the `lowBatteries` component (javadoc: "dead and low batteries, sorted for display"); `assemble` calls `LowBatteryDetector.detect(ha.all(), ha.registry(), properties.batteries(), now)`; the service passes `this.clock.instant()` from both the constructor and `refresh()`, and the public constructor uses `Clock.systemUTC()`. Move `MutableClock` out of `CalendarPollerTests`.

- [ ] **Step 4: Run the whole backend suite**

Run: `./mvnw test -Dskip.npm -Dskip.installnodenpm`
Expected: PASS (all tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/toadhead/ha/screensaver/stream src/test/java/net/toadhead/ha/screensaver
git commit -m "Stream low batteries to the kiosks"
```

---

### Task 5: Low-battery layout rules (frontend)

**Files:**
- Modify: `src/main/frontend/src/types.ts`
- Create: `src/main/frontend/src/scenes/batteries/model.ts`
- Test: `src/main/frontend/src/scenes/batteries/model.test.ts`

**Interfaces:**
- Consumes: the world-state JSON from Task 4.
- Produces:
  - `types.ts`: `SceneType` adds `'low-batteries'`; `export interface LowBattery { id: string; name: string; status: 'dead' | 'low'; level: number | null }`; `WorldState.lowBatteries: LowBattery[]`.
  - `model.ts`: `MAX_SHOWN = 10`, `PER_ROW = 5`; `layout(count: number): { shown: number; rows: number; more: number }`; `batteryLabel(battery: LowBattery): string`; `fillFraction(battery: LowBattery): number`.

- [ ] **Step 1: Write the failing tests**

```ts
describe('layout', () => {
  it('uses one row for up to five', () => {
    expect(layout(1)).toEqual({ shown: 1, rows: 1, more: 0 });
    expect(layout(5)).toEqual({ shown: 5, rows: 1, more: 0 });
  });
  it('wraps to two rows from six to ten', () => {
    expect(layout(6)).toEqual({ shown: 6, rows: 2, more: 0 });
    expect(layout(10)).toEqual({ shown: 10, rows: 2, more: 0 });
  });
  it('draws at most ten and counts the rest', () => {
    expect(layout(12)).toEqual({ shown: 10, rows: 2, more: 2 });
  });
});

describe('batteryLabel', () => {
  it('says DEAD, LOW, or the level', () => {
    expect(batteryLabel({ id: 'a', name: 'A', status: 'dead', level: null })).toBe('DEAD');
    expect(batteryLabel({ id: 'a', name: 'A', status: 'low', level: null })).toBe('LOW');
    expect(batteryLabel({ id: 'a', name: 'A', status: 'low', level: 0 })).toBe('0%');
    expect(batteryLabel({ id: 'a', name: 'A', status: 'low', level: 7 })).toBe('7%');
  });
});

describe('fillFraction', () => {
  it('follows the level but always leaves a visible sliver', () => {
    expect(fillFraction({ id: 'a', name: 'A', status: 'low', level: 9 })).toBeCloseTo(0.09);
    expect(fillFraction({ id: 'a', name: 'A', status: 'low', level: 0 })).toBe(0.03);
    expect(fillFraction({ id: 'a', name: 'A', status: 'low', level: null })).toBe(0.03);
    expect(fillFraction({ id: 'a', name: 'A', status: 'dead', level: null })).toBe(0);
  });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd src/main/frontend && npx vitest run src/scenes/batteries/model.test.ts`
Expected: FAIL (module not found).

- [ ] **Step 3: Implement the types and `model.ts`**

`fillFraction`: 0 for dead, otherwise `Math.max(0.03, (level ?? 0) / 100)`. Adding the required `lowBatteries` to `WorldState` makes `main.ts` and `demo.ts` fail type-checking until Task 6 fixes them; Vitest doesn't type-check, so this task's tests still run.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd src/main/frontend && npx vitest run src/scenes/batteries/model.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/frontend/src/types.ts src/main/frontend/src/scenes/batteries
git commit -m "Add low-battery layout rules"
```

---

### Task 6: Low-batteries scene, wiring, and demo data

**Files:**
- Create: `src/main/frontend/src/scenes/batteries/batteries.ts`
- Create: `src/main/frontend/src/scenes/batteries/batteries.css`
- Modify: `src/main/frontend/src/main.ts` (`MODULES`, and the fallback `WorldState` literal gains `lowBatteries: []`)
- Modify: `src/main/frontend/src/demo.ts`
- Modify: `README.md`
- Test: `src/main/frontend/src/demo.test.ts`

**Interfaces:**
- Consumes: `layout`, `batteryLabel`, `fillFraction`, `MAX_SHOWN`, `PER_ROW` (Task 5).
- Produces: `lowBatteriesModule: SceneModule` (type `low-batteries`, `isEligible: (_, state) => state.lowBatteries.length > 0`).

- [ ] **Step 1: Write the failing demo tests**

Append to `demo.test.ts`:

```ts
describe('demo batteries', () => {
  it('lists the low-batteries scene', () => {
    expect(DEMO_CONFIG.scenes.map((s) => s.type)).toContain('low-batteries');
  });

  it('shows a dead battery, a 0% battery, and a level-less low battery, in display order', () => {
    const batteries = demoState(Date.now()).lowBatteries;
    expect(batteries[0].status).toBe('dead');
    expect(batteries.some((b) => b.status === 'low' && b.level === null)).toBe(true);
    expect(batteries.some((b) => b.level === 0)).toBe(true);
  });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd src/main/frontend && npx vitest run src/demo.test.ts`
Expected: FAIL.

- [ ] **Step 3: Implement the scene**

- `batteries.ts`: `mount` builds a root `div.low-batteries-scene` with an `h1` "Running low", a grid, and (when `more > 0`) a `div.more` "and N more"; `update(state)` re-renders unless `state.lowBatteries` is empty (then keep the last list, spec §4.1); `unmount` does nothing beyond what the host removes. Each battery is an inline SVG (viewBox `0 0 80 140`: nub `rect x=28 y=2 w=24 h=12`, body `rect x=6 y=12 w=68 h=124 rx=12`, stroke width 6) with the name in a `div.name` beneath. Dead: stroke `#8a93a6` dashed `10 7`, two "z" glyphs, label text `#e8e8e8`. Low: stroke `#cfd6e4`, fill rect inside the body with height `fillFraction × 110` at the bottom in `#ffb547`, label in white. Label = `batteryLabel(...)`. Build SVG with `document.createElementNS`, never `innerHTML` with device names.
- `batteries.css`: the background gradient, `system-ui` font stack like `calendar.css`, padding `5vmin 6vmin 4vmin`; heading about `6vmin` bold; grid `grid-template-columns: repeat(min(count, 5), 1fr)` (set the count as a CSS custom property from TS); battery width about `11vmin` in one row and `8vmin` in two rows (a `.two-rows` class); names about `2.6vmin` semibold (`2vmin` in two rows), centered, clamped to two lines (`-webkit-line-clamp: 2`) with ellipsis; `.more` right-aligned, about `2.4vmin`, 60% white.
- `main.ts`: import and register `'low-batteries': lowBatteriesModule`; add `lowBatteries: []` to the fallback state.
- `demo.ts`: add `{ type: 'low-batteries', weight: 2, durationMs: 20_000 }` to `DEMO_CONFIG.scenes` and to `demoState` a fixed list already in display order: Back door lock (dead), Garage tilt sensor (dead), Hall remote (low, null), Kitchen motion sensor (0), Basement leak sensor (6), Front door contact (9).
- `README.md`: add `?scene=low-batteries` to the "Working on scenes" list, mention low batteries in the intro line and link the new spec next to the others, and describe the `screensaver.batteries` settings in one short paragraph where configuration is described (point at `config/application.example.yaml`).

- [ ] **Step 4: Run the frontend build and tests**

Run: `cd src/main/frontend && npm run build && npx vitest run`
Expected: `tsc` reports no errors, the build succeeds, all tests PASS.

- [ ] **Step 5: Look at it**

Run: `cd src/main/frontend && npx vite`, open `http://localhost:5173/?demo&scene=low-batteries`, and compare with the approved mockups (option B, and `battery-layout-v2.html` for two rows). Temporarily trim or extend the demo list in the browser's devtools, or in `demo.ts` without committing, to check 1, 5, 6 and 12 batteries.

- [ ] **Step 6: Commit**

```bash
git add src/main/frontend/src README.md
git commit -m "Add the low-batteries scene"
```

---

### Task 7: Verify against the real Home Assistant

**Files:** none (verification only).

- [ ] **Step 1: Run the full build**

Run: `./mvnw verify`
Expected: BUILD SUCCESS with every Java and frontend test passing.

- [ ] **Step 2: Run the app against Home Assistant**

Add `low-batteries: { weight: 3, duration: 20s }` under `rotation.scenes` in `src/main/resources/application-local.yml` if that file lists scenes (git-ignored; do not commit). Run `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`.
Expected: no `Could not read Home Assistant's device registry` warning after authenticating.

- [ ] **Step 3: Check the stream**

Run: `curl -sN 'http://localhost:8080/api/stream?kiosk=default' | head -c 6000`
Expected: the `state` event has `"lowBatteries":[...]`. The user's house currently has 4 battery entities `unavailable`, 2 at 0% and 1 below 10%; confirm with the user that each appears with a sensible device name and in the right order. For any `unavailable` battery, check in Home Assistant whether it has the attribute `restored: true` (a device or integration Home Assistant no longer loads) and tell the user, who can delete it in Home Assistant or add it to `exclude`.

- [ ] **Step 4: Look at the scene**

Open `http://localhost:8080/?scene=low-batteries` and compare with the list in Home Assistant. Stop the app afterwards.
