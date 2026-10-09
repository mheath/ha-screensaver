# Plan 1: Foundation, Clock, and Snakes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a deployable screensaver app: a Spring Boot backend that connects to Home Assistant and streams live state over SSE, and a browser app that rotates a clock scene and the NetWare snakes scene.

**Architecture:** One Spring Boot jar holds one Home Assistant WebSocket connection, keeps an entity state cache, derives a small "world state," and streams it with each kiosk's merged config to browsers over Server-Sent Events. The browser (TypeScript, Vite, no framework) runs its own weighted scene rotation and reloads itself after any stream reconnect. Maven builds the frontend into the jar.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Spring MVC, validation, actuator, Jackson 3), JDK `java.net.http.WebSocket`, JUnit 6 + AssertJ; TypeScript 6.0.3, Vite 8.3.1, Vitest 5.0.1, `@fontsource/vt323` 5.3.0; Node v22.22.3 via `frontend-maven-plugin` 2.0.2.

**Spec:** `docs/superpowers/specs/2026-10-08-ha-screensaver-design.md`

**This plan is the first of four.** It covers spec §2, §3.1–3.2, §3.6–3.7, §4.1–4.3, §4.5, §5 (as it applies), the clock and snakes scenes of §4.4, §7 and §8. Later plans add: (2) photos, (3) alert rules, low batteries, and the alert cap, (4) weather and calendar. Scene types, world-state fields, and config sections for those are added by the plan that implements them. Home Assistant's `get_config` (time zone) arrives with plan 3, which is the first to need it.

## Global Constraints

- Base package `net.toadhead.ha.screensaver`; Maven coordinates `net.toadhead.ha:ha-screensaver`; build with the Maven wrapper `./mvnw`.
- Spring Boot 4.1.1 uses **Jackson 3**: classes are `tools.jackson.databind.*` (`JsonMapper`, `JsonNode`, `ObjectNode`); annotations stay `com.fasterxml.jackson.annotation.*`. Use `asString("")` and `asInt(default)` (the defaulted forms), never `asText()`.
- Configuration is plain `@ConfigurationProperties("screensaver")` records, `@Validated`, flat (no polymorphism). Config is bound once; changes apply on restart.
- Palette names (exactly): `black, blue, green, cyan, red, magenta, brown, light-gray, dark-gray, light-blue, light-green, light-cyan, light-red, light-magenta, yellow, white`. `black` is rejected for snakes. Pinned count + `guest-count` ≤ 15.
- SSE endpoint `GET /api/stream?kiosk=<id>`; event names `config` then `state`; at most one `state` event per second per connection; an SSE comment heartbeat every 15 s; emitters never time out.
- The browser reloads itself at the next scene change after the stream reconnects (not the first connect), and only if the stream is connected at that moment.
- Screens never show error text. The only failure UI is a small dim dot shown when the stream is down or `haConnected` is false.
- Frontend lives in `src/main/frontend`; no UI framework; pin exact npm versions.
- Every commit message ends with the line `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>` after a blank line.

## Review Focus

1. **Backend unreachable through HAProxy (502/503):** a browser `EventSource` that gets a non-200 response stops retrying forever. Kiosks must keep retrying and recover once the backend is back. Pinned by the `CLOSED` retry test in Task 9.
2. **Idle SSE connections killed by a servlet async timeout or a proxy idle timeout:** every drop triggers a kiosk reload, so silent drops would cause reload loops. Emitters use no timeout, and a 15 s heartbeat keeps proxies from seeing an idle connection. Pinned by the "survives past the async request timeout" test in Task 8 and the heartbeat test in Task 8.
3. **Large Home Assistant messages split across WebSocket frames:** `get_states` on a real install is hundreds of KB. A client that parses each frame separately sees garbage and never connects. Pinned by the 3,000-entity test in Task 6.
4. **Snake entities that aren't numbers** (`unavailable`, `unknown`, `on`, missing): the snake must show `(unavailable)` and freeze, not `NaN` or a crash. Pinned by assembler tests in Task 7 and `formatValue` tests in Task 12.
5. **Deploys change hashed asset names:** if a tablet reuses a cached `index.html` after the reload, it requests assets that no longer exist and shows a black screen forever. Static resources are served `Cache-Control: no-cache`. Pinned by the cache-header test in Task 1.

---

### Task 1: Project skeleton

**Files:**
- Create (generated): `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`, `.gitattributes`, `.gitignore`
- Create: `src/main/java/net/toadhead/ha/screensaver/HaScreensaverApplication.java`
- Create: `src/main/resources/application.yaml`
- Create: `src/test/resources/static/cache-probe.txt`
- Test: `src/test/java/net/toadhead/ha/screensaver/WebEndpointsTests.java`

**Interfaces:**
- Produces: runnable Boot app with `@ConfigurationPropertiesScan` and `@EnableScheduling`; `/actuator/health`, `/actuator/info`; static resources with `Cache-Control: no-cache`.

- [ ] **Step 1: Generate the project with Spring Initializr into the repo root**

```bash
cd /home/mheath/workspace/ha-screensaver
curl -s https://start.spring.io/starter.tgz \
  -d type=maven-project -d bootVersion=4.1.1 -d javaVersion=25 \
  -d groupId=net.toadhead.ha -d artifactId=ha-screensaver -d name=ha-screensaver \
  -d packageName=net.toadhead.ha.screensaver \
  -d dependencies=web,validation,actuator,configuration-processor,websocket \
  | tar xz
rm -f HELP.md src/main/resources/application.properties
rm -f src/test/java/net/toadhead/ha/screensaver/HaScreensaverApplicationTests.java
```

- [ ] **Step 2: Edit `pom.xml`**

Make these changes to the generated file:

1. Move `spring-boot-starter-websocket` to `<scope>test</scope>` (it's only used for the fake Home Assistant server in tests) and delete the `spring-boot-starter-websocket-test` dependency.
2. Replace the `<build><plugins>` section's `spring-boot-maven-plugin` entry with the version below (adds `build-info`), and add the git plugin after it. Keep the generated `maven-compiler-plugin` entry unchanged.

```xml
			<plugin>
				<groupId>org.springframework.boot</groupId>
				<artifactId>spring-boot-maven-plugin</artifactId>
				<executions>
					<execution>
						<goals>
							<goal>build-info</goal>
						</goals>
					</execution>
				</executions>
			</plugin>
			<plugin>
				<groupId>io.github.git-commit-id</groupId>
				<artifactId>git-commit-id-maven-plugin</artifactId>
				<configuration>
					<failOnNoGitDirectory>false</failOnNoGitDirectory>
				</configuration>
			</plugin>
```

- [ ] **Step 3: Write the application class**

`src/main/java/net/toadhead/ha/screensaver/HaScreensaverApplication.java`:

```java
package net.toadhead.ha.screensaver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class HaScreensaverApplication {

	public static void main(String[] args) {
		SpringApplication.run(HaScreensaverApplication.class, args);
	}

}
```

- [ ] **Step 4: Write `src/main/resources/application.yaml`**

```yaml
spring:
  application:
    name: ha-screensaver
  web:
    resources:
      cache:
        cachecontrol:
          # Kiosks reload after every deploy; they must never reuse a stale index.html.
          no-cache: true

management:
  endpoints:
    web:
      exposure:
        include: health,info
  info:
    git:
      mode: simple
```

- [ ] **Step 5: Write the failing test**

`src/test/resources/static/cache-probe.txt`:

```
probe
```

`src/test/java/net/toadhead/ha/screensaver/WebEndpointsTests.java`:

```java
package net.toadhead.ha.screensaver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class WebEndpointsTests {

	@LocalServerPort
	int port;

	private final HttpClient http = HttpClient.newHttpClient();

	@Test
	void staticResourcesMustBeRevalidated() throws Exception {
		HttpResponse<String> response = get("/cache-probe.txt");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Cache-Control"))
			.hasValueSatisfying((value) -> assertThat(value).contains("no-cache"));
	}

	@Test
	void healthIsUp() throws Exception {
		HttpResponse<String> response = get("/actuator/health");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"UP\"");
	}

	private HttpResponse<String> get(String path) throws Exception {
		return this.http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path)).build(),
				HttpResponse.BodyHandlers.ofString());
	}

}
```

- [ ] **Step 6: Run the tests**

Run: `./mvnw -q test`
Expected: PASS (2 tests). If `staticResourcesMustBeRevalidated` fails, check that `application.yaml` from Step 4 is in `src/main/resources`.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Add Spring Boot project skeleton

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Frontend toolchain and palette

**Files:**
- Create: `src/main/frontend/package.json`, `src/main/frontend/package-lock.json` (generated), `src/main/frontend/tsconfig.json`, `src/main/frontend/vite.config.ts`, `src/main/frontend/index.html`
- Create: `src/main/frontend/src/main.ts` (placeholder, replaced in Task 14), `src/main/frontend/src/palette.ts`
- Test: `src/main/frontend/src/palette.test.ts`
- Modify: `pom.xml` (properties, frontend plugin), `.gitignore`

**Interfaces:**
- Produces: `PALETTE: Record<PaletteName, string>` (hex), `type PaletteName`, `SNAKE_COLOR_PREFERENCE: readonly PaletteName[]` (15 colors, `blue` and `dark-gray` last). `./mvnw package` builds the frontend into `target/classes/static` and runs `vitest` in the `test` phase.

- [ ] **Step 1: Write the frontend project files**

`src/main/frontend/package.json`:

```json
{
  "name": "ha-screensaver-frontend",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc --noEmit && vite build",
    "test": "vitest run"
  },
  "dependencies": {
    "@fontsource/vt323": "5.3.0"
  },
  "devDependencies": {
    "typescript": "6.0.3",
    "vite": "8.3.1",
    "vitest": "5.0.1"
  }
}
```

`src/main/frontend/tsconfig.json`:

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "types": ["vite/client"],
    "strict": true,
    "noEmit": true,
    "isolatedModules": true,
    "skipLibCheck": true
  },
  "include": ["src"]
}
```

`src/main/frontend/vite.config.ts`:

```ts
import { defineConfig } from 'vitest/config';

export default defineConfig({
  build: {
    // Lands in the jar's static resources; Maven runs this in generate-resources.
    outDir: '../../../target/classes/static',
    emptyOutDir: true,
  },
  server: {
    proxy: { '/api': 'http://localhost:8080' },
  },
  test: {
    include: ['src/**/*.test.ts'],
  },
});
```

`src/main/frontend/index.html`:

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>HA Screensaver</title>
  </head>
  <body>
    <div id="stage"></div>
    <div id="status" hidden></div>
    <script type="module" src="/src/main.ts"></script>
  </body>
</html>
```

`src/main/frontend/src/main.ts` (placeholder until Task 14):

```ts
document.getElementById('stage')!.textContent = 'ha-screensaver';
```

- [ ] **Step 2: Install and lock dependencies**

```bash
cd src/main/frontend && npm install && cd -
```

Expected: `package-lock.json` and `node_modules/` created.

- [ ] **Step 3: Write the failing palette test**

`src/main/frontend/src/palette.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { PALETTE, SNAKE_COLOR_PREFERENCE } from './palette';

describe('palette', () => {
  it('has the 16 text-mode colors', () => {
    expect(Object.keys(PALETTE)).toHaveLength(16);
    expect(PALETTE.black).toBe('#000000');
    expect(PALETTE['dark-gray']).toBe('#555555');
    expect(PALETTE.yellow).toBe('#FFFF55');
  });

  it('offers 15 distinct snake colors, never black', () => {
    expect(SNAKE_COLOR_PREFERENCE).toHaveLength(15);
    expect(new Set(SNAKE_COLOR_PREFERENCE).size).toBe(15);
    expect(SNAKE_COLOR_PREFERENCE).not.toContain('black');
  });

  it('assigns the hard-to-see colors last', () => {
    expect(SNAKE_COLOR_PREFERENCE.slice(-2)).toEqual(['blue', 'dark-gray']);
  });
});
```

- [ ] **Step 4: Run it to verify it fails**

Run: `cd src/main/frontend && npm test; cd -`
Expected: FAIL — cannot resolve `./palette`.

- [ ] **Step 5: Implement the palette**

`src/main/frontend/src/palette.ts`:

```ts
/** The 16-color text-mode palette, by the names used in configuration. */
export const PALETTE = {
  black: '#000000',
  blue: '#0000AA',
  green: '#00AA00',
  cyan: '#00AAAA',
  red: '#AA0000',
  magenta: '#AA00AA',
  brown: '#AA5500',
  'light-gray': '#AAAAAA',
  'dark-gray': '#555555',
  'light-blue': '#5555FF',
  'light-green': '#55FF55',
  'light-cyan': '#55FFFF',
  'light-red': '#FF5555',
  'light-magenta': '#FF55FF',
  yellow: '#FFFF55',
  white: '#FFFFFF',
} as const;

export type PaletteName = keyof typeof PALETTE;

/**
 * Order in which unassigned snakes get colors: bright colors first, then the
 * darker ones, and last the two that are hard to see on black. Black is the
 * background and is never a snake color.
 */
export const SNAKE_COLOR_PREFERENCE: readonly PaletteName[] = [
  'light-green', 'light-cyan', 'light-red', 'yellow', 'light-magenta', 'white', 'light-blue',
  'green', 'cyan', 'red', 'magenta', 'brown', 'light-gray',
  'blue', 'dark-gray',
];
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd src/main/frontend && npm test; cd -`
Expected: PASS (3 tests).

- [ ] **Step 7: Wire the frontend into Maven**

In `pom.xml` `<properties>`, add:

```xml
		<node.version>v22.22.3</node.version>
		<frontend-maven-plugin.version>2.0.2</frontend-maven-plugin.version>
		<skipTests>false</skipTests>
```

In `<build><plugins>`, add:

```xml
			<plugin>
				<groupId>com.github.eirslett</groupId>
				<artifactId>frontend-maven-plugin</artifactId>
				<version>${frontend-maven-plugin.version}</version>
				<configuration>
					<workingDirectory>src/main/frontend</workingDirectory>
					<installDirectory>${project.basedir}/.node</installDirectory>
				</configuration>
				<executions>
					<execution>
						<id>install-node-and-npm</id>
						<goals>
							<goal>install-node-and-npm</goal>
						</goals>
						<configuration>
							<nodeVersion>${node.version}</nodeVersion>
						</configuration>
					</execution>
					<execution>
						<id>npm-ci</id>
						<goals>
							<goal>npm</goal>
						</goals>
						<configuration>
							<arguments>ci</arguments>
						</configuration>
					</execution>
					<execution>
						<id>npm-build</id>
						<goals>
							<goal>npm</goal>
						</goals>
						<phase>generate-resources</phase>
						<configuration>
							<arguments>run build</arguments>
						</configuration>
					</execution>
					<execution>
						<id>npm-test</id>
						<goals>
							<goal>npm</goal>
						</goals>
						<phase>test</phase>
						<configuration>
							<arguments>test</arguments>
							<skip>${skipTests}</skip>
						</configuration>
					</execution>
				</executions>
			</plugin>
```

Append to `.gitignore`:

```
### Frontend ###
.node/
src/main/frontend/node_modules/
```

- [ ] **Step 8: Verify the full build puts the frontend in the jar**

Run: `./mvnw -q package && unzip -l target/ha-screensaver-0.0.1-SNAPSHOT.jar | grep -E "static/(index.html|assets/)"`
Expected: build succeeds (Java tests and the 3 Vitest tests pass); the listing shows `BOOT-INF/classes/static/index.html` and at least one `BOOT-INF/classes/static/assets/index-*.js`.

Tip for later backend-only iterations: `./mvnw test -Dskip.npm -Dskip.installnodenpm` skips the frontend steps.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Add Vite/TypeScript frontend built by Maven, with text-mode palette

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Configuration properties

**Files:**
- Create: `src/main/java/net/toadhead/ha/screensaver/config/TextModeColor.java`
- Create: `src/main/java/net/toadhead/ha/screensaver/config/SceneType.java`
- Create: `src/main/java/net/toadhead/ha/screensaver/config/ScreensaverProperties.java`
- Create: `src/test/resources/config/application.yaml`
- Create: `src/test/java/net/toadhead/ha/screensaver/config/TestProperties.java`
- Test: `src/test/java/net/toadhead/ha/screensaver/config/ScreensaverPropertiesTests.java`

**Interfaces:**
- Produces:
  - `enum TextModeColor { BLACK, …, WHITE; String id() /* "light-green", @JsonValue */ }`
  - `enum SceneType { CLOCK, SNAKES; double defaultWeight(); Duration defaultDuration(); String id() /* @JsonValue */ }` — defaults: clock 2 / 20 s, snakes 2 / 45 s.
  - `record ScreensaverProperties(HomeAssistant homeAssistant, Clock clock, Snakes snakes, Rotation rotation, Map<String, Kiosk> kiosks)` with nested records:
    - `HomeAssistant(URI url, String token, boolean enabled, Duration initialBackoff, Duration maxBackoff)`
    - `Clock(boolean use24Hour)`
    - `Snakes(int guestCount, int minLength, int maxLength, double minSpeed, double maxSpeed, Map<String, Snake> pinned, Map<String, Snake> guests)`
    - `Snake(String entity, String label, Double min, Double max, TextModeColor color, String unit, int precision)`
    - `Rotation(Map<SceneType, SceneSettings> scenes)`
    - `SceneSettings(Boolean enabled, Double weight, Duration duration)` (all nullable; used for defaults and overrides)
    - `Kiosk(Map<SceneType, SceneSettings> scenes)`
  - Test helper `TestProperties` (test sources): `homeAssistant()`, `snake(entity, label)`, `withSnakes(pinned, guests)`.

- [ ] **Step 1: Write the test config that every Spring test will load**

`src/test/resources/config/application.yaml` (loaded in addition to the main `application.yaml`):

```yaml
screensaver:
  home-assistant:
    url: ws://localhost:1/api/websocket
    token: test-token
    # Tests never talk to a real Home Assistant.
    enabled: false
```

- [ ] **Step 2: Write the failing tests**

`src/test/java/net/toadhead/ha/screensaver/config/ScreensaverPropertiesTests.java`:

```java
package net.toadhead.ha.screensaver.config;

import java.time.Duration;
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
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./mvnw -q test -Dtest=ScreensaverPropertiesTests -Dskip.npm -Dskip.installnodenpm`
Expected: compilation FAILURE — `ScreensaverProperties` does not exist.

- [ ] **Step 4: Implement the enums**

`src/main/java/net/toadhead/ha/screensaver/config/TextModeColor.java`:

```java
package net.toadhead.ha.screensaver.config;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The 16-color text-mode palette. Configured as kebab-case names such as
 * {@code light-green}.
 */
public enum TextModeColor {

	BLACK, BLUE, GREEN, CYAN, RED, MAGENTA, BROWN, LIGHT_GRAY, DARK_GRAY, LIGHT_BLUE, LIGHT_GREEN, LIGHT_CYAN,
	LIGHT_RED, LIGHT_MAGENTA, YELLOW, WHITE;

	/**
	 * The kebab-case name used in configuration and sent to the browser.
	 * @return the id, for example {@code light-green}
	 */
	@JsonValue
	public String id() {
		return name().toLowerCase(Locale.ROOT).replace('_', '-');
	}

}
```

`src/main/java/net/toadhead/ha/screensaver/config/SceneType.java`:

```java
package net.toadhead.ha.screensaver.config;

import java.time.Duration;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The scene types the browser knows how to show, with the weight and duration used
 * when configuration doesn't set them.
 */
public enum SceneType {

	CLOCK(2, Duration.ofSeconds(20)),

	SNAKES(2, Duration.ofSeconds(45));

	private final double defaultWeight;

	private final Duration defaultDuration;

	SceneType(double defaultWeight, Duration defaultDuration) {
		this.defaultWeight = defaultWeight;
		this.defaultDuration = defaultDuration;
	}

	public double defaultWeight() {
		return this.defaultWeight;
	}

	public Duration defaultDuration() {
		return this.defaultDuration;
	}

	@JsonValue
	public String id() {
		return name().toLowerCase(Locale.ROOT).replace('_', '-');
	}

}
```

- [ ] **Step 5: Implement the properties record**

`src/main/java/net/toadhead/ha/screensaver/config/ScreensaverProperties.java`:

```java
package net.toadhead.ha.screensaver.config;

import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * All screensaver configuration, bound once at startup from the {@code screensaver}
 * prefix.
 *
 * @param homeAssistant connection to Home Assistant
 * @param clock clock scene options
 * @param snakes snakes scene options
 * @param rotation default weight, duration and enablement for each scene type
 * @param kiosks per-kiosk overrides keyed by the {@code ?kiosk=} id
 */
@ConfigurationProperties("screensaver")
@Validated
public record ScreensaverProperties(@Valid @NotNull HomeAssistant homeAssistant, @Valid @DefaultValue Clock clock,
		@Valid @DefaultValue Snakes snakes, @Valid @DefaultValue Rotation rotation, Map<String, @Valid Kiosk> kiosks) {

	/** Every palette color except black can be a snake. */
	static final int MAX_SNAKES = 15;

	public ScreensaverProperties {
		kiosks = (kiosks != null) ? kiosks : Map.of();
	}

	public record HomeAssistant(@NotNull URI url, @NotBlank String token, @DefaultValue("true") boolean enabled,
			@DefaultValue("1s") Duration initialBackoff, @DefaultValue("60s") Duration maxBackoff) {

	}

	public record Clock(@DefaultValue("false") boolean use24Hour) {

	}

	public record Snakes(@DefaultValue("0") @Min(0) int guestCount, @DefaultValue("4") @Min(1) int minLength,
			@DefaultValue("30") @Min(1) int maxLength, @DefaultValue("2") @Positive double minSpeed,
			@DefaultValue("20") @Positive double maxSpeed, Map<String, @Valid Snake> pinned,
			Map<String, @Valid Snake> guests) {

		public Snakes {
			// Keep the bound map as-is: it preserves configuration order for the legend.
			pinned = (pinned != null) ? pinned : Map.of();
			guests = (guests != null) ? guests : Map.of();
		}

		@AssertTrue(message = "min-length must not exceed max-length")
		public boolean isLengthRangeValid() {
			return this.minLength <= this.maxLength;
		}

		@AssertTrue(message = "min-speed must not exceed max-speed")
		public boolean isSpeedRangeValid() {
			return this.minSpeed <= this.maxSpeed;
		}

		@AssertTrue(message = "pinned snakes must have distinct colors")
		public boolean isPinnedColorsDistinct() {
			List<TextModeColor> colors = this.pinned.values().stream().map(Snake::color).filter(Objects::nonNull).toList();
			return colors.size() == new HashSet<>(colors).size();
		}

		@AssertTrue(message = "pinned snakes plus guest-count must not exceed 15 (one per palette color)")
		public boolean isWithinPalette() {
			return this.pinned.size() + this.guestCount <= MAX_SNAKES;
		}

		@AssertTrue(message = "guest-count must not exceed the number of guest snakes")
		public boolean isGuestCountWithinPool() {
			return this.guestCount <= this.guests.size();
		}

		@AssertTrue(message = "snake ids must be unique across pinned and guests")
		public boolean isIdsUnique() {
			Set<String> ids = new HashSet<>(this.pinned.keySet());
			ids.retainAll(this.guests.keySet());
			return ids.isEmpty();
		}

	}

	/**
	 * One snake: a numeric entity mapped onto the snake's length and speed.
	 *
	 * @param entity the Home Assistant entity id
	 * @param label legend label
	 * @param min value at which the snake is shortest and slowest
	 * @param max value at which the snake is longest and fastest
	 * @param color palette color, or {@code null} to assign one automatically
	 * @param unit legend unit, or {@code null} to use the entity's unit_of_measurement
	 * @param precision maximum decimals shown in the legend
	 */
	public record Snake(@NotBlank String entity, @NotBlank String label, @NotNull Double min, @NotNull Double max,
			TextModeColor color, String unit, @DefaultValue("1") @Min(0) @Max(4) int precision) {

		@AssertTrue(message = "min must be less than max")
		public boolean isRangeValid() {
			return this.min == null || this.max == null || this.min < this.max;
		}

		@AssertTrue(message = "black is the background color and cannot be used for a snake")
		public boolean isColorVisible() {
			return this.color != TextModeColor.BLACK;
		}

	}

	public record Rotation(Map<SceneType, @Valid SceneSettings> scenes) {

		public Rotation {
			scenes = (scenes != null) ? scenes : Map.of();
		}

	}

	/**
	 * Scene settings. Every field is optional so the same shape works for defaults
	 * and per-kiosk overrides.
	 *
	 * @param enabled whether the scene is in the rotation
	 * @param weight relative chance of being picked
	 * @param duration how long the scene stays on screen
	 */
	public record SceneSettings(Boolean enabled, @Positive Double weight, Duration duration) {

		@AssertTrue(message = "duration must be positive")
		public boolean isDurationPositive() {
			return this.duration == null || (!this.duration.isNegative() && !this.duration.isZero());
		}

	}

	public record Kiosk(Map<SceneType, @Valid SceneSettings> scenes) {

		public Kiosk {
			scenes = (scenes != null) ? scenes : Map.of();
		}

	}

}
```

- [ ] **Step 6: Run the tests**

Run: `./mvnw -q test -Dtest=ScreensaverPropertiesTests -Dskip.npm -Dskip.installnodenpm`
Expected: PASS (13 tests). If `bindsSnakesClockRotationAndKiosks` fails only on `use24Hour`, rename the record component to `twentyFourHour` (property `twenty-four-hour`), and update the spec's §6.1 example and this plan's later references to match.

- [ ] **Step 7: Add the shared test helper**

`src/test/java/net/toadhead/ha/screensaver/config/TestProperties.java`:

```java
package net.toadhead.ha.screensaver.config;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import net.toadhead.ha.screensaver.config.ScreensaverProperties.Clock;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.HomeAssistant;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.Rotation;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.Snake;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.Snakes;

/**
 * Builds {@link ScreensaverProperties} for unit tests without Spring.
 */
public final class TestProperties {

	private TestProperties() {
	}

	public static HomeAssistant homeAssistant() {
		return new HomeAssistant(URI.create("ws://localhost:1/api/websocket"), "test-token", false,
				Duration.ofSeconds(1), Duration.ofSeconds(60));
	}

	public static Snake snake(String entity, String label) {
		return new Snake(entity, label, 0.0, 100.0, null, null, 1);
	}

	public static ScreensaverProperties withSnakes(Map<String, Snake> pinned, Map<String, Snake> guests) {
		return new ScreensaverProperties(homeAssistant(), new Clock(false),
				new Snakes(0, 4, 30, 2, 20, pinned, guests), new Rotation(Map.of()), Map.of());
	}

}
```

- [ ] **Step 8: Run all backend tests**

Run: `./mvnw -q test -Dskip.npm -Dskip.installnodenpm`
Expected: PASS (`WebEndpointsTests` still loads, now with the test config).

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Add validated screensaver configuration properties

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Kiosk config resolution

**Files:**
- Create: `src/main/java/net/toadhead/ha/screensaver/kiosk/KioskConfig.java`
- Create: `src/main/java/net/toadhead/ha/screensaver/kiosk/KioskConfigService.java`
- Test: `src/test/java/net/toadhead/ha/screensaver/kiosk/KioskConfigServiceTests.java`

**Interfaces:**
- Consumes: `ScreensaverProperties`, `SceneType`, `TextModeColor` (Task 3).
- Produces:
  - `record KioskConfig(String kiosk, List<SceneConfig> scenes, ClockOptions clock, SnakeOptions snakes)` with nested `SceneConfig(SceneType type, double weight, long durationMs)`, `ClockOptions(boolean use24Hour)`, `SnakeOptions(int guestCount, int minLength, int maxLength, double minSpeed, double maxSpeed, List<SnakeDef> pinned, List<SnakeDef> guests)`, `SnakeDef(String id, String label, TextModeColor color, String unit, int precision, double min, double max)`. Serialized as JSON to the browser.
  - `KioskConfigService.resolve(String kioskId): KioskConfig` — null/blank id → `"default"`; disabled scenes omitted; scenes in `SceneType` order.

- [ ] **Step 1: Write the failing tests**

`src/test/java/net/toadhead/ha/screensaver/kiosk/KioskConfigServiceTests.java`:

```java
package net.toadhead.ha.screensaver.kiosk;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import net.toadhead.ha.screensaver.config.SceneType;
import net.toadhead.ha.screensaver.config.ScreensaverProperties;
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

import static org.assertj.core.api.Assertions.assertThat;

class KioskConfigServiceTests {

	@Test
	void usesSceneTypeDefaultsWhenNothingIsConfigured() {
		KioskConfig config = service(Map.of(), Map.of()).resolve("kitchen");
		assertThat(config.kiosk()).isEqualTo("kitchen");
		assertThat(config.scenes()).containsExactly(new SceneConfig(SceneType.CLOCK, 2, 20_000),
				new SceneConfig(SceneType.SNAKES, 2, 45_000));
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
		assertThat(config.scenes()).extracting(SceneConfig::type).containsExactly(SceneType.CLOCK);
	}

	@Test
	void sceneDisabledInRotationStaysDisabledWhenKioskOnlyChangesWeight() {
		var rotation = Map.of(SceneType.SNAKES, new SceneSettings(false, null, null));
		var kiosks = Map.of("hall", new Kiosk(Map.of(SceneType.SNAKES, new SceneSettings(null, 9.0, null))));
		KioskConfig config = service(rotation, kiosks).resolve("hall");
		assertThat(config.scenes()).extracting(SceneConfig::type).containsExactly(SceneType.CLOCK);
	}

	@Test
	void unknownKioskGetsTheDefaults() {
		var kiosks = Map.of("bedroom", new Kiosk(Map.of(SceneType.SNAKES, new SceneSettings(false, null, null))));
		KioskConfig config = service(Map.of(), kiosks).resolve("somewhere-else");
		assertThat(config.scenes()).hasSize(2);
	}

	@Test
	void includesClockAndSnakeOptionsInConfigOrder() {
		Map<String, Snake> pinned = new LinkedHashMap<>();
		pinned.put("internet-download",
				new Snake("sensor.wan", "Internet download bandwidth", 0.0, 1000.0, TextModeColor.LIGHT_GREEN, null, 1));
		pinned.put("house-power", new Snake("sensor.power", "House power", 0.0, 12.0, null, "kW", 0));
		Map<String, Snake> guests = Map.of("wind", TestProperties.snake("sensor.wind", "Wind speed"));
		var properties = new ScreensaverProperties(TestProperties.homeAssistant(), new Clock(true),
				new Snakes(1, 3, 25, 1, 15, pinned, guests), new Rotation(Map.of()), Map.of());

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

	private static KioskConfigService service(Map<SceneType, SceneSettings> rotation, Map<String, Kiosk> kiosks) {
		var properties = new ScreensaverProperties(TestProperties.homeAssistant(), new Clock(false),
				new Snakes(0, 4, 30, 2, 20, Map.of(), Map.of()), new Rotation(rotation), kiosks);
		return new KioskConfigService(properties);
	}

}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -q test -Dtest=KioskConfigServiceTests -Dskip.npm -Dskip.installnodenpm`
Expected: compilation FAILURE — `KioskConfig` does not exist.

- [ ] **Step 3: Implement**

`src/main/java/net/toadhead/ha/screensaver/kiosk/KioskConfig.java`:

```java
package net.toadhead.ha.screensaver.kiosk;

import java.util.List;

import net.toadhead.ha.screensaver.config.SceneType;
import net.toadhead.ha.screensaver.config.TextModeColor;

/**
 * Everything one kiosk's browser needs to run its rotation. Sent as the stream's
 * {@code config} event; never contains secrets.
 *
 * @param kiosk the kiosk id
 * @param scenes enabled scenes with their resolved weight and duration
 * @param clock clock scene options
 * @param snakes snakes scene options
 */
public record KioskConfig(String kiosk, List<SceneConfig> scenes, ClockOptions clock, SnakeOptions snakes) {

	public record SceneConfig(SceneType type, double weight, long durationMs) {

	}

	public record ClockOptions(boolean use24Hour) {

	}

	public record SnakeOptions(int guestCount, int minLength, int maxLength, double minSpeed, double maxSpeed,
			List<SnakeDef> pinned, List<SnakeDef> guests) {

	}

	public record SnakeDef(String id, String label, TextModeColor color, String unit, int precision, double min,
			double max) {

	}

}
```

`src/main/java/net/toadhead/ha/screensaver/kiosk/KioskConfigService.java`:

```java
package net.toadhead.ha.screensaver.kiosk;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import net.toadhead.ha.screensaver.config.SceneType;
import net.toadhead.ha.screensaver.config.ScreensaverProperties;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.Kiosk;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.SceneSettings;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.Snake;
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

	public KioskConfigService(ScreensaverProperties properties) {
		this.properties = properties;
	}

	public KioskConfig resolve(String kioskId) {
		String id = (kioskId == null || kioskId.isBlank()) ? DEFAULT_KIOSK : kioskId;
		Kiosk kiosk = this.properties.kiosks().get(id);
		Map<SceneType, SceneSettings> overrides = (kiosk != null) ? kiosk.scenes() : Map.of();
		return new KioskConfig(id, scenes(overrides), new ClockOptions(this.properties.clock().use24Hour()),
				snakeOptions());
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
```

- [ ] **Step 4: Run the tests**

Run: `./mvnw -q test -Dtest=KioskConfigServiceTests -Dskip.npm -Dskip.installnodenpm`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Resolve per-kiosk config from rotation defaults and overrides

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Home Assistant protocol session and state store

**Files:**
- Create: `src/main/java/net/toadhead/ha/screensaver/ha/EntityState.java`
- Create: `src/main/java/net/toadhead/ha/screensaver/ha/HomeAssistantState.java`
- Create: `src/main/java/net/toadhead/ha/screensaver/ha/HomeAssistantUpdated.java`
- Create: `src/main/java/net/toadhead/ha/screensaver/ha/HaRequestException.java`
- Create: `src/main/java/net/toadhead/ha/screensaver/ha/HaSession.java`
- Test: `src/test/java/net/toadhead/ha/screensaver/ha/HaSessionTests.java`
- Test: `src/test/java/net/toadhead/ha/screensaver/ha/HomeAssistantStateTests.java`

**Interfaces:**
- Produces:
  - `record EntityState(String entityId, String state, JsonNode attributes, Instant lastChanged)`; `static EntityState fromJson(JsonNode)`; `Optional<String> attribute(String name)`.
  - `@Component HomeAssistantState`: `boolean connected()`, `void markConnected(Collection<EntityState>)` (replaces all states), `void markDisconnected()`, `void put(EntityState)`, `void remove(String entityId)`, `Optional<EntityState> get(String entityId)`.
  - `record HomeAssistantUpdated()` — Spring event published after any change to `HomeAssistantState`.
  - `HaSession(String token, JsonMapper mapper, Consumer<String> outbound, HaSession.Listener listener)`; `void onMessage(String text)`; `CompletableFuture<JsonNode> request(ObjectNode message)`; `void close()`. `HaSession.Listener`: `onAuthenticated()`, `onAuthFailed(String message)`, `onStates(List<EntityState>)`, `onStateChanged(String entityId, EntityState newStateOrNull)`.

Home Assistant WebSocket protocol summary (for reference):
`auth_required` → client sends `{"type":"auth","access_token":…}` → `auth_ok` or `auth_invalid`. Requests carry an increasing `id`; replies are `{"id":n,"type":"result","success":true,"result":…}` or `success:false` with `error.message`. `subscribe_events` with `event_type: state_changed` yields `{"id":n,"type":"event","event":{"data":{"entity_id":…,"new_state":{…}|null}}}`.

- [ ] **Step 1: Write the failing session tests**

`src/test/java/net/toadhead/ha/screensaver/ha/HaSessionTests.java`:

```java
package net.toadhead.ha.screensaver.ha;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class HaSessionTests {

	private final JsonMapper mapper = JsonMapper.shared();

	private final List<String> sent = new ArrayList<>();

	private final RecordingListener listener = new RecordingListener();

	private final HaSession session = new HaSession("secret", this.mapper, this.sent::add, this.listener);

	@Test
	void answersAuthRequiredWithToken() {
		this.session.onMessage("{\"type\":\"auth_required\",\"ha_version\":\"2026.10.0\"}");
		assertThat(sent(0).path("type").asString("")).isEqualTo("auth");
		assertThat(sent(0).path("access_token").asString("")).isEqualTo("secret");
	}

	@Test
	void subscribesThenLoadsStatesAfterAuthOk() {
		this.session.onMessage("{\"type\":\"auth_ok\"}");
		assertThat(this.listener.authenticated).isTrue();
		assertThat(sent(0).path("type").asString("")).isEqualTo("subscribe_events");
		assertThat(sent(0).path("event_type").asString("")).isEqualTo("state_changed");
		assertThat(sent(0).path("id").asInt(-1)).isEqualTo(1);
		assertThat(sent(1).path("type").asString("")).isEqualTo("get_states");
		assertThat(sent(1).path("id").asInt(-1)).isEqualTo(2);
	}

	@Test
	void parsesStatesFromGetStatesResult() {
		this.session.onMessage("{\"type\":\"auth_ok\"}");
		this.session.onMessage("""
				{"id":2,"type":"result","success":true,"result":[
				  {"entity_id":"sensor.power","state":"42.5",
				   "attributes":{"unit_of_measurement":"W","friendly_name":"Power"},
				   "last_changed":"2026-10-08T17:05:00.123456+00:00","last_updated":"2026-10-08T17:05:00.123456+00:00"}
				]}""");
		assertThat(this.listener.states).hasSize(1);
		EntityState power = this.listener.states.get(0);
		assertThat(power.entityId()).isEqualTo("sensor.power");
		assertThat(power.state()).isEqualTo("42.5");
		assertThat(power.attribute("unit_of_measurement")).contains("W");
		assertThat(power.attribute("missing")).isEmpty();
		assertThat(power.lastChanged()).isEqualTo(Instant.parse("2026-10-08T17:05:00.123456Z"));
	}

	@Test
	void reportsStateChangesFromTheSubscription() {
		this.session.onMessage("{\"type\":\"auth_ok\"}");
		this.session.onMessage("""
				{"id":1,"type":"event","event":{"event_type":"state_changed","data":{
				  "entity_id":"sensor.power",
				  "new_state":{"entity_id":"sensor.power","state":"7","attributes":{},
				               "last_changed":"2026-10-08T17:06:00+00:00"}}}}""");
		assertThat(this.listener.changedIds).containsExactly("sensor.power");
		assertThat(this.listener.changedStates.get(0).state()).isEqualTo("7");
	}

	@Test
	void reportsRemovalWhenNewStateIsNull() {
		this.session.onMessage("{\"type\":\"auth_ok\"}");
		this.session.onMessage("""
				{"id":1,"type":"event","event":{"event_type":"state_changed","data":{
				  "entity_id":"sensor.gone","new_state":null}}}""");
		assertThat(this.listener.changedIds).containsExactly("sensor.gone");
		assertThat(this.listener.changedStates).containsExactly((EntityState) null);
	}

	@Test
	void ignoresEventsForOtherSubscriptions() {
		this.session.onMessage("{\"type\":\"auth_ok\"}");
		this.session.onMessage("""
				{"id":99,"type":"event","event":{"data":{"entity_id":"sensor.x","new_state":null}}}""");
		assertThat(this.listener.changedIds).isEmpty();
	}

	@Test
	void reportsInvalidAuth() {
		this.session.onMessage("{\"type\":\"auth_invalid\",\"message\":\"Invalid access token\"}");
		assertThat(this.listener.authFailure).isEqualTo("Invalid access token");
	}

	@Test
	void failedResultCompletesRequestExceptionally() {
		CompletableFuture<JsonNode> reply = this.session.request(this.mapper.createObjectNode().put("type", "ping_me"));
		this.session.onMessage("""
				{"id":1,"type":"result","success":false,"error":{"code":"unknown_command","message":"Unknown command."}}""");
		assertThat(reply).isCompletedExceptionally();
		assertThat(reply.exceptionNow()).isInstanceOf(HaRequestException.class).hasMessage("Unknown command.");
	}

	@Test
	void closeFailsPendingRequests() {
		CompletableFuture<JsonNode> reply = this.session.request(this.mapper.createObjectNode().put("type", "get_states"));
		this.session.close();
		assertThat(reply).isCompletedExceptionally();
	}

	private JsonNode sent(int index) {
		return this.mapper.readTree(this.sent.get(index));
	}

	static final class RecordingListener implements HaSession.Listener {

		boolean authenticated;

		String authFailure;

		List<EntityState> states = List.of();

		final List<String> changedIds = new ArrayList<>();

		final List<EntityState> changedStates = new ArrayList<>();

		@Override
		public void onAuthenticated() {
			this.authenticated = true;
		}

		@Override
		public void onAuthFailed(String message) {
			this.authFailure = message;
		}

		@Override
		public void onStates(List<EntityState> states) {
			this.states = states;
		}

		@Override
		public void onStateChanged(String entityId, EntityState newState) {
			this.changedIds.add(entityId);
			this.changedStates.add(newState);
		}

	}

}
```

`src/test/java/net/toadhead/ha/screensaver/ha/HomeAssistantStateTests.java`:

```java
package net.toadhead.ha.screensaver.ha;

import java.time.Instant;
import java.util.List;

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

	static EntityState entity(String id, String value) {
		return new EntityState(id, value, JsonMapper.shared().createObjectNode(), Instant.EPOCH);
	}

}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -q test -Dtest='HaSessionTests,HomeAssistantStateTests' -Dskip.npm -Dskip.installnodenpm`
Expected: compilation FAILURE — `HaSession`, `EntityState` do not exist.

- [ ] **Step 3: Implement the state types**

`src/main/java/net/toadhead/ha/screensaver/ha/EntityState.java`:

```java
package net.toadhead.ha.screensaver.ha;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

/**
 * One Home Assistant entity's state.
 *
 * @param entityId the entity id, for example {@code sensor.house_power}
 * @param state the raw state string
 * @param attributes the entity's attributes object
 * @param lastChanged when the state last changed, or {@code null} if unknown
 */
public record EntityState(String entityId, String state, JsonNode attributes, Instant lastChanged) {

	static EntityState fromJson(JsonNode node) {
		String lastChanged = node.path("last_changed").asString("");
		return new EntityState(node.path("entity_id").asString(""), node.path("state").asString(""),
				node.path("attributes"), lastChanged.isEmpty() ? null : OffsetDateTime.parse(lastChanged).toInstant());
	}

	public Optional<String> attribute(String name) {
		JsonNode value = (this.attributes != null) ? this.attributes.get(name) : null;
		return (value == null || value.isNull()) ? Optional.empty() : Optional.of(value.asString(""));
	}

}
```

`src/main/java/net/toadhead/ha/screensaver/ha/HomeAssistantState.java`:

```java
package net.toadhead.ha.screensaver.ha;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Last known state of every Home Assistant entity, plus whether we're connected.
 * States are kept while disconnected so screens keep showing last-known values.
 */
@Component
public class HomeAssistantState {

	private final Map<String, EntityState> states = new ConcurrentHashMap<>();

	private volatile boolean connected;

	public boolean connected() {
		return this.connected;
	}

	public void markConnected(Collection<EntityState> all) {
		Map<String, EntityState> fresh = new ConcurrentHashMap<>();
		all.forEach((state) -> fresh.put(state.entityId(), state));
		this.states.keySet().retainAll(fresh.keySet());
		this.states.putAll(fresh);
		this.connected = true;
	}

	public void markDisconnected() {
		this.connected = false;
	}

	public void put(EntityState state) {
		this.states.put(state.entityId(), state);
	}

	public void remove(String entityId) {
		this.states.remove(entityId);
	}

	public Optional<EntityState> get(String entityId) {
		return Optional.ofNullable(this.states.get(entityId));
	}

}
```

`src/main/java/net/toadhead/ha/screensaver/ha/HomeAssistantUpdated.java`:

```java
package net.toadhead.ha.screensaver.ha;

/**
 * Published after any change to {@link HomeAssistantState}.
 */
public record HomeAssistantUpdated() {

}
```

`src/main/java/net/toadhead/ha/screensaver/ha/HaRequestException.java`:

```java
package net.toadhead.ha.screensaver.ha;

/**
 * A Home Assistant WebSocket request that failed or could not complete.
 */
public class HaRequestException extends RuntimeException {

	public HaRequestException(String message) {
		super(message);
	}

}
```

- [ ] **Step 4: Implement the session**

`src/main/java/net/toadhead/ha/screensaver/ha/HaSession.java`:

```java
package net.toadhead.ha.screensaver.ha;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Home Assistant WebSocket protocol for one connection, independent of the
 * transport: feed it incoming messages, and it writes replies to {@code outbound}
 * and reports what happened to the {@link Listener}.
 */
final class HaSession {

	private final String token;

	private final JsonMapper mapper;

	private final Consumer<String> outbound;

	private final Listener listener;

	private final AtomicInteger ids = new AtomicInteger();

	private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

	private volatile int subscriptionId = -1;

	HaSession(String token, JsonMapper mapper, Consumer<String> outbound, Listener listener) {
		this.token = token;
		this.mapper = mapper;
		this.outbound = outbound;
		this.listener = listener;
	}

	void onMessage(String text) {
		JsonNode message = this.mapper.readTree(text);
		switch (message.path("type").asString("")) {
			case "auth_required" -> send(this.mapper.createObjectNode().put("type", "auth").put("access_token", this.token));
			case "auth_ok" -> {
				this.listener.onAuthenticated();
				subscribeAndLoad();
			}
			case "auth_invalid" -> this.listener.onAuthFailed(message.path("message").asString(""));
			case "result" -> complete(message);
			case "event" -> onEvent(message);
			default -> {
			}
		}
	}

	CompletableFuture<JsonNode> request(ObjectNode message) {
		int id = this.ids.incrementAndGet();
		message.put("id", id);
		CompletableFuture<JsonNode> reply = new CompletableFuture<>();
		this.pending.put(id, reply);
		send(message);
		return reply;
	}

	void close() {
		this.pending.values().forEach((reply) -> reply.completeExceptionally(new HaRequestException("connection closed")));
		this.pending.clear();
	}

	private void subscribeAndLoad() {
		ObjectNode subscribe = this.mapper.createObjectNode()
			.put("type", "subscribe_events")
			.put("event_type", "state_changed");
		this.subscriptionId = this.ids.get() + 1;
		request(subscribe);
		request(this.mapper.createObjectNode().put("type", "get_states")).thenAccept((result) -> {
			List<EntityState> states = new ArrayList<>();
			result.forEach((node) -> states.add(EntityState.fromJson(node)));
			this.listener.onStates(states);
		});
	}

	private void complete(JsonNode message) {
		CompletableFuture<JsonNode> reply = this.pending.remove(message.path("id").asInt(-1));
		if (reply == null) {
			return;
		}
		if (message.path("success").asBoolean(false)) {
			reply.complete(message.path("result"));
		}
		else {
			reply.completeExceptionally(
					new HaRequestException(message.path("error").path("message").asString("request failed")));
		}
	}

	private void onEvent(JsonNode message) {
		if (message.path("id").asInt(-1) != this.subscriptionId) {
			return;
		}
		JsonNode data = message.path("event").path("data");
		JsonNode newState = data.path("new_state");
		this.listener.onStateChanged(data.path("entity_id").asString(""),
				newState.isObject() ? EntityState.fromJson(newState) : null);
	}

	private void send(ObjectNode message) {
		this.outbound.accept(this.mapper.writeValueAsString(message));
	}

	interface Listener {

		void onAuthenticated();

		void onAuthFailed(String message);

		void onStates(List<EntityState> states);

		/**
		 * Called for every state change.
		 * @param entityId the entity that changed
		 * @param newState the new state, or {@code null} if the entity was removed
		 */
		void onStateChanged(String entityId, EntityState newState);

	}

}
```

- [ ] **Step 5: Run the tests**

Run: `./mvnw -q test -Dtest='HaSessionTests,HomeAssistantStateTests' -Dskip.npm -Dskip.installnodenpm`
Expected: PASS (13 tests).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add Home Assistant protocol session and entity state store

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Home Assistant connector (WebSocket transport and reconnect)

**Files:**
- Create: `src/main/java/net/toadhead/ha/screensaver/ha/HomeAssistantConnector.java`
- Create: `src/test/java/fakeha/FakeHomeAssistant.java`
- Test: `src/test/java/net/toadhead/ha/screensaver/ha/HomeAssistantConnectorTests.java`

**Interfaces:**
- Consumes: `ScreensaverProperties.HomeAssistant`, `HomeAssistantState`, `HomeAssistantUpdated`, `HaSession` (Tasks 3, 5); Spring's auto-configured `JsonMapper` bean.
- Produces: `@Component HomeAssistantConnector implements SmartLifecycle`, active unless `screensaver.home-assistant.enabled=false`. Keeps `HomeAssistantState` current and publishes `HomeAssistantUpdated` after every change. Package-private constructor `HomeAssistantConnector(ScreensaverProperties.HomeAssistant, HomeAssistantState, ApplicationEventPublisher, JsonMapper)` for tests.

Test-only `fakeha.FakeHomeAssistant` (outside `net.toadhead.ha.screensaver` so the app never scans it): `static FakeHomeAssistant start(List<Map<String,Object>> states)`, `URI uri()`, `int authAttempts()`, `void pushStateChange(String entityId, String state)`, `void dropConnections()`, `close()`; `TOKEN = "good-token"`.

- [ ] **Step 1: Write the fake Home Assistant server**

`src/test/java/fakeha/FakeHomeAssistant.java`:

```java
package fakeha;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * A scripted stand-in for Home Assistant's WebSocket API, served by its own
 * embedded Tomcat on a random port.
 */
public final class FakeHomeAssistant implements AutoCloseable {

	public static final String TOKEN = "good-token";

	private final ConfigurableApplicationContext context;

	private final Handler handler;

	private FakeHomeAssistant(ConfigurableApplicationContext context) {
		this.context = context;
		this.handler = context.getBean(Handler.class);
	}

	public static FakeHomeAssistant start(List<Map<String, Object>> states) {
		ConfigurableApplicationContext context = new SpringApplicationBuilder(App.class)
			.properties("server.port=0", "spring.main.banner-mode=off",
					"spring.config.location=optional:classpath:/fakeha-none/")
			.run();
		FakeHomeAssistant fake = new FakeHomeAssistant(context);
		fake.handler.states = states;
		return fake;
	}

	public static Map<String, Object> entity(String entityId, String state, Map<String, Object> attributes) {
		Map<String, Object> entity = new LinkedHashMap<>();
		entity.put("entity_id", entityId);
		entity.put("state", state);
		entity.put("attributes", attributes);
		entity.put("last_changed", "2026-10-08T17:05:00.000000+00:00");
		entity.put("last_updated", "2026-10-08T17:05:00.000000+00:00");
		return entity;
	}

	public URI uri() {
		return URI.create("ws://localhost:" + this.context.getEnvironment().getProperty("local.server.port")
				+ "/api/websocket");
	}

	public int authAttempts() {
		return this.handler.authAttempts.get();
	}

	public void pushStateChange(String entityId, String state) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("id", this.handler.subscriptionId);
		event.put("type", "event");
		event.put("event", Map.of("event_type", "state_changed", "data",
				Map.of("entity_id", entityId, "new_state", entity(entityId, state, Map.of()))));
		this.handler.sessions.forEach((session) -> this.handler.send(session, event));
	}

	public void dropConnections() throws IOException {
		for (WebSocketSession session : this.handler.sessions) {
			session.close(CloseStatus.GOING_AWAY);
		}
	}

	@Override
	public void close() {
		this.context.close();
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EnableWebSocket
	static class App implements WebSocketConfigurer {

		private final Handler handler = new Handler();

		@Bean
		Handler handler() {
			return this.handler;
		}

		@Override
		public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
			registry.addHandler(this.handler, "/api/websocket");
		}

	}

	static final class Handler extends TextWebSocketHandler {

		private final JsonMapper mapper = JsonMapper.shared();

		final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

		final AtomicInteger authAttempts = new AtomicInteger();

		volatile List<Map<String, Object>> states = List.of();

		volatile int subscriptionId = -1;

		@Override
		public void afterConnectionEstablished(WebSocketSession session) {
			this.sessions.add(session);
			send(session, Map.of("type", "auth_required", "ha_version", "2026.10.0"));
		}

		@Override
		protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
			JsonNode request = this.mapper.readTree(message.getPayload());
			int id = request.path("id").asInt(0);
			switch (request.path("type").asString("")) {
				case "auth" -> {
					this.authAttempts.incrementAndGet();
					if (TOKEN.equals(request.path("access_token").asString(""))) {
						send(session, Map.of("type", "auth_ok", "ha_version", "2026.10.0"));
					}
					else {
						send(session, Map.of("type", "auth_invalid", "message", "Invalid access token"));
						session.close(CloseStatus.POLICY_VIOLATION);
					}
				}
				case "subscribe_events" -> {
					this.subscriptionId = id;
					send(session, result(id, null));
				}
				case "get_states" -> send(session, result(id, this.states));
				default -> send(session, Map.of("id", id, "type", "result", "success", false, "error",
						Map.of("code", "unknown_command", "message", "Unknown command.")));
			}
		}

		@Override
		public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
			this.sessions.remove(session);
		}

		synchronized void send(WebSocketSession session, Object payload) {
			try {
				session.sendMessage(new TextMessage(this.mapper.writeValueAsString(payload)));
			}
			catch (IOException ex) {
				throw new IllegalStateException(ex);
			}
		}

		private static Map<String, Object> result(int id, Object result) {
			Map<String, Object> reply = new LinkedHashMap<>();
			reply.put("id", id);
			reply.put("type", "result");
			reply.put("success", true);
			reply.put("result", result);
			return reply;
		}

	}

}
```

- [ ] **Step 2: Write the failing connector tests**

`src/test/java/net/toadhead/ha/screensaver/ha/HomeAssistantConnectorTests.java`:

```java
package net.toadhead.ha.screensaver.ha;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import fakeha.FakeHomeAssistant;
import net.toadhead.ha.screensaver.config.ScreensaverProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class HomeAssistantConnectorTests {

	private final HomeAssistantState state = new HomeAssistantState();

	private final AtomicInteger updates = new AtomicInteger();

	private FakeHomeAssistant fake;

	private HomeAssistantConnector connector;

	@AfterEach
	void stop() {
		if (this.connector != null) {
			this.connector.stop();
		}
		if (this.fake != null) {
			this.fake.close();
		}
	}

	@Test
	void connectsAuthenticatesAndLoadsStates() {
		start(List.of(FakeHomeAssistant.entity("sensor.power", "42.5", Map.of("unit_of_measurement", "W"))),
				FakeHomeAssistant.TOKEN);
		await(this.state::connected, "connected");
		assertThat(this.state.get("sensor.power")).map(EntityState::state).contains("42.5");
		assertThat(this.updates.get()).isPositive();
	}

	@Test
	void appliesStateChanges() {
		start(List.of(FakeHomeAssistant.entity("sensor.power", "1", Map.of())), FakeHomeAssistant.TOKEN);
		await(this.state::connected, "connected");
		this.fake.pushStateChange("sensor.power", "2");
		await(() -> this.state.get("sensor.power").map(EntityState::state).orElse("").equals("2"), "state change");
	}

	@Test
	void reassemblesLargeMessagesSplitAcrossFrames() {
		List<Map<String, Object>> many = new ArrayList<>();
		for (int i = 0; i < 3000; i++) {
			many.add(FakeHomeAssistant.entity("sensor.s" + i, String.valueOf(i),
					Map.of("friendly_name", "Sensor number " + i + " with a reasonably long name")));
		}
		start(many, FakeHomeAssistant.TOKEN);
		await(this.state::connected, "connected");
		assertThat(this.state.get("sensor.s0")).isPresent();
		assertThat(this.state.get("sensor.s2999")).map(EntityState::state).contains("2999");
	}

	@Test
	void reconnectsAfterConnectionDrops() throws Exception {
		start(List.of(FakeHomeAssistant.entity("sensor.power", "1", Map.of())), FakeHomeAssistant.TOKEN);
		await(this.state::connected, "connected");
		this.fake.dropConnections();
		await(() -> this.fake.authAttempts() >= 2 && this.state.connected(), "reconnected");
	}

	@Test
	void keepsRetryingWithBadToken() {
		start(List.of(), "wrong-token");
		await(() -> this.fake.authAttempts() >= 2, "second auth attempt");
		assertThat(this.state.connected()).isFalse();
	}

	private void start(List<Map<String, Object>> states, String token) {
		this.fake = FakeHomeAssistant.start(states);
		var config = new ScreensaverProperties.HomeAssistant(this.fake.uri(), token, true, Duration.ofMillis(50),
				Duration.ofMillis(200));
		this.connector = new HomeAssistantConnector(config, this.state, (event) -> this.updates.incrementAndGet(),
				JsonMapper.shared());
		this.connector.start();
	}

	private static void await(BooleanSupplier condition, String what) {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			try {
				Thread.sleep(20);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				fail("interrupted waiting for " + what);
			}
		}
		fail("timed out waiting for " + what);
	}

}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./mvnw -q test -Dtest=HomeAssistantConnectorTests -Dskip.npm -Dskip.installnodenpm`
Expected: compilation FAILURE — `HomeAssistantConnector` does not exist.

- [ ] **Step 4: Implement the connector**

`src/main/java/net/toadhead/ha/screensaver/ha/HomeAssistantConnector.java`:

```java
package net.toadhead.ha.screensaver.ha;

import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import net.toadhead.ha.screensaver.config.ScreensaverProperties;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Keeps one WebSocket connection to Home Assistant open, reconnecting with
 * exponential backoff, and mirrors entity states into {@link HomeAssistantState}.
 */
@Component
@ConditionalOnBooleanProperty(name = "screensaver.home-assistant.enabled", matchIfMissing = true)
public class HomeAssistantConnector implements SmartLifecycle {

	private static final Logger logger = LoggerFactory.getLogger(HomeAssistantConnector.class);

	private final ScreensaverProperties.HomeAssistant config;

	private final HomeAssistantState state;

	private final ApplicationEventPublisher events;

	private final JsonMapper mapper;

	private final HttpClient http = HttpClient.newHttpClient();

	private final ScheduledExecutorService reconnects = Executors
		.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("ha-reconnect").factory());

	private volatile boolean running;

	private volatile Connection connection;

	private Duration nextBackoff;

	@Autowired
	public HomeAssistantConnector(ScreensaverProperties properties, HomeAssistantState state,
			ApplicationEventPublisher events, JsonMapper mapper) {
		this(properties.homeAssistant(), state, events, mapper);
	}

	HomeAssistantConnector(ScreensaverProperties.HomeAssistant config, HomeAssistantState state,
			ApplicationEventPublisher events, JsonMapper mapper) {
		this.config = config;
		this.state = state;
		this.events = events;
		this.mapper = mapper;
		this.nextBackoff = config.initialBackoff();
	}

	@Override
	public void start() {
		this.running = true;
		connect();
	}

	@Override
	public void stop() {
		this.running = false;
		this.reconnects.shutdownNow();
		Connection current = this.connection;
		if (current != null) {
			current.abort();
		}
	}

	@Override
	public boolean isRunning() {
		return this.running;
	}

	private void connect() {
		if (!this.running) {
			return;
		}
		Connection attempt = new Connection();
		this.connection = attempt;
		this.http.newWebSocketBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.buildAsync(this.config.url(), attempt)
			.whenComplete((socket, error) -> {
				if (error != null) {
					attempt.closed("could not connect: " + error.getMessage());
				}
			});
	}

	private synchronized void scheduleReconnect() {
		if (!this.running) {
			return;
		}
		Duration delay = this.nextBackoff;
		Duration doubled = this.nextBackoff.multipliedBy(2);
		this.nextBackoff = (doubled.compareTo(this.config.maxBackoff()) > 0) ? this.config.maxBackoff() : doubled;
		logger.info("Reconnecting to Home Assistant in {}", delay);
		try {
			this.reconnects.schedule(this::connect, delay.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (RejectedExecutionException ex) {
			// Shutting down.
		}
	}

	private synchronized void resetBackoff() {
		this.nextBackoff = this.config.initialBackoff();
	}

	private void publish() {
		this.events.publishEvent(new HomeAssistantUpdated());
	}

	/**
	 * One connection attempt: the WebSocket listener plus its protocol session.
	 */
	private final class Connection implements WebSocket.Listener, HaSession.Listener {

		private final HaSession session = new HaSession(HomeAssistantConnector.this.config.token(),
				HomeAssistantConnector.this.mapper, this::send, this);

		/** Home Assistant splits large messages into several frames. */
		private final StringBuilder buffer = new StringBuilder();

		private final AtomicBoolean closed = new AtomicBoolean();

		private volatile WebSocket socket;

		/** The JDK WebSocket rejects a send while another is in flight, so sends are chained. */
		private CompletableFuture<WebSocket> sending = CompletableFuture.completedFuture(null);

		@Override
		public void onOpen(WebSocket webSocket) {
			this.socket = webSocket;
			webSocket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			this.buffer.append(data);
			if (last) {
				String message = this.buffer.toString();
				this.buffer.setLength(0);
				try {
					this.session.onMessage(message);
				}
				catch (RuntimeException ex) {
					logger.warn("Could not handle a Home Assistant message", ex);
				}
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			closed("closed by Home Assistant (" + statusCode + " " + reason + ")");
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			closed(error.toString());
		}

		private synchronized void send(String text) {
			WebSocket target = this.socket;
			this.sending = this.sending.exceptionally((ex) -> null).thenCompose((ignored) -> target.sendText(text, true));
		}

		void abort() {
			WebSocket target = this.socket;
			if (target != null) {
				target.abort();
			}
			closed("stopped");
		}

		void closed(String why) {
			if (!this.closed.compareAndSet(false, true)) {
				return;
			}
			this.session.close();
			if (HomeAssistantConnector.this.connection == this && HomeAssistantConnector.this.state.connected()) {
				HomeAssistantConnector.this.state.markDisconnected();
				publish();
			}
			if (HomeAssistantConnector.this.running) {
				logger.warn("Home Assistant connection lost: {}", why);
				scheduleReconnect();
			}
		}

		@Override
		public void onAuthenticated() {
			resetBackoff();
			logger.info("Authenticated with Home Assistant at {}", HomeAssistantConnector.this.config.url());
		}

		@Override
		public void onAuthFailed(String message) {
			logger.error("Home Assistant rejected the access token: {}", message);
			abort();
		}

		@Override
		public void onStates(List<EntityState> states) {
			HomeAssistantConnector.this.state.markConnected(states);
			publish();
		}

		@Override
		public void onStateChanged(String entityId, EntityState newState) {
			if (newState == null) {
				HomeAssistantConnector.this.state.remove(entityId);
			}
			else {
				HomeAssistantConnector.this.state.put(newState);
			}
			publish();
		}

	}

}
```

Note on `onAuthFailed`: `abort()` calls `closed(...)` while `running` is true, which schedules a reconnect with backoff. That is intended — a fixed token (after a restart) or a transient HA auth hiccup recovers on its own.

- [ ] **Step 5: Run the tests**

Run: `./mvnw -q test -Dtest=HomeAssistantConnectorTests -Dskip.npm -Dskip.installnodenpm`
Expected: PASS (5 tests).

- [ ] **Step 6: Run the whole backend suite**

Run: `./mvnw -q test -Dskip.npm -Dskip.installnodenpm`
Expected: PASS. `WebEndpointsTests` still loads because `enabled: false` in the test config keeps the connector out of the context.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Connect to Home Assistant over WebSocket with reconnect and backoff

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: World state

**Files:**
- Create: `src/main/java/net/toadhead/ha/screensaver/stream/WorldState.java`
- Create: `src/main/java/net/toadhead/ha/screensaver/stream/WorldStateAssembler.java`
- Create: `src/main/java/net/toadhead/ha/screensaver/stream/WorldStateService.java`
- Test: `src/test/java/net/toadhead/ha/screensaver/stream/WorldStateAssemblerTests.java`
- Test: `src/test/java/net/toadhead/ha/screensaver/stream/WorldStateServiceTests.java`

**Interfaces:**
- Consumes: `ScreensaverProperties` (Task 3), `HomeAssistantState`, `EntityState`, `HomeAssistantUpdated` (Task 5), `TestProperties` (test).
- Produces:
  - `record WorldState(boolean haConnected, List<SnakeValue> snakes)`; `record WorldState.SnakeValue(String id, Double value, String unit, boolean available)`.
  - `final class WorldStateAssembler`: `static WorldState assemble(ScreensaverProperties, HomeAssistantState)` — pinned snakes then guests, in config order.
  - `@Service WorldStateService`: `Versioned current()`; `record Versioned(long version, WorldState state)`; `@EventListener onHomeAssistantUpdated(HomeAssistantUpdated)`; `void refresh()` bumps the version only when the state actually changed.

- [ ] **Step 1: Write the failing tests**

`src/test/java/net/toadhead/ha/screensaver/stream/WorldStateAssemblerTests.java`:

```java
package net.toadhead.ha.screensaver.stream;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.toadhead.ha.screensaver.config.ScreensaverProperties;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.Snake;
import net.toadhead.ha.screensaver.config.TestProperties;
import net.toadhead.ha.screensaver.ha.EntityState;
import net.toadhead.ha.screensaver.ha.HomeAssistantState;
import net.toadhead.ha.screensaver.stream.WorldState.SnakeValue;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class WorldStateAssemblerTests {

	private final HomeAssistantState ha = new HomeAssistantState();

	@Test
	void numericStateBecomesValueWithUnit() {
		this.ha.markConnected(List.of(entity("sensor.power", "8.25", "{\"unit_of_measurement\":\"kW\"}")));
		WorldState state = WorldStateAssembler.assemble(properties("power", "sensor.power"), this.ha);
		assertThat(state.haConnected()).isTrue();
		assertThat(state.snakes()).containsExactly(new SnakeValue("power", 8.25, "kW", true));
	}

	@Test
	void entityWithoutUnitHasNullUnit() {
		this.ha.markConnected(List.of(entity("zone.home", "3", "{}")));
		WorldState state = WorldStateAssembler.assemble(properties("people", "zone.home"), this.ha);
		assertThat(state.snakes()).containsExactly(new SnakeValue("people", 3.0, null, true));
	}

	@Test
	void nonNumericStatesAreUnavailable() {
		for (String raw : List.of("unavailable", "unknown", "on", "", "NaN", "Infinity")) {
			this.ha.markConnected(List.of(entity("sensor.x", raw, "{}")));
			WorldState state = WorldStateAssembler.assemble(properties("x", "sensor.x"), this.ha);
			assertThat(state.snakes()).as(raw).containsExactly(new SnakeValue("x", null, null, false));
		}
	}

	@Test
	void missingEntityIsUnavailable() {
		this.ha.markConnected(List.of());
		WorldState state = WorldStateAssembler.assemble(properties("x", "sensor.missing"), this.ha);
		assertThat(state.snakes()).containsExactly(new SnakeValue("x", null, null, false));
	}

	@Test
	void keepsLastKnownValuesButReportsDisconnected() {
		this.ha.markConnected(List.of(entity("sensor.power", "5", "{}")));
		this.ha.markDisconnected();
		WorldState state = WorldStateAssembler.assemble(properties("power", "sensor.power"), this.ha);
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
		WorldState state = WorldStateAssembler.assemble(properties, this.ha);
		assertThat(state.snakes()).extracting(SnakeValue::id).containsExactly("b", "a", "g");
	}

	private static ScreensaverProperties properties(String id, String entity) {
		return TestProperties.withSnakes(Map.of(id, TestProperties.snake(entity, id)), Map.of());
	}

	static EntityState entity(String id, String state, String attributesJson) {
		return new EntityState(id, state, JsonMapper.shared().readTree(attributesJson), Instant.EPOCH);
	}

}
```

`src/test/java/net/toadhead/ha/screensaver/stream/WorldStateServiceTests.java`:

```java
package net.toadhead.ha.screensaver.stream;

import java.util.List;
import java.util.Map;

import net.toadhead.ha.screensaver.config.TestProperties;
import net.toadhead.ha.screensaver.ha.HomeAssistantState;
import net.toadhead.ha.screensaver.ha.HomeAssistantUpdated;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorldStateServiceTests {

	private final HomeAssistantState ha = new HomeAssistantState();

	private final WorldStateService service = new WorldStateService(
			TestProperties.withSnakes(Map.of("power", TestProperties.snake("sensor.power", "Power")), Map.of()),
			this.ha);

	@Test
	void startsAtVersionZero() {
		assertThat(this.service.current().version()).isZero();
		assertThat(this.service.current().state().haConnected()).isFalse();
	}

	@Test
	void bumpsVersionWhenStateChanges() {
		this.ha.markConnected(List.of(WorldStateAssemblerTests.entity("sensor.power", "1", "{}")));
		this.service.onHomeAssistantUpdated(new HomeAssistantUpdated());
		assertThat(this.service.current().version()).isEqualTo(1);
		assertThat(this.service.current().state().snakes().get(0).value()).isEqualTo(1.0);
	}

	@Test
	void keepsVersionWhenNothingRelevantChanged() {
		this.ha.markConnected(List.of(WorldStateAssemblerTests.entity("sensor.power", "1", "{}")));
		this.service.onHomeAssistantUpdated(new HomeAssistantUpdated());
		this.ha.put(WorldStateAssemblerTests.entity("light.kitchen", "on", "{}"));
		this.service.onHomeAssistantUpdated(new HomeAssistantUpdated());
		assertThat(this.service.current().version()).isEqualTo(1);
	}

}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -q test -Dtest='WorldState*Tests' -Dskip.npm -Dskip.installnodenpm`
Expected: compilation FAILURE — `WorldState` does not exist.

- [ ] **Step 3: Implement**

`src/main/java/net/toadhead/ha/screensaver/stream/WorldState.java`:

```java
package net.toadhead.ha.screensaver.stream;

import java.util.List;

/**
 * The derived data the scenes need, streamed to every kiosk as the {@code state}
 * event.
 *
 * @param haConnected whether Home Assistant is currently connected
 * @param snakes current value of every configured snake, pinned first
 */
public record WorldState(boolean haConnected, List<SnakeValue> snakes) {

	/**
	 * A snake's current value.
	 *
	 * @param id snake id from configuration
	 * @param value numeric value, or {@code null} when unavailable
	 * @param unit the entity's unit_of_measurement, or {@code null}
	 * @param available whether the value is usable
	 */
	public record SnakeValue(String id, Double value, String unit, boolean available) {

	}

}
```

`src/main/java/net/toadhead/ha/screensaver/stream/WorldStateAssembler.java`:

```java
package net.toadhead.ha.screensaver.stream;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

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

	static WorldState assemble(ScreensaverProperties properties, HomeAssistantState ha) {
		List<SnakeValue> snakes = Stream
			.concat(properties.snakes().pinned().entrySet().stream(), properties.snakes().guests().entrySet().stream())
			.map((entry) -> snakeValue(entry, ha))
			.toList();
		return new WorldState(ha.connected(), snakes);
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
```

`src/main/java/net/toadhead/ha/screensaver/stream/WorldStateService.java`:

```java
package net.toadhead.ha.screensaver.stream;

import net.toadhead.ha.screensaver.config.ScreensaverProperties;
import net.toadhead.ha.screensaver.ha.HomeAssistantState;
import net.toadhead.ha.screensaver.ha.HomeAssistantUpdated;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Holds the current {@link WorldState} and a version that increases each time it
 * actually changes.
 */
@Service
public class WorldStateService {

	private final ScreensaverProperties properties;

	private final HomeAssistantState ha;

	private volatile Versioned current;

	public WorldStateService(ScreensaverProperties properties, HomeAssistantState ha) {
		this.properties = properties;
		this.ha = ha;
		this.current = new Versioned(0, WorldStateAssembler.assemble(properties, ha));
	}

	public Versioned current() {
		return this.current;
	}

	@EventListener
	public void onHomeAssistantUpdated(HomeAssistantUpdated event) {
		refresh();
	}

	synchronized void refresh() {
		WorldState next = WorldStateAssembler.assemble(this.properties, this.ha);
		if (!next.equals(this.current.state())) {
			this.current = new Versioned(this.current.version() + 1, next);
		}
	}

	public record Versioned(long version, WorldState state) {

	}

}
```

- [ ] **Step 4: Run the tests**

Run: `./mvnw -q test -Dtest='WorldState*Tests' -Dskip.npm -Dskip.installnodenpm`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Derive versioned world state from Home Assistant entities

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: SSE stream

**Files:**
- Create: `src/main/java/net/toadhead/ha/screensaver/stream/StreamBroadcaster.java`
- Create: `src/main/java/net/toadhead/ha/screensaver/stream/StreamController.java`
- Test: `src/test/java/net/toadhead/ha/screensaver/stream/StreamBroadcasterTests.java`
- Test: `src/test/java/net/toadhead/ha/screensaver/stream/StreamControllerTests.java`

**Interfaces:**
- Consumes: `WorldStateService` (Task 7), `KioskConfigService`/`KioskConfig` (Task 4), `HomeAssistantState`, `HomeAssistantUpdated`, `EntityState` (Task 5).
- Produces:
  - `@Component StreamBroadcaster`: nested `interface Client { void send(String event, Object data) throws IOException; void heartbeat() throws IOException; }`; `void register(Client, KioskConfig) throws IOException` (sends `config` then `state` immediately); `void unregister(Client)`; `@Scheduled(fixedDelay = 1000) void broadcast()`; `@Scheduled(fixedRate = 15000, initialDelay = 15000) void heartbeat()`; `int clientCount()`.
  - `GET /api/stream?kiosk=<id>` (`text/event-stream`).

- [ ] **Step 1: Write the failing broadcaster tests**

`src/test/java/net/toadhead/ha/screensaver/stream/StreamBroadcasterTests.java`:

```java
package net.toadhead.ha.screensaver.stream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.toadhead.ha.screensaver.config.TestProperties;
import net.toadhead.ha.screensaver.ha.HomeAssistantState;
import net.toadhead.ha.screensaver.ha.HomeAssistantUpdated;
import net.toadhead.ha.screensaver.kiosk.KioskConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StreamBroadcasterTests {

	private final HomeAssistantState ha = new HomeAssistantState();

	private final WorldStateService worldState = new WorldStateService(
			TestProperties.withSnakes(Map.of("power", TestProperties.snake("sensor.power", "Power")), Map.of()),
			this.ha);

	private final StreamBroadcaster broadcaster = new StreamBroadcaster(this.worldState);

	private final KioskConfig config = new KioskConfig("kitchen", List.of(), new KioskConfig.ClockOptions(false),
			new KioskConfig.SnakeOptions(0, 4, 30, 2, 20, List.of(), List.of()));

	@Test
	void registerSendsConfigThenState() throws IOException {
		RecordingClient client = new RecordingClient();
		this.broadcaster.register(client, this.config);
		assertThat(client.events).containsExactly("config", "state");
		assertThat(client.data.get(0)).isEqualTo(this.config);
	}

	@Test
	void broadcastSendsNothingWhenUnchanged() throws IOException {
		RecordingClient client = new RecordingClient();
		this.broadcaster.register(client, this.config);
		this.broadcaster.broadcast();
		assertThat(client.events).containsExactly("config", "state");
	}

	@Test
	void broadcastSendsOneStateForSeveralChangesBetweenTicks() throws IOException {
		RecordingClient client = new RecordingClient();
		this.broadcaster.register(client, this.config);
		change("1");
		change("2");
		change("3");
		this.broadcaster.broadcast();
		this.broadcaster.broadcast();
		assertThat(client.events).containsExactly("config", "state", "state");
		WorldState last = (WorldState) client.data.get(2);
		assertThat(last.snakes().get(0).value()).isEqualTo(3.0);
	}

	@Test
	void dropsClientsThatFail() throws IOException {
		RecordingClient client = new RecordingClient();
		this.broadcaster.register(client, this.config);
		client.fail = true;
		change("1");
		this.broadcaster.broadcast();
		assertThat(this.broadcaster.clientCount()).isZero();
	}

	@Test
	void heartbeatPingsEveryClient() throws IOException {
		RecordingClient client = new RecordingClient();
		this.broadcaster.register(client, this.config);
		this.broadcaster.heartbeat();
		assertThat(client.heartbeats).isEqualTo(1);
	}

	@Test
	void unregisterStopsUpdates() throws IOException {
		RecordingClient client = new RecordingClient();
		this.broadcaster.register(client, this.config);
		this.broadcaster.unregister(client);
		change("1");
		this.broadcaster.broadcast();
		assertThat(client.events).containsExactly("config", "state");
	}

	private void change(String value) {
		this.ha.markConnected(List.of(WorldStateAssemblerTests.entity("sensor.power", value, "{}")));
		this.worldState.onHomeAssistantUpdated(new HomeAssistantUpdated());
	}

	static final class RecordingClient implements StreamBroadcaster.Client {

		final List<String> events = new ArrayList<>();

		final List<Object> data = new ArrayList<>();

		int heartbeats;

		boolean fail;

		@Override
		public void send(String event, Object payload) throws IOException {
			if (this.fail) {
				throw new IOException("broken pipe");
			}
			this.events.add(event);
			this.data.add(payload);
		}

		@Override
		public void heartbeat() throws IOException {
			if (this.fail) {
				throw new IOException("broken pipe");
			}
			this.heartbeats++;
		}

	}

}
```

- [ ] **Step 2: Write the failing end-to-end stream test**

`src/test/java/net/toadhead/ha/screensaver/stream/StreamControllerTests.java`:

```java
package net.toadhead.ha.screensaver.stream;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import net.toadhead.ha.screensaver.ha.HomeAssistantState;
import net.toadhead.ha.screensaver.ha.HomeAssistantUpdated;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "screensaver.snakes.pinned.power.entity=sensor.power",
				"screensaver.snakes.pinned.power.label=Power", "screensaver.snakes.pinned.power.min=0",
				"screensaver.snakes.pinned.power.max=100",
				// Much shorter than the test's wait, to prove the stream ignores it.
				"spring.mvc.async.request-timeout=1s" })
class StreamControllerTests {

	@LocalServerPort
	int port;

	@Autowired
	HomeAssistantState ha;

	@Autowired
	ApplicationEventPublisher events;

	private InputStream body;

	@AfterEach
	void close() throws Exception {
		if (this.body != null) {
			this.body.close();
		}
	}

	@Test
	void streamsConfigThenStateThenUpdatesAndOutlivesTheAsyncTimeout() throws Exception {
		BlockingQueue<String> lines = open("/api/stream?kiosk=kitchen");

		Event config = nextEvent(lines);
		assertThat(config.name()).isEqualTo("config");
		assertThat(config.data()).contains("\"kiosk\":\"kitchen\"").contains("\"type\":\"snakes\"");

		Event initial = nextEvent(lines);
		assertThat(initial.name()).isEqualTo("state");
		assertThat(initial.data()).contains("\"haConnected\":false");

		update("42.5");
		Event changed = nextEvent(lines);
		assertThat(changed.name()).isEqualTo("state");
		assertThat(changed.data()).contains("\"value\":42.5").contains("\"unit\":\"W\"");

		Thread.sleep(2500);
		update("43");
		Event later = nextEvent(lines);
		assertThat(later.data()).contains("\"value\":43.0");
	}

	private void update(String value) {
		this.ha.markConnected(
				List.of(WorldStateAssemblerTests.entity("sensor.power", value, "{\"unit_of_measurement\":\"W\"}")));
		this.events.publishEvent(new HomeAssistantUpdated());
	}

	private BlockingQueue<String> open(String path) throws Exception {
		HttpResponse<InputStream> response = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path)).build(),
					HttpResponse.BodyHandlers.ofInputStream());
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				(type) -> assertThat(type).startsWith("text/event-stream"));
		this.body = response.body();
		BlockingQueue<String> lines = new LinkedBlockingQueue<>();
		Thread.ofVirtual().start(() -> {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(this.body, StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					lines.add(line);
				}
			}
			catch (Exception ex) {
				// Stream closed at the end of the test.
			}
		});
		return lines;
	}

	/** Reads the next named event, skipping heartbeat comments. */
	private static Event nextEvent(BlockingQueue<String> lines) throws InterruptedException {
		String name = null;
		StringBuilder data = new StringBuilder();
		while (true) {
			String line = lines.poll(5, TimeUnit.SECONDS);
			assertThat(line).as("next SSE line").isNotNull();
			if (line.isEmpty()) {
				if (name != null) {
					return new Event(name, data.toString());
				}
				continue;
			}
			if (line.startsWith("event:")) {
				name = line.substring("event:".length()).trim();
			}
			else if (line.startsWith("data:")) {
				data.append(line.substring("data:".length()));
			}
		}
	}

	record Event(String name, String data) {

	}

}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./mvnw -q test -Dtest='Stream*Tests' -Dskip.npm -Dskip.installnodenpm`
Expected: compilation FAILURE — `StreamBroadcaster` does not exist.

- [ ] **Step 4: Implement**

`src/main/java/net/toadhead/ha/screensaver/stream/StreamBroadcaster.java`:

```java
package net.toadhead.ha.screensaver.stream;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.toadhead.ha.screensaver.kiosk.KioskConfig;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pushes world-state changes to connected kiosks, at most once per second per
 * kiosk, and keeps idle connections alive with heartbeats.
 */
@Component
public class StreamBroadcaster {

	private final WorldStateService worldState;

	/** Each client mapped to the last world-state version it was sent. */
	private final Map<Client, Long> clients = new ConcurrentHashMap<>();

	public StreamBroadcaster(WorldStateService worldState) {
		this.worldState = worldState;
	}

	public void register(Client client, KioskConfig config) throws IOException {
		WorldStateService.Versioned current = this.worldState.current();
		client.send("config", config);
		client.send("state", current.state());
		this.clients.put(client, current.version());
	}

	public void unregister(Client client) {
		this.clients.remove(client);
	}

	public int clientCount() {
		return this.clients.size();
	}

	@Scheduled(fixedDelay = 1000)
	public void broadcast() {
		WorldStateService.Versioned current = this.worldState.current();
		this.clients.forEach((client, sentVersion) -> {
			if (sentVersion < current.version()) {
				try {
					client.send("state", current.state());
					this.clients.put(client, current.version());
				}
				catch (IOException | IllegalStateException ex) {
					this.clients.remove(client);
				}
			}
		});
	}

	@Scheduled(fixedRate = 15000, initialDelay = 15000)
	public void heartbeat() {
		this.clients.keySet().forEach((client) -> {
			try {
				client.heartbeat();
			}
			catch (IOException | IllegalStateException ex) {
				this.clients.remove(client);
			}
		});
	}

	/**
	 * One kiosk's open stream.
	 */
	public interface Client {

		void send(String event, Object data) throws IOException;

		void heartbeat() throws IOException;

	}

}
```

`src/main/java/net/toadhead/ha/screensaver/stream/StreamController.java`:

```java
package net.toadhead.ha.screensaver.stream;

import java.io.IOException;

import net.toadhead.ha.screensaver.kiosk.KioskConfigService;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The kiosk stream: a {@code config} event, then {@code state} events.
 */
@RestController
public class StreamController {

	/** Zero means the servlet container never times the stream out. */
	private static final long NO_TIMEOUT = 0L;

	private final StreamBroadcaster broadcaster;

	private final KioskConfigService kioskConfigs;

	public StreamController(StreamBroadcaster broadcaster, KioskConfigService kioskConfigs) {
		this.broadcaster = broadcaster;
		this.kioskConfigs = kioskConfigs;
	}

	@GetMapping(path = "/api/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter stream(@RequestParam(defaultValue = "default") String kiosk) throws IOException {
		SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
		EmitterClient client = new EmitterClient(emitter);
		emitter.onCompletion(() -> this.broadcaster.unregister(client));
		emitter.onTimeout(() -> this.broadcaster.unregister(client));
		emitter.onError((ex) -> this.broadcaster.unregister(client));
		this.broadcaster.register(client, this.kioskConfigs.resolve(kiosk));
		return emitter;
	}

	private record EmitterClient(SseEmitter emitter) implements StreamBroadcaster.Client {

		@Override
		public void send(String event, Object data) throws IOException {
			this.emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
		}

		@Override
		public void heartbeat() throws IOException {
			this.emitter.send(SseEmitter.event().comment("ping"));
		}

	}

}
```

- [ ] **Step 5: Run the tests**

Run: `./mvnw -q test -Dtest='Stream*Tests' -Dskip.npm -Dskip.installnodenpm`
Expected: PASS (7 tests). The controller test takes about 3 s because of the deliberate wait past the async timeout.

- [ ] **Step 6: Run the whole backend suite**

Run: `./mvnw -q test -Dskip.npm -Dskip.installnodenpm`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Stream kiosk config and throttled world state over SSE

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Frontend types, stream client, reload policy, demo data

**Files:**
- Create: `src/main/frontend/src/types.ts`, `src/main/frontend/src/scene.ts`, `src/main/frontend/src/stream.ts`, `src/main/frontend/src/reload.ts`, `src/main/frontend/src/demo.ts`
- Test: `src/main/frontend/src/stream.test.ts`, `src/main/frontend/src/reload.test.ts`, `src/main/frontend/src/demo.test.ts`

**Interfaces:**
- Consumes: `PaletteName` (Task 2); JSON shapes from Tasks 4 and 7.
- Produces:
  - `types.ts`: `SceneType = 'clock' | 'snakes'`, `SceneConfig`, `SnakeDef`, `SnakeOptions`, `KioskConfig`, `SnakeValue`, `WorldState` (field names match the Java records exactly).
  - `scene.ts`: `SceneContext { config; state; random }`, `Scene { mount(container, ctx); update?(state); unmount() }`, `SceneModule { type; isEligible(config, state); create(): Scene }`.
  - `stream.ts`: `StreamHandlers { onConfig; onState; onConnection(connected, reconnected) }`; `openStream(url, handlers, options?) => { close() }`; `EventSourceLike`; `EventSourceFactory`.
  - `reload.ts`: `class ReloadPolicy { onConnection(connected, reconnected); shouldReloadNow(): boolean }`.
  - `demo.ts`: `DEMO_CONFIG: KioskConfig`, `demoState(timeMs): WorldState`, `startDemo(handlers)`.

- [ ] **Step 1: Write the type and scene contract files**

`src/main/frontend/src/types.ts`:

```ts
import type { PaletteName } from './palette';

/** Matches net.toadhead.ha.screensaver.config.SceneType ids. */
export type SceneType = 'clock' | 'snakes';

export interface SceneConfig {
  type: SceneType;
  weight: number;
  durationMs: number;
}

export interface SnakeDef {
  id: string;
  label: string;
  color: PaletteName | null;
  /** Overrides the entity's unit when set. */
  unit: string | null;
  precision: number;
  min: number;
  max: number;
}

export interface SnakeOptions {
  guestCount: number;
  minLength: number;
  maxLength: number;
  minSpeed: number;
  maxSpeed: number;
  pinned: SnakeDef[];
  guests: SnakeDef[];
}

/** The stream's `config` event (net.toadhead.ha.screensaver.kiosk.KioskConfig). */
export interface KioskConfig {
  kiosk: string;
  scenes: SceneConfig[];
  clock: { use24Hour: boolean };
  snakes: SnakeOptions;
}

export interface SnakeValue {
  id: string;
  value: number | null;
  unit: string | null;
  available: boolean;
}

/** The stream's `state` event (net.toadhead.ha.screensaver.stream.WorldState). */
export interface WorldState {
  haConnected: boolean;
  snakes: SnakeValue[];
}
```

`src/main/frontend/src/scene.ts`:

```ts
import type { KioskConfig, SceneType, WorldState } from './types';

export interface SceneContext {
  config: KioskConfig;
  state: WorldState;
  random: () => number;
}

/** One appearance of a scene on screen. */
export interface Scene {
  /** Builds the scene inside `container`, which is already attached and sized. */
  mount(container: HTMLElement, ctx: SceneContext): void;
  /** Receives live world-state updates while on screen. */
  update?(state: WorldState): void;
  /** Stops timers and animation. The host removes the container afterwards. */
  unmount(): void;
}

export interface SceneModule {
  type: SceneType;
  isEligible(config: KioskConfig, state: WorldState): boolean;
  create(): Scene;
}
```

- [ ] **Step 2: Write the failing tests**

`src/main/frontend/src/stream.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { openStream, type EventSourceLike, type StreamHandlers } from './stream';

class FakeSource implements EventSourceLike {
  readyState = 0;
  onopen: ((ev: Event) => unknown) | null = null;
  onerror: ((ev: Event) => unknown) | null = null;
  closed = false;
  listeners = new Map<string, (ev: MessageEvent) => void>();
  addEventListener(type: string, listener: (ev: MessageEvent) => void): void {
    this.listeners.set(type, listener);
  }
  close(): void {
    this.closed = true;
    this.readyState = 2;
  }
  open(): void {
    this.readyState = 1;
    this.onopen?.(new Event('open'));
  }
  fail(readyState: number): void {
    this.readyState = readyState;
    this.onerror?.(new Event('error'));
  }
  emit(type: string, data: unknown): void {
    this.listeners.get(type)?.({ data: JSON.stringify(data) } as MessageEvent);
  }
}

function setup() {
  const sources: FakeSource[] = [];
  const timers: (() => void)[] = [];
  const connections: [boolean, boolean][] = [];
  const configs: unknown[] = [];
  const states: unknown[] = [];
  const handlers: StreamHandlers = {
    onConfig: (c) => configs.push(c),
    onState: (s) => states.push(s),
    onConnection: (connected, reconnected) => connections.push([connected, reconnected]),
  };
  const stream = openStream('/api/stream?kiosk=x', handlers, {
    factory: () => {
      const source = new FakeSource();
      sources.push(source);
      return source;
    },
    setTimer: (fn) => timers.push(fn),
  });
  return { sources, timers, connections, configs, states, stream };
}

describe('openStream', () => {
  it('reports the first open as not a reconnect', () => {
    const t = setup();
    t.sources[0].open();
    expect(t.connections).toEqual([[true, false]]);
  });

  it('reports a reopen after an error as a reconnect', () => {
    const t = setup();
    t.sources[0].open();
    t.sources[0].fail(0); // CONNECTING: the browser retries on its own
    t.sources[0].open();
    expect(t.connections).toEqual([[true, false], [false, false], [true, true]]);
    expect(t.sources).toHaveLength(1);
  });

  it('opens a new EventSource when the browser gives up (e.g. HTTP 502)', () => {
    const t = setup();
    t.sources[0].open();
    t.sources[0].fail(2); // CLOSED: the browser will not retry
    expect(t.timers).toHaveLength(1);
    t.timers[0]();
    expect(t.sources).toHaveLength(2);
    t.sources[1].open();
    expect(t.connections.at(-1)).toEqual([true, true]);
  });

  it('keeps retrying while the backend stays down', () => {
    const t = setup();
    t.sources[0].fail(2);
    t.timers[0]();
    t.sources[1].fail(2);
    t.timers[1]();
    expect(t.sources).toHaveLength(3);
  });

  it('parses config and state events', () => {
    const t = setup();
    t.sources[0].emit('config', { kiosk: 'x' });
    t.sources[0].emit('state', { haConnected: true, snakes: [] });
    expect(t.configs).toEqual([{ kiosk: 'x' }]);
    expect(t.states).toEqual([{ haConnected: true, snakes: [] }]);
  });

  it('stops retrying after close', () => {
    const t = setup();
    t.stream.close();
    t.sources[0].fail(2);
    expect(t.timers).toHaveLength(0);
  });
});
```

`src/main/frontend/src/reload.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { ReloadPolicy } from './reload';

describe('ReloadPolicy', () => {
  it('does not reload after the first connect', () => {
    const policy = new ReloadPolicy();
    policy.onConnection(true, false);
    expect(policy.shouldReloadNow()).toBe(false);
  });

  it('reloads after a reconnect', () => {
    const policy = new ReloadPolicy();
    policy.onConnection(true, false);
    policy.onConnection(false, false);
    policy.onConnection(true, true);
    expect(policy.shouldReloadNow()).toBe(true);
  });

  it('waits while the stream is down again', () => {
    const policy = new ReloadPolicy();
    policy.onConnection(true, true);
    policy.onConnection(false, false);
    expect(policy.shouldReloadNow()).toBe(false);
    policy.onConnection(true, true);
    expect(policy.shouldReloadNow()).toBe(true);
  });
});
```

`src/main/frontend/src/demo.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { DEMO_CONFIG, demoState } from './demo';

describe('demo data', () => {
  it('has a value for every configured snake', () => {
    const ids = [...DEMO_CONFIG.snakes.pinned, ...DEMO_CONFIG.snakes.guests].map((s) => s.id);
    expect(demoState(0).snakes.map((s) => s.id).sort()).toEqual([...ids].sort());
  });

  it('keeps values within each snake range', () => {
    const defs = [...DEMO_CONFIG.snakes.pinned, ...DEMO_CONFIG.snakes.guests];
    for (const time of [0, 1234, 98765, 1_000_000]) {
      for (const value of demoState(time).snakes) {
        if (value.value === null) continue;
        const def = defs.find((d) => d.id === value.id)!;
        expect(value.value).toBeGreaterThanOrEqual(def.min);
        expect(value.value).toBeLessThanOrEqual(def.max);
      }
    }
  });

  it('includes an unavailable snake to show the frozen look', () => {
    expect(demoState(0).snakes.some((s) => !s.available)).toBe(true);
  });
});
```

- [ ] **Step 3: Run them to verify they fail**

Run: `cd src/main/frontend && npm test; cd -`
Expected: FAIL — cannot resolve `./stream`, `./reload`, `./demo`.

- [ ] **Step 4: Implement**

`src/main/frontend/src/stream.ts`:

```ts
import type { KioskConfig, WorldState } from './types';

export interface StreamHandlers {
  onConfig(config: KioskConfig): void;
  onState(state: WorldState): void;
  /** `reconnected` is true when this open follows an earlier successful open. */
  onConnection(connected: boolean, reconnected: boolean): void;
}

/** The parts of EventSource this module uses, so tests can substitute a fake. */
export interface EventSourceLike {
  readyState: number;
  onopen: ((ev: Event) => unknown) | null;
  onerror: ((ev: Event) => unknown) | null;
  addEventListener(type: string, listener: (ev: MessageEvent) => void): void;
  close(): void;
}

export type EventSourceFactory = (url: string) => EventSourceLike;

interface StreamOptions {
  factory?: EventSourceFactory;
  retryMs?: number;
  setTimer?: (fn: () => void, ms: number) => unknown;
}

const CLOSED = 2;

/**
 * Opens the kiosk stream and keeps it open. The browser retries dropped
 * connections itself, but gives up for good after a non-200 response (such as
 * HAProxy's 502 while the backend restarts), so this replaces a CLOSED source.
 */
export function openStream(url: string, handlers: StreamHandlers, options: StreamOptions = {}): { close(): void } {
  const factory = options.factory ?? ((u: string) => new EventSource(u) as unknown as EventSourceLike);
  const retryMs = options.retryMs ?? 5000;
  const setTimer = options.setTimer ?? ((fn: () => void, ms: number) => window.setTimeout(fn, ms));
  let everOpened = false;
  let stopped = false;
  let source: EventSourceLike;

  const connect = (): void => {
    const current = factory(url);
    source = current;
    current.onopen = () => {
      const reconnected = everOpened;
      everOpened = true;
      handlers.onConnection(true, reconnected);
    };
    current.onerror = () => {
      handlers.onConnection(false, false);
      if (current.readyState === CLOSED && !stopped) {
        current.close();
        setTimer(connect, retryMs);
      }
    };
    current.addEventListener('config', (e) => handlers.onConfig(JSON.parse(e.data) as KioskConfig));
    current.addEventListener('state', (e) => handlers.onState(JSON.parse(e.data) as WorldState));
  };

  connect();
  return {
    close() {
      stopped = true;
      source.close();
    },
  };
}
```

Note: the test "stops retrying after close" calls `fail(2)` after `close()`; `stopped` is true so no timer is scheduled.

`src/main/frontend/src/reload.ts`:

```ts
/**
 * Any backend restart (a deploy or a config change) drops the stream, so a
 * reconnect means code, scenes or config may have changed. The app reloads at
 * the next scene change, but only while connected, so Fully never loads the
 * page while the backend is down.
 */
export class ReloadPolicy {
  private pending = false;
  private connected = false;

  onConnection(connected: boolean, reconnected: boolean): void {
    this.connected = connected;
    if (connected && reconnected) {
      this.pending = true;
    }
  }

  shouldReloadNow(): boolean {
    return this.pending && this.connected;
  }
}
```

`src/main/frontend/src/demo.ts`:

```ts
import type { StreamHandlers } from './stream';
import type { KioskConfig, WorldState } from './types';

/** Canned config for `?demo`, for working on scenes without a backend. */
export const DEMO_CONFIG: KioskConfig = {
  kiosk: 'demo',
  scenes: [
    { type: 'clock', weight: 1, durationMs: 15_000 },
    { type: 'snakes', weight: 2, durationMs: 30_000 },
  ],
  clock: { use24Hour: false },
  snakes: {
    guestCount: 2,
    minLength: 4,
    maxLength: 30,
    minSpeed: 2,
    maxSpeed: 20,
    pinned: [
      { id: 'internet-download', label: 'Internet download bandwidth', color: 'light-green', unit: null, precision: 1, min: 0, max: 1000 },
      { id: 'house-power', label: 'House power consumption', color: 'light-red', unit: null, precision: 0, min: 0, max: 12000 },
    ],
    guests: [
      { id: 'outdoor-wind', label: 'Wind speed', color: null, unit: null, precision: 1, min: 0, max: 40 },
      { id: 'people-home', label: 'People home', color: null, unit: null, precision: 0, min: 0, max: 6 },
    ],
  },
};

/** Slowly varying values; `people-home` is unavailable to show the frozen look. */
export function demoState(timeMs: number): WorldState {
  const wave = (periodSeconds: number, phase: number) =>
    (Math.sin((timeMs / 1000) * ((2 * Math.PI) / periodSeconds) + phase) + 1) / 2;
  return {
    haConnected: true,
    snakes: [
      { id: 'internet-download', value: 1000 * wave(20, 0), unit: 'Mbit/s', available: true },
      { id: 'house-power', value: 12000 * wave(45, 1), unit: 'W', available: true },
      { id: 'outdoor-wind', value: 40 * wave(13, 2), unit: 'mph', available: true },
      { id: 'people-home', value: null, unit: null, available: false },
    ],
  };
}

export function startDemo(handlers: StreamHandlers): void {
  handlers.onConnection(true, false);
  handlers.onConfig(DEMO_CONFIG);
  handlers.onState(demoState(Date.now()));
  window.setInterval(() => handlers.onState(demoState(Date.now())), 1000);
}
```

- [ ] **Step 5: Run the tests**

Run: `cd src/main/frontend && npm test; cd -`
Expected: PASS (all palette, stream, reload, demo tests).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add frontend stream client with reconnect detection, reload policy, demo data

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: Scheduler

**Files:**
- Create: `src/main/frontend/src/scheduler.ts`
- Test: `src/main/frontend/src/scheduler.test.ts`

**Interfaces:**
- Consumes: `SceneType` (Task 9).
- Produces: `interface Candidate { key: string; type: SceneType; weight: number; durationMs: number }`; `mulberry32(seed): () => number`; `class Scheduler(random, now = Date.now)` with `next(candidates): Candidate | null` and `sideline(type, forMs = 600_000)`.

- [ ] **Step 1: Write the failing tests**

`src/main/frontend/src/scheduler.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { mulberry32, Scheduler, type Candidate } from './scheduler';

const clock: Candidate = { key: 'clock', type: 'clock', weight: 1, durationMs: 1000 };
const snakes: Candidate = { key: 'snakes', type: 'snakes', weight: 1, durationMs: 1000 };

describe('mulberry32', () => {
  it('is repeatable and in [0, 1)', () => {
    const a = mulberry32(42);
    const b = mulberry32(42);
    for (let i = 0; i < 100; i++) {
      const value = a();
      expect(value).toBe(b());
      expect(value).toBeGreaterThanOrEqual(0);
      expect(value).toBeLessThan(1);
    }
  });
});

describe('Scheduler', () => {
  it('returns null with no candidates', () => {
    expect(new Scheduler(mulberry32(1)).next([])).toBeNull();
  });

  it('repeats the only candidate', () => {
    const scheduler = new Scheduler(mulberry32(1));
    expect(scheduler.next([clock])).toBe(clock);
    expect(scheduler.next([clock])).toBe(clock);
  });

  it('never shows the same candidate twice in a row when there is a choice', () => {
    const scheduler = new Scheduler(mulberry32(7));
    const heavy = { ...snakes, weight: 100 };
    let previous = scheduler.next([clock, heavy]);
    for (let i = 0; i < 500; i++) {
      const next = scheduler.next([clock, heavy]);
      expect(next?.key).not.toBe(previous?.key);
      previous = next;
    }
  });

  it('respects weights', () => {
    const scheduler = new Scheduler(mulberry32(3));
    const a: Candidate = { key: 'a', type: 'clock', weight: 6, durationMs: 1 };
    const b: Candidate = { key: 'b', type: 'snakes', weight: 1, durationMs: 1 };
    const c: Candidate = { key: 'c', type: 'snakes', weight: 1, durationMs: 1 };
    const counts: Record<string, number> = { a: 0, b: 0, c: 0 };
    for (let i = 0; i < 9000; i++) {
      counts[scheduler.next([a, b, c])!.key]++;
    }
    // With no immediate repeats, `a` settles near 12/7 of `b` (it can't follow itself).
    expect(counts.a).toBeGreaterThan(counts.b * 1.5);
    expect(counts.a).toBeGreaterThan(counts.c * 1.5);
    expect(Math.abs(counts.b - counts.c)).toBeLessThan(400);
  });

  it('skips zero-weight candidates', () => {
    const scheduler = new Scheduler(mulberry32(1));
    for (let i = 0; i < 50; i++) {
      expect(scheduler.next([{ ...clock, weight: 0 }, snakes])).toBe(snakes);
    }
  });

  it('skips sidelined scene types until the sideline expires', () => {
    let now = 0;
    const scheduler = new Scheduler(mulberry32(1), () => now);
    scheduler.sideline('snakes', 1000);
    for (let i = 0; i < 20; i++) {
      expect(scheduler.next([clock, snakes])).toBe(clock);
    }
    now = 1000;
    const picks = new Set<string>();
    for (let i = 0; i < 20; i++) {
      picks.add(scheduler.next([clock, snakes])!.key);
    }
    expect(picks).toContain('snakes');
  });

  it('returns null when everything is sidelined', () => {
    const scheduler = new Scheduler(mulberry32(1), () => 0);
    scheduler.sideline('clock');
    expect(scheduler.next([clock])).toBeNull();
  });
});
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd src/main/frontend && npx vitest run src/scheduler.test.ts; cd -`
Expected: FAIL — cannot resolve `./scheduler`.

- [ ] **Step 3: Implement**

`src/main/frontend/src/scheduler.ts`:

```ts
import type { SceneType } from './types';

export interface Candidate {
  /** Identifies one rotation entry; the same key is never picked twice in a row. */
  key: string;
  type: SceneType;
  weight: number;
  durationMs: number;
}

/** Small seeded random generator, so tests can make "random" picks repeatable. */
export function mulberry32(seed: number): () => number {
  let state = seed | 0;
  return () => {
    state = (state + 0x6d2b79f5) | 0;
    let t = Math.imul(state ^ (state >>> 15), 1 | state);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Weighted random rotation with no immediate repeats and sidelining of broken scenes. */
export class Scheduler {
  private lastKey: string | null = null;
  private readonly sidelinedUntil = new Map<SceneType, number>();

  constructor(
    private readonly random: () => number,
    private readonly now: () => number = Date.now,
  ) {}

  sideline(type: SceneType, forMs = 10 * 60_000): void {
    this.sidelinedUntil.set(type, this.now() + forMs);
  }

  next(candidates: Candidate[]): Candidate | null {
    const time = this.now();
    const active = candidates.filter((c) => c.weight > 0 && (this.sidelinedUntil.get(c.type) ?? 0) <= time);
    const pool = active.length > 1 ? active.filter((c) => c.key !== this.lastKey) : active;
    if (pool.length === 0) {
      return null;
    }
    const total = pool.reduce((sum, c) => sum + c.weight, 0);
    let remaining = this.random() * total;
    let chosen = pool[pool.length - 1];
    for (const candidate of pool) {
      remaining -= candidate.weight;
      if (remaining < 0) {
        chosen = candidate;
        break;
      }
    }
    this.lastKey = chosen.key;
    return chosen;
  }
}
```

- [ ] **Step 4: Run the tests**

Run: `cd src/main/frontend && npm test; cd -`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add weighted scene scheduler with no repeats and sidelining

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 11: Clock scene

**Files:**
- Create: `src/main/frontend/src/scenes/clock/layout.ts`
- Create: `src/main/frontend/src/scenes/clock/clock.ts`
- Create: `src/main/frontend/src/scenes/clock/clock.css`
- Test: `src/main/frontend/src/scenes/clock/layout.test.ts`

**Interfaces:**
- Consumes: `Scene`, `SceneContext`, `SceneModule` (Task 9).
- Produces: `clockModule: SceneModule` (always eligible); pure helpers `placeWithin(area, face, random): Point`, `drift(position, area, face, random, maxStep = 8): Point`, `formatDigital(date, use24Hour): { time; suffix }`, `handAngles(date): { hour; minute; second }`, types `Box`, `Point`.

- [ ] **Step 1: Write the failing tests**

`src/main/frontend/src/scenes/clock/layout.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { mulberry32 } from '../../scheduler';
import { drift, formatDigital, handAngles, placeWithin } from './layout';

const area = { width: 1280, height: 800 };
const face = { width: 400, height: 200 };

describe('placeWithin', () => {
  it('keeps the face fully on screen', () => {
    const random = mulberry32(5);
    for (let i = 0; i < 500; i++) {
      const p = placeWithin(area, face, random);
      expect(p.x).toBeGreaterThanOrEqual(0);
      expect(p.y).toBeGreaterThanOrEqual(0);
      expect(p.x + face.width).toBeLessThanOrEqual(area.width);
      expect(p.y + face.height).toBeLessThanOrEqual(area.height);
    }
  });

  it('uses the whole available range', () => {
    expect(placeWithin(area, face, () => 0)).toEqual({ x: 0, y: 0 });
    expect(placeWithin(area, face, () => 0.999999)).toEqual({ x: 880, y: 600 });
  });

  it('pins to the corner when the face is bigger than the screen', () => {
    expect(placeWithin({ width: 100, height: 100 }, face, () => 0.5)).toEqual({ x: 0, y: 0 });
  });
});

describe('drift', () => {
  it('moves at most a few pixels and stays on screen', () => {
    const random = mulberry32(9);
    let p = { x: 0, y: 600 };
    for (let i = 0; i < 1000; i++) {
      const next = drift(p, area, face, random);
      expect(Math.abs(next.x - p.x)).toBeLessThanOrEqual(8);
      expect(Math.abs(next.y - p.y)).toBeLessThanOrEqual(8);
      expect(next.x).toBeGreaterThanOrEqual(0);
      expect(next.y + face.height).toBeLessThanOrEqual(area.height);
      p = next;
    }
  });
});

describe('formatDigital', () => {
  it('formats 12-hour time with a suffix', () => {
    expect(formatDigital(new Date(2026, 9, 9, 0, 5), false)).toEqual({ time: '12:05', suffix: 'AM' });
    expect(formatDigital(new Date(2026, 9, 9, 13, 7), false)).toEqual({ time: '1:07', suffix: 'PM' });
    expect(formatDigital(new Date(2026, 9, 9, 12, 0), false)).toEqual({ time: '12:00', suffix: 'PM' });
  });

  it('formats 24-hour time without a suffix', () => {
    expect(formatDigital(new Date(2026, 9, 9, 7, 3), true)).toEqual({ time: '07:03', suffix: '' });
    expect(formatDigital(new Date(2026, 9, 9, 23, 59), true)).toEqual({ time: '23:59', suffix: '' });
  });
});

describe('handAngles', () => {
  it('points the hands correctly', () => {
    expect(handAngles(new Date(2026, 9, 9, 3, 0, 0))).toEqual({ hour: 90, minute: 0, second: 0 });
    const half = handAngles(new Date(2026, 9, 9, 6, 30, 15));
    expect(half.hour).toBeCloseTo(195.125);
    expect(half.minute).toBeCloseTo(181.5);
    expect(half.second).toBe(90);
  });
});
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd src/main/frontend && npx vitest run src/scenes/clock; cd -`
Expected: FAIL — cannot resolve `./layout`.

- [ ] **Step 3: Implement the layout helpers**

`src/main/frontend/src/scenes/clock/layout.ts`:

```ts
export interface Box {
  width: number;
  height: number;
}

export interface Point {
  x: number;
  y: number;
}

/** A random position that keeps the face fully on screen (spreads OLED wear). */
export function placeWithin(area: Box, face: Box, random: () => number): Point {
  const maxX = Math.max(0, area.width - face.width);
  const maxY = Math.max(0, area.height - face.height);
  return { x: Math.floor(random() * (maxX + 1)), y: Math.floor(random() * (maxY + 1)) };
}

/** Moves the face up to `maxStep` pixels in each direction, staying on screen. */
export function drift(position: Point, area: Box, face: Box, random: () => number, maxStep = 8): Point {
  const step = () => Math.round((random() * 2 - 1) * maxStep);
  const clamp = (value: number, max: number) => Math.min(Math.max(value, 0), Math.max(0, max));
  return {
    x: clamp(position.x + step(), area.width - face.width),
    y: clamp(position.y + step(), area.height - face.height),
  };
}

const pad = (n: number) => String(n).padStart(2, '0');

export function formatDigital(date: Date, use24Hour: boolean): { time: string; suffix: string } {
  const hours = date.getHours();
  const minutes = pad(date.getMinutes());
  if (use24Hour) {
    return { time: `${pad(hours)}:${minutes}`, suffix: '' };
  }
  const hours12 = hours % 12 === 0 ? 12 : hours % 12;
  return { time: `${hours12}:${minutes}`, suffix: hours < 12 ? 'AM' : 'PM' };
}

/** Clockwise angles in degrees from 12 o'clock. */
export function handAngles(date: Date): { hour: number; minute: number; second: number } {
  const seconds = date.getSeconds();
  const minutes = date.getMinutes() + seconds / 60;
  const hours = (date.getHours() % 12) + minutes / 60;
  return { hour: hours * 30, minute: minutes * 6, second: seconds * 6 };
}
```

- [ ] **Step 4: Run the tests**

Run: `cd src/main/frontend && npx vitest run src/scenes/clock; cd -`
Expected: PASS.

- [ ] **Step 5: Implement the scene**

`src/main/frontend/src/scenes/clock/clock.css`:

```css
.clock-scene { background: #000000; }
.clock-face { position: absolute; left: 0; top: 0; transition: transform 2s ease; }

.clock-digital { font-family: 'VT323', ui-monospace, monospace; color: #AAAAAA; text-align: center; padding: 0.2em 0.5em; }
.clock-digital .time { font-size: 22vmin; line-height: 1; color: #FFFFFF; }
.clock-digital .suffix { font-size: 0.3em; margin-left: 0.25em; color: #55FFFF; }
.clock-digital .date { font-size: 6vmin; }

.clock-analog svg { width: 55vmin; height: 55vmin; display: block; }
.clock-analog .ring { fill: none; stroke: #555555; stroke-width: 2; }
.clock-analog .tick { stroke: #AAAAAA; stroke-width: 3; stroke-linecap: round; }
.clock-analog .hand { stroke-linecap: round; }
.clock-analog .hour { stroke: #FFFFFF; stroke-width: 7; }
.clock-analog .minute { stroke: #FFFFFF; stroke-width: 4; }
.clock-analog .second { stroke: #FF5555; stroke-width: 2; }
.clock-analog .hub { fill: #FF5555; }
```

`src/main/frontend/src/scenes/clock/clock.ts`:

```ts
import './clock.css';
import type { Scene, SceneContext, SceneModule } from '../../scene';
import { drift, formatDigital, handAngles, placeWithin, type Box, type Point } from './layout';

const SVG_NS = 'http://www.w3.org/2000/svg';

interface Face {
  element: HTMLElement;
  render(): void;
}

export const clockModule: SceneModule = {
  type: 'clock',
  isEligible: () => true,
  create: () => new ClockScene(),
};

class ClockScene implements Scene {
  private container!: HTMLElement;
  private face!: Face;
  private position: Point = { x: 0, y: 0 };
  private random: () => number = Math.random;
  private readonly timers: number[] = [];

  mount(container: HTMLElement, ctx: SceneContext): void {
    this.container = container;
    this.random = ctx.random;
    container.classList.add('clock-scene');
    this.face = ctx.random() < 0.5 ? analogFace() : digitalFace(ctx.config.clock.use24Hour);
    container.appendChild(this.face.element);
    this.face.render();
    this.position = placeWithin(this.area(), this.faceBox(), this.random);
    this.applyPosition();
    this.timers.push(window.setInterval(() => this.face.render(), 1000));
    this.timers.push(
      window.setInterval(() => {
        this.position = drift(this.position, this.area(), this.faceBox(), this.random);
        this.applyPosition();
      }, 60_000),
    );
  }

  unmount(): void {
    this.timers.forEach((id) => window.clearInterval(id));
  }

  private area(): Box {
    return { width: this.container.clientWidth, height: this.container.clientHeight };
  }

  private faceBox(): Box {
    return { width: this.face.element.offsetWidth, height: this.face.element.offsetHeight };
  }

  private applyPosition(): void {
    this.face.element.style.transform = `translate(${this.position.x}px, ${this.position.y}px)`;
  }
}

function digitalFace(use24Hour: boolean): Face {
  const element = document.createElement('div');
  element.className = 'clock-face clock-digital';
  const time = document.createElement('div');
  time.className = 'time';
  const date = document.createElement('div');
  date.className = 'date';
  element.append(time, date);
  return {
    element,
    render() {
      const now = new Date();
      const formatted = formatDigital(now, use24Hour);
      time.textContent = formatted.time;
      if (formatted.suffix) {
        const suffix = document.createElement('span');
        suffix.className = 'suffix';
        suffix.textContent = formatted.suffix;
        time.append(suffix);
      }
      date.textContent = now.toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric' });
    },
  };
}

function analogFace(): Face {
  const element = document.createElement('div');
  element.className = 'clock-face clock-analog';
  const svg = svgElement('svg', { viewBox: '0 0 200 200' });
  svg.append(svgElement('circle', { cx: 100, cy: 100, r: 96, class: 'ring' }));
  for (let i = 0; i < 12; i++) {
    svg.append(
      svgElement('line', {
        x1: 100, y1: 10, x2: 100, y2: i % 3 === 0 ? 26 : 18,
        class: 'tick', transform: `rotate(${i * 30} 100 100)`,
      }),
    );
  }
  const hour = svgElement('line', { x1: 100, y1: 108, x2: 100, y2: 55, class: 'hand hour' });
  const minute = svgElement('line', { x1: 100, y1: 112, x2: 100, y2: 30, class: 'hand minute' });
  const second = svgElement('line', { x1: 100, y1: 118, x2: 100, y2: 22, class: 'hand second' });
  svg.append(hour, minute, second, svgElement('circle', { cx: 100, cy: 100, r: 4, class: 'hub' }));
  element.append(svg);
  return {
    element,
    render() {
      const angles = handAngles(new Date());
      hour.setAttribute('transform', `rotate(${angles.hour} 100 100)`);
      minute.setAttribute('transform', `rotate(${angles.minute} 100 100)`);
      second.setAttribute('transform', `rotate(${angles.second} 100 100)`);
    },
  };
}

function svgElement(name: string, attributes: Record<string, string | number>): SVGElement {
  const node = document.createElementNS(SVG_NS, name);
  for (const [key, value] of Object.entries(attributes)) {
    node.setAttribute(key, String(value));
  }
  return node;
}
```

- [ ] **Step 6: Type-check**

Run: `cd src/main/frontend && npx tsc --noEmit; cd -`
Expected: no errors. (The scene is checked visually in Task 14.)

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Add clock scene with random digital/analog face and burn-in drift

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 12: Snake model

**Files:**
- Create: `src/main/frontend/src/scenes/snakes/model.ts`
- Test: `src/main/frontend/src/scenes/snakes/model.test.ts`

**Interfaces:**
- Consumes: `PaletteName`, `SNAKE_COLOR_PREFERENCE` (Task 2), `SnakeDef` (Task 9), `mulberry32` (tests, Task 10).
- Produces: `normalize(value, min, max)`, `targetLength(t, minLength, maxLength)`, `speedFor(t, minSpeed, maxSpeed)`, `formatValue(value, unit, precision, available)`, `assignColors(snakes): Map<string, PaletteName>`, `pickGuests(guests, count, random)`, `shade(index, length)`, `type Dir = 0|1|2|3`, `interface Cell { x; y }`, `class SnakeBody(start: Cell, dir: Dir)` with `cells`, `dir`, `advance(seconds, speed, targetLen, cols, rows, random)`, `step(cols, rows, random)`.

- [ ] **Step 1: Write the failing tests**

`src/main/frontend/src/scenes/snakes/model.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { mulberry32 } from '../../scheduler';
import type { SnakeDef } from '../../types';
import {
  assignColors, formatValue, normalize, pickGuests, shade, SnakeBody, speedFor, targetLength,
} from './model';

const def = (id: string, color: SnakeDef['color'] = null): SnakeDef => ({
  id, label: id, color, unit: null, precision: 1, min: 0, max: 100,
});

describe('value mapping', () => {
  it('normalizes and clamps', () => {
    expect(normalize(50, 0, 100)).toBe(0.5);
    expect(normalize(-5, 0, 100)).toBe(0);
    expect(normalize(500, 0, 100)).toBe(1);
    expect(normalize(5, 10, 10)).toBe(0);
  });

  it('interpolates length and speed', () => {
    expect(targetLength(0, 4, 30)).toBe(4);
    expect(targetLength(1, 4, 30)).toBe(30);
    expect(targetLength(0.5, 4, 30)).toBe(17);
    expect(speedFor(0, 2, 20)).toBe(2);
    expect(speedFor(0.5, 2, 20)).toBe(11);
  });
});

describe('formatValue', () => {
  it('shows value and unit', () => {
    expect(formatValue(243.14, 'Mbit/s', 1, true)).toBe('243.1 Mbit/s');
  });

  it('drops trailing zeros', () => {
    expect(formatValue(8.0, 'kW', 1, true)).toBe('8 kW');
    expect(formatValue(8.7, '%', 2, true)).toBe('8.7 %');
  });

  it('shows just the number without a unit', () => {
    expect(formatValue(3, null, 0, true)).toBe('3');
  });

  it('rounds to the precision', () => {
    expect(formatValue(1234.567, 'W', 0, true)).toBe('1235 W');
    expect(formatValue(-0.04, 'W', 1, true)).toBe('0 W');
  });

  it('shows unavailable', () => {
    expect(formatValue(null, 'W', 1, false)).toBe('unavailable');
    expect(formatValue(5, 'W', 1, false)).toBe('unavailable');
  });
});

describe('assignColors', () => {
  it('honors configured colors and fills the rest by preference', () => {
    const colors = assignColors([def('a', 'light-red'), def('b'), def('c')]);
    expect(colors.get('a')).toBe('light-red');
    expect(colors.get('b')).toBe('light-green');
    expect(colors.get('c')).toBe('light-cyan');
  });

  it('gives a later snake a new color when its configured color is taken', () => {
    const colors = assignColors([def('pinned', 'yellow'), def('guest', 'yellow')]);
    expect(colors.get('pinned')).toBe('yellow');
    expect(colors.get('guest')).not.toBe('yellow');
  });

  it('never repeats a color among 15 snakes', () => {
    const snakes = Array.from({ length: 15 }, (_, i) => def(`s${i}`));
    expect(new Set(assignColors(snakes).values()).size).toBe(15);
  });
});

describe('pickGuests', () => {
  it('picks the requested number of distinct guests', () => {
    const guests = ['a', 'b', 'c', 'd'].map((id) => def(id));
    const picked = pickGuests(guests, 2, mulberry32(4));
    expect(picked).toHaveLength(2);
    expect(new Set(picked.map((g) => g.id)).size).toBe(2);
  });

  it('varies between appearances', () => {
    const guests = ['a', 'b', 'c', 'd', 'e'].map((id) => def(id));
    const random = mulberry32(11);
    const seen = new Set<string>();
    for (let i = 0; i < 20; i++) {
      seen.add(pickGuests(guests, 1, random)[0].id);
    }
    expect(seen.size).toBeGreaterThan(2);
  });
});

describe('shade', () => {
  it('fades from head to tail', () => {
    expect(shade(0, 20)).toBe(1);
    expect(shade(6, 20)).toBe(0.7);
    expect(shade(11, 20)).toBe(0.45);
    expect(shade(19, 20)).toBe(0.22);
  });
});

describe('SnakeBody', () => {
  it('takes one step per cell of speed', () => {
    const body = new SnakeBody({ x: 10, y: 10 }, 0);
    body.advance(1, 5, 100, 80, 20, mulberry32(1));
    expect(body.cells).toHaveLength(6);
  });

  it('grows to and trims to the target length', () => {
    const body = new SnakeBody({ x: 10, y: 10 }, 0);
    body.advance(1, 20, 8, 80, 20, mulberry32(1));
    expect(body.cells).toHaveLength(8);
    body.advance(0, 20, 3, 80, 20, mulberry32(1));
    expect(body.cells).toHaveLength(3);
  });

  it('stays in bounds and never reverses', () => {
    const random = mulberry32(21);
    const body = new SnakeBody({ x: 0, y: 0 }, 2);
    for (let i = 0; i < 5000; i++) {
      const before = body.dir;
      body.step(10, 6, random);
      const head = body.cells[0];
      expect(head.x).toBeGreaterThanOrEqual(0);
      expect(head.x).toBeLessThan(10);
      expect(head.y).toBeGreaterThanOrEqual(0);
      expect(head.y).toBeLessThan(6);
      expect(body.dir).not.toBe((before + 2) % 4);
    }
  });
});
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd src/main/frontend && npx vitest run src/scenes/snakes; cd -`
Expected: FAIL — cannot resolve `./model`.

- [ ] **Step 3: Implement**

`src/main/frontend/src/scenes/snakes/model.ts`:

```ts
import { SNAKE_COLOR_PREFERENCE, type PaletteName } from '../../palette';
import type { SnakeDef } from '../../types';

/** 0 right, 1 down, 2 left, 3 up. */
export type Dir = 0 | 1 | 2 | 3;

export interface Cell {
  x: number;
  y: number;
}

const DX = [1, 0, -1, 0];
const DY = [0, 1, 0, -1];

/** Chance per step that a snake turns even though it could go straight. */
const TURN_CHANCE = 0.12;

export function normalize(value: number, min: number, max: number): number {
  if (max <= min) {
    return 0;
  }
  return Math.min(1, Math.max(0, (value - min) / (max - min)));
}

const lerp = (a: number, b: number, t: number) => a + (b - a) * t;

export function targetLength(t: number, minLength: number, maxLength: number): number {
  return Math.max(1, Math.round(lerp(minLength, maxLength, t)));
}

export function speedFor(t: number, minSpeed: number, maxSpeed: number): number {
  return lerp(minSpeed, maxSpeed, t);
}

/** Legend value: at most `precision` decimals, trailing zeros dropped, unit if known. */
export function formatValue(value: number | null, unit: string | null, precision: number, available: boolean): string {
  if (!available || value === null) {
    return 'unavailable';
  }
  const rounded = Number(value.toFixed(precision));
  const text = Object.is(rounded, -0) ? '0' : String(rounded);
  return unit ? `${text} ${unit}` : text;
}

/**
 * Gives every snake a distinct color. Pass pinned snakes first: configured colors
 * are honored in order, and a later snake whose color is taken gets an unused one.
 */
export function assignColors(snakes: SnakeDef[]): Map<string, PaletteName> {
  const result = new Map<string, PaletteName>();
  const used = new Set<PaletteName>();
  for (const snake of snakes) {
    if (snake.color && snake.color !== 'black' && !used.has(snake.color)) {
      result.set(snake.id, snake.color);
      used.add(snake.color);
    }
  }
  for (const snake of snakes) {
    if (!result.has(snake.id)) {
      const color = SNAKE_COLOR_PREFERENCE.find((c) => !used.has(c));
      if (color) {
        result.set(snake.id, color);
        used.add(color);
      }
    }
  }
  return result;
}

export function pickGuests(guests: SnakeDef[], count: number, random: () => number): SnakeDef[] {
  const shuffled = [...guests];
  for (let i = shuffled.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [shuffled[i], shuffled[j]] = [shuffled[j], shuffled[i]];
  }
  return shuffled.slice(0, count);
}

/** Opacity for body segment `index`: full block at the head, fading like ▓ ▒ ░ to the tail. */
export function shade(index: number, length: number): number {
  const f = index / Math.max(1, length);
  if (f < 0.25) return 1;
  if (f < 0.5) return 0.7;
  if (f < 0.75) return 0.45;
  return 0.22;
}

/** A snake's cells (head first) on a character grid. */
export class SnakeBody {
  readonly cells: Cell[];
  dir: Dir;
  private progress = 0;

  constructor(start: Cell, dir: Dir) {
    this.cells = [start];
    this.dir = dir;
  }

  /** Moves `seconds × speed` cells, growing toward or trimming to `targetLen`. */
  advance(seconds: number, speed: number, targetLen: number, cols: number, rows: number, random: () => number): void {
    this.progress += seconds * speed;
    while (this.progress >= 1) {
      this.progress -= 1;
      this.step(cols, rows, random);
      if (this.cells.length > targetLen) {
        this.cells.pop();
      }
    }
    while (this.cells.length > targetLen) {
      this.cells.pop();
    }
  }

  step(cols: number, rows: number, random: () => number): void {
    const left = ((this.dir + 3) % 4) as Dir;
    const right = ((this.dir + 1) % 4) as Dir;
    const turns: Dir[] = random() < 0.5 ? [left, right] : [right, left];
    const options: Dir[] = random() < TURN_CHANCE ? [...turns, this.dir] : [this.dir, ...turns];
    const head = this.cells[0];
    for (const dir of options) {
      const x = head.x + DX[dir];
      const y = head.y + DY[dir];
      if (x >= 0 && x < cols && y >= 0 && y < rows) {
        this.dir = dir;
        this.cells.unshift({ x, y });
        return;
      }
    }
    // Only reachable on a one-cell-wide grid: stay put this step.
  }
}
```

- [ ] **Step 4: Run the tests**

Run: `cd src/main/frontend && npm test; cd -`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add snake model: value mapping, legend formatting, colors, movement

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 13: Snakes scene

**Files:**
- Create: `src/main/frontend/src/scenes/snakes/snakes.ts`
- Create: `src/main/frontend/src/scenes/snakes/snakes.css`

**Interfaces:**
- Consumes: everything in `model.ts` (Task 12), `PALETTE` (Task 2), `Scene`/`SceneModule` (Task 9), `SnakeDef`, `WorldState` (Task 9).
- Produces: `snakesModule: SceneModule`, eligible when at least one snake is configured.

- [ ] **Step 1: Write the styles**

`src/main/frontend/src/scenes/snakes/snakes.css`:

```css
.snakes-scene { display: flex; flex-direction: column; background: #000000; }
.snakes-scene .field { position: relative; flex: 1 1 0; min-height: 0; }
.snakes-scene canvas { position: absolute; inset: 0; width: 100%; height: 100%; display: block; }
.snakes-scene .legend {
  font-family: 'VT323', ui-monospace, monospace;
  font-size: 3.2vmin;
  line-height: 1.2;
  color: #AAAAAA;
  display: flex;
  flex-wrap: wrap;
  gap: 0.2em 1.6em;
  padding: 0.3em 0.8em 0.5em;
}
.snakes-scene .swatch { margin-right: 0.4em; }
```

- [ ] **Step 2: Write the scene**

`src/main/frontend/src/scenes/snakes/snakes.ts`:

```ts
import './snakes.css';
import { PALETTE } from '../../palette';
import type { Scene, SceneContext, SceneModule } from '../../scene';
import type { SnakeDef, SnakeOptions, WorldState } from '../../types';
import { assignColors, formatValue, normalize, pickGuests, shade, SnakeBody, speedFor, targetLength, type Dir } from './model';

/** Text-mode width; cells are twice as tall as wide, like 80×25 characters. */
const COLUMNS = 80;
const UNAVAILABLE_COLOR = PALETTE['dark-gray'];

interface Entry {
  def: SnakeDef;
  color: string;
  body: SnakeBody;
  value: number | null;
  available: boolean;
  swatch: HTMLElement;
  text: HTMLElement;
}

export const snakesModule: SceneModule = {
  type: 'snakes',
  isEligible: (config) => config.snakes.pinned.length + config.snakes.guests.length > 0,
  create: () => new SnakesScene(),
};

class SnakesScene implements Scene {
  private canvas!: HTMLCanvasElement;
  private g!: CanvasRenderingContext2D;
  private options!: SnakeOptions;
  private entries: Entry[] = [];
  private random: () => number = Math.random;
  private rows = 1;
  private cellWidth = 1;
  private cellHeight = 2;
  private offsetY = 0;
  private frameId = 0;
  private lastFrame = 0;

  mount(container: HTMLElement, ctx: SceneContext): void {
    this.options = ctx.config.snakes;
    this.random = ctx.random;
    container.classList.add('snakes-scene');
    const field = document.createElement('div');
    field.className = 'field';
    this.canvas = document.createElement('canvas');
    field.append(this.canvas);
    const legend = document.createElement('div');
    legend.className = 'legend';
    container.append(field, legend);

    const chosen = [...this.options.pinned, ...pickGuests(this.options.guests, this.options.guestCount, ctx.random)];
    const colors = assignColors(chosen);
    const items = chosen.map((def) => {
      const item = document.createElement('span');
      const swatch = document.createElement('span');
      swatch.className = 'swatch';
      swatch.textContent = '█▓▒░';
      const text = document.createElement('span');
      item.append(swatch, text);
      legend.append(item);
      return { def, swatch, text, color: PALETTE[colors.get(def.id) ?? 'light-gray'] };
    });

    // Fill the legend before measuring, so the snake field gets the remaining height.
    this.entries = items.map((item) => ({ ...item, body: new SnakeBody({ x: 0, y: 0 }, 0), value: null, available: false }));
    this.applyState(ctx.state);
    this.layout();
    for (const entry of this.entries) {
      entry.body = new SnakeBody(
        { x: Math.floor(this.random() * COLUMNS), y: Math.floor(this.random() * this.rows) },
        Math.floor(this.random() * 4) as Dir,
      );
    }
    this.lastFrame = performance.now();
    this.frameId = requestAnimationFrame((time) => this.frame(time));
  }

  update(state: WorldState): void {
    this.applyState(state);
  }

  unmount(): void {
    cancelAnimationFrame(this.frameId);
  }

  private applyState(state: WorldState): void {
    for (const entry of this.entries) {
      const current = state.snakes.find((s) => s.id === entry.def.id);
      entry.value = current?.value ?? null;
      entry.available = !!current && current.available && current.value !== null;
      const unit = entry.def.unit ?? current?.unit ?? null;
      entry.text.textContent = `${entry.def.label} (${formatValue(entry.value, unit, entry.def.precision, entry.available)})`;
      entry.swatch.style.color = entry.available ? entry.color : UNAVAILABLE_COLOR;
    }
  }

  private layout(): void {
    const dpr = window.devicePixelRatio || 1;
    this.canvas.width = Math.max(1, Math.round(this.canvas.clientWidth * dpr));
    this.canvas.height = Math.max(1, Math.round(this.canvas.clientHeight * dpr));
    this.g = this.canvas.getContext('2d')!;
    this.cellWidth = this.canvas.width / COLUMNS;
    this.cellHeight = this.cellWidth * 2;
    this.rows = Math.max(1, Math.floor(this.canvas.height / this.cellHeight));
    this.offsetY = (this.canvas.height - this.rows * this.cellHeight) / 2;
  }

  private frame(time: number): void {
    const seconds = Math.min(0.25, (time - this.lastFrame) / 1000);
    this.lastFrame = time;
    for (const entry of this.entries) {
      // Unavailable snakes freeze where they are.
      if (!entry.available || entry.value === null) continue;
      const t = normalize(entry.value, entry.def.min, entry.def.max);
      entry.body.advance(
        seconds,
        speedFor(t, this.options.minSpeed, this.options.maxSpeed),
        targetLength(t, this.options.minLength, this.options.maxLength),
        COLUMNS,
        this.rows,
        this.random,
      );
    }
    this.draw();
    this.frameId = requestAnimationFrame((next) => this.frame(next));
  }

  private draw(): void {
    const g = this.g;
    g.globalAlpha = 1;
    g.fillStyle = '#000000';
    g.fillRect(0, 0, this.canvas.width, this.canvas.height);
    for (const entry of this.entries) {
      g.fillStyle = entry.available ? entry.color : UNAVAILABLE_COLOR;
      const length = entry.body.cells.length;
      entry.body.cells.forEach((cell, index) => {
        g.globalAlpha = shade(index, length);
        g.fillRect(
          Math.floor(cell.x * this.cellWidth),
          Math.floor(this.offsetY + cell.y * this.cellHeight),
          Math.ceil(this.cellWidth),
          Math.ceil(this.cellHeight),
        );
      });
    }
    g.globalAlpha = 1;
  }
}
```

- [ ] **Step 3: Type-check**

Run: `cd src/main/frontend && npx tsc --noEmit; cd -`
Expected: no errors. (The scene is checked visually in Task 14.)

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "Add NetWare-style snakes scene with live legend

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 14: Scene host and app bootstrap

**Files:**
- Create: `src/main/frontend/src/host.ts`, `src/main/frontend/src/styles.css`
- Modify: `src/main/frontend/src/main.ts` (replace the placeholder)

**Interfaces:**
- Consumes: `Scheduler`, `Candidate` (Task 10), `ReloadPolicy`, `openStream`, `startDemo`, `StreamHandlers` (Task 9), `clockModule` (Task 11), `snakesModule` (Task 13), `Scene`, `SceneContext`, `SceneModule` (Task 9).
- Produces: `class SceneHost(stage, fadeMs = 1000)` with `show(scene, ctx)` (throws if `mount` throws, leaving the previous scene up) and `update(state)`. The running app: `?kiosk=<id>`, `?scene=<type>`, `?demo`.

- [ ] **Step 1: Write the host and base styles**

`src/main/frontend/src/styles.css`:

```css
html, body { margin: 0; height: 100%; background: #000000; overflow: hidden; cursor: none; }
#stage { position: fixed; inset: 0; }
.layer { position: absolute; inset: 0; overflow: hidden; opacity: 0; transition: opacity 1s ease; }
.layer.visible { opacity: 1; }
#status {
  position: fixed; right: 1.5vmin; bottom: 1.5vmin; z-index: 10;
  width: 1vmin; height: 1vmin; border-radius: 50%;
  background: #AA5500; opacity: 0.6;
}
```

`src/main/frontend/src/host.ts`:

```ts
import type { Scene, SceneContext } from './scene';
import type { WorldState } from './types';

interface Showing {
  scene: Scene;
  layer: HTMLElement;
}

/** Shows one scene at a time, crossfading between them. */
export class SceneHost {
  private current: Showing | null = null;

  constructor(
    private readonly stage: HTMLElement,
    private readonly fadeMs = 1000,
  ) {}

  /** Mounts `scene` and fades it in. If mounting throws, the previous scene stays up. */
  show(scene: Scene, ctx: SceneContext): void {
    const layer = document.createElement('div');
    layer.className = 'layer';
    this.stage.appendChild(layer);
    try {
      scene.mount(layer, ctx);
    } catch (error) {
      layer.remove();
      throw error;
    }
    // Two frames so the browser renders opacity 0 before transitioning to 1.
    requestAnimationFrame(() => requestAnimationFrame(() => layer.classList.add('visible')));
    const previous = this.current;
    this.current = { scene, layer };
    if (previous) {
      previous.layer.classList.remove('visible');
      window.setTimeout(() => this.dispose(previous), this.fadeMs);
    }
  }

  update(state: WorldState): void {
    this.current?.scene.update?.(state);
  }

  private dispose(showing: Showing): void {
    try {
      showing.scene.unmount();
    } catch (error) {
      console.error('Scene failed to unmount', error);
    }
    showing.layer.remove();
  }
}
```

- [ ] **Step 2: Write the bootstrap**

Replace `src/main/frontend/src/main.ts`:

```ts
import '@fontsource/vt323';
import './styles.css';
import { startDemo } from './demo';
import { SceneHost } from './host';
import { ReloadPolicy } from './reload';
import type { SceneContext, SceneModule } from './scene';
import { clockModule } from './scenes/clock/clock';
import { snakesModule } from './scenes/snakes/snakes';
import { Scheduler, type Candidate } from './scheduler';
import { openStream, type StreamHandlers } from './stream';
import type { KioskConfig, SceneType, WorldState } from './types';

const MODULES: Record<SceneType, SceneModule> = { clock: clockModule, snakes: snakesModule };
const RETRY_MS = 5000;
const FALLBACK_AFTER_MS = 5000;
const FORCED_SCENE_MS = 60_000;

const params = new URLSearchParams(location.search);
const kiosk = params.get('kiosk') ?? 'default';
const forced = params.get('scene') as SceneType | null;

const status = document.getElementById('status') as HTMLElement;
const host = new SceneHost(document.getElementById('stage') as HTMLElement);
const scheduler = new Scheduler(Math.random);
const reload = new ReloadPolicy();

let config: KioskConfig | null = null;
let state: WorldState | null = null;
let streamConnected = false;
let started = false;
let currentType: SceneType | null = null;
let timer = 0;

function context(cfg: KioskConfig, st: WorldState): SceneContext {
  return { config: cfg, state: st, random: Math.random };
}

function candidates(cfg: KioskConfig, st: WorldState): Candidate[] {
  if (forced) {
    return MODULES[forced]?.isEligible(cfg, st)
      ? [{ key: forced, type: forced, weight: 1, durationMs: FORCED_SCENE_MS }]
      : [];
  }
  return cfg.scenes
    .filter((scene) => MODULES[scene.type]?.isEligible(cfg, st))
    .map((scene) => ({ key: scene.type, type: scene.type, weight: scene.weight, durationMs: scene.durationMs }));
}

function schedule(ms: number): void {
  window.clearTimeout(timer);
  timer = window.setTimeout(showNext, ms);
}

function showNext(): void {
  if (reload.shouldReloadNow()) {
    location.reload();
    return;
  }
  if (!config || !state) {
    schedule(RETRY_MS);
    return;
  }
  const next = scheduler.next(candidates(config, state));
  if (!next) {
    schedule(RETRY_MS);
    return;
  }
  try {
    host.show(MODULES[next.type].create(), context(config, state));
    currentType = next.type;
    schedule(next.durationMs);
  } catch (error) {
    console.error(`Scene "${next.type}" failed to start`, error);
    scheduler.sideline(next.type);
    schedule(0);
  }
}

/** Something to look at if the backend is slow to answer on first load. */
function showFallbackClock(): void {
  if (started) return;
  const fallback: KioskConfig = config ?? {
    kiosk,
    scenes: [],
    clock: { use24Hour: false },
    snakes: { guestCount: 0, minLength: 4, maxLength: 30, minSpeed: 2, maxSpeed: 20, pinned: [], guests: [] },
  };
  host.show(clockModule.create(), context(fallback, state ?? { haConnected: false, snakes: [] }));
  currentType = 'clock';
}

function maybeStart(): void {
  if (started || !config || !state) return;
  started = true;
  showNext();
}

function updateStatus(): void {
  status.hidden = streamConnected && (state?.haConnected ?? true);
}

const handlers: StreamHandlers = {
  onConfig(next) {
    config = next;
    maybeStart();
  },
  onState(next) {
    state = next;
    updateStatus();
    if (started) {
      try {
        host.update(next);
      } catch (error) {
        console.error(`Scene "${currentType}" failed to update`, error);
        if (currentType) scheduler.sideline(currentType);
        showNext();
      }
    }
    maybeStart();
  },
  onConnection(connected, reconnected) {
    streamConnected = connected;
    reload.onConnection(connected, reconnected);
    updateStatus();
  },
};

// Measure scenes with the real font, not a fallback.
void document.fonts.ready.then(() => {
  if (params.has('demo')) {
    startDemo(handlers);
  } else {
    openStream(`/api/stream?kiosk=${encodeURIComponent(kiosk)}`, handlers);
  }
  window.setTimeout(showFallbackClock, FALLBACK_AFTER_MS);
});
```

- [ ] **Step 3: Type-check and run all frontend tests**

Run: `cd src/main/frontend && npx tsc --noEmit && npm test; cd -`
Expected: no type errors; all tests PASS.

- [ ] **Step 4: Verify visually in demo mode**

Run: `cd src/main/frontend && npx vite --port 5173; cd -` (leave it running), then open each URL in a desktop browser:

1. `http://localhost:5173/?demo&scene=snakes` — black screen with snakes moving on an 80-column grid. Legend at the bottom: `█▓▒░ Internet download bandwidth (… Mbit/s)` in light green, `… House power consumption (… W)` in light red, and two guest entries; values update every second. "People home (unavailable)" appears with a dark gray swatch whenever it's picked as a guest, and its snake stays frozen. Faster/longer snakes as values rise.
2. `http://localhost:5173/?demo&scene=clock` — reload a few times: digital and analog faces both appear, at different positions, never cut off.
3. `http://localhost:5173/?demo` — scenes crossfade; clock and snakes alternate (never the same scene twice in a row, since there are two).
4. Resize the window to a portrait shape and reload `?demo&scene=snakes` — legend wraps; snakes stay inside the field.

Stop the dev server (Ctrl+C).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add scene host with crossfades and app bootstrap with reload-on-reconnect

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 15: Packaging, README, and end-to-end check

**Files:**
- Create: `README.md`
- Create: `config/application.example.yaml`

**Interfaces:**
- Consumes: everything above.
- Produces: documented build, run, Docker image, Fully Kiosk, and HAProxy setup.

- [ ] **Step 1: Write the example configuration**

`config/application.example.yaml`:

```yaml
# Copy to config/application.yaml (git-ignored is up to you) and mount it into the
# container, or put these properties in Spring Cloud Config.
screensaver:
  home-assistant:
    url: ws://homeassistant.lan:8123/api/websocket
    token: ${HA_TOKEN}

  clock:
    use-24-hour: false

  snakes:
    guest-count: 2
    pinned:
      internet-download:
        entity: sensor.wan_download_mbps
        label: Internet download bandwidth
        min: 0
        max: 1000
        color: light-green
      house-power:
        entity: sensor.house_power_w
        label: House power consumption
        min: 0
        max: 12000
        color: light-red
        precision: 0
    guests:
      outdoor-wind:
        entity: sensor.wind_speed
        label: Wind speed
        min: 0
        max: 40
      people-home:
        entity: zone.home
        label: People home
        min: 0
        max: 6
        precision: 0

  rotation:
    scenes:
      clock: { weight: 2, duration: 20s }
      snakes: { weight: 2, duration: 45s }

  kiosks:
    bedroom:
      scenes:
        snakes: { enabled: false }
```

- [ ] **Step 2: Write the README**

`README.md`:

````markdown
# HA Screensaver

A screensaver for wall-mounted Fully Kiosk tablets: a Spring Boot app that connects to Home Assistant and rotates scenes in the browser. This version has the clock and the NetWare-style snakes. Design: `docs/superpowers/specs/2026-10-08-ha-screensaver-design.md`.

## Build

```bash
./mvnw package                # backend + frontend + all tests
./mvnw spring-boot:build-image -Dspring-boot.build-image.imageName=ha-screensaver:latest
```

Backend-only iteration: `./mvnw test -Dskip.npm -Dskip.installnodenpm`.

## Run

```bash
docker run -d --name ha-screensaver -p 8080:8080 \
  -e HA_TOKEN=<long-lived access token> \
  -e SPRING_CONFIG_ADDITIONAL_LOCATION=/config/ \
  -v /srv/ha-screensaver/config:/config:ro \
  ha-screensaver:latest
```

`/srv/ha-screensaver/config/application.yaml` holds the `screensaver.*` settings — start from `config/application.example.yaml`. Create the token in Home Assistant under your profile → Security → Long-lived access tokens.

Config is read at startup. After editing it, restart the container; every kiosk reloads itself once it reconnects. An invalid config stops startup with the validation errors in the container log.

Health and build info: `/actuator/health`, `/actuator/info`.

## Fully Kiosk

Set each tablet's screensaver URL to `https://<host>/?kiosk=<id>`, where `<id>` matches a key under `screensaver.kiosks` (any other id gets the defaults). The screensaver timeout and schedule stay in Fully's settings.

## HAProxy

The kiosks hold a Server-Sent Events connection open. The app sends a heartbeat every 15 s, so any `timeout server` / `timeout client` of 30 s or more is fine. Don't enable compression for `text/event-stream` — it buffers the stream.

## Working on scenes

```bash
cd src/main/frontend
npx vite
```

- `http://localhost:5173/?demo` — canned data, no backend needed.
- `?scene=snakes` or `?scene=clock` — show only that scene.
- Without `?demo`, the dev server proxies `/api` to a Boot app on `localhost:8080`.

The small orange dot in the bottom-right corner means the kiosk has lost the stream or the backend has lost Home Assistant.
````

- [ ] **Step 3: Full build**

Run: `./mvnw clean package`
Expected: BUILD SUCCESS; all Java and Vitest tests pass; `target/ha-screensaver-0.0.1-SNAPSHOT.jar` exists.

- [ ] **Step 4: End-to-end check against the real Home Assistant**

Create `config/application.yaml` from the example with real entity ids, then:

```bash
HA_TOKEN=<token> java -jar target/ha-screensaver-0.0.1-SNAPSHOT.jar --spring.config.additional-location=file:config/
```

Check, in order:
1. Log shows `Authenticated with Home Assistant at …`.
2. `curl -sN http://localhost:8080/api/stream?kiosk=kitchen | head -6` prints `event:config`, a `data:` line with the kiosk config, `event:state`, and a `data:` line with `"haConnected":true` and real snake values.
3. `curl -sI http://localhost:8080/ | grep -i cache-control` shows `no-cache`.
4. `curl -s http://localhost:8080/actuator/info` includes `git` and `build` sections.
5. Open `http://localhost:8080/?kiosk=kitchen&scene=snakes` in a browser: snakes move with real values and units in the legend.
6. With the page open, stop and restart the jar. The orange dot appears while it's down; within a scene change after it's back, the page reloads itself and the dot is gone.

- [ ] **Step 5: Commit**

```bash
git add README.md config/application.example.yaml
git commit -m "Document build, deployment, Fully Kiosk and HAProxy setup

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

Do not commit `config/application.yaml` if it contains real entity ids you'd rather keep private; add it to `.gitignore` if so.
