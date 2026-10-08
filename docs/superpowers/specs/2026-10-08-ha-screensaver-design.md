# HA Screensaver — Design

**Date:** 2026-10-08
**Status:** Draft for review

## 1. Purpose

Wall-mounted tablets running Fully Kiosk Browser currently turn their screens off when idle. Instead, during the hours Fully's own screensaver settings allow, each tablet should show a rotating set of "scenes" served by a standalone web app connected to Home Assistant:

- **Ambient art:** photos from a NAS folder, and a nostalgic NetWare-style "snakes" display driven by live HA values.
- **Glanceable info:** clock, weather, today's calendar.
- **Gentle attention:** low batteries and situational alerts (e.g., garage door left open in the evening). These are rotated in *more often* than other scenes but must never dominate or nag, and should be fun to look at, not alarming.

Success looks like: tablets that are pleasant to glance at, surface things that need attention without being annoying, require no touching after a deploy, and are configured by editing Spring Boot properties.

### Out of scope (v1)

- "Now playing" scene (media room / Music Assistant), planned for later; the scene interface must accommodate it.
- Admin UI for configuration (the config schema is designed so one could be added later).
- Live config refresh (`@RefreshScope`, Spring Cloud Bus). Config changes apply on restart.
- Deciding *when* the screensaver runs. Fully Kiosk's own screensaver timeout/schedule settings own that.
- Authentication. The app runs on the home LAN behind HAProxy; restrict access with HAProxy ACLs if desired.
- HEIC images, video, per-kiosk photo folders.

## 2. Architecture

One Spring Boot application, packaged as one Docker image, deployed to the home Docker server by CI, fronted by HAProxy. The frontend is built during the Maven build and served from the jar as static resources.

```
 Fully Kiosk tablets                     Docker server                         
 ┌──────────────────────┐   SSE      ┌────────────────────────────────┐  WebSocket  ┌────────────────┐
 │ browser app          │◄───────────┤ Spring Boot                    ├────────────►│ Home Assistant │
 │  scheduler + scenes  │  HTTP      │  HA client + state cache       │             └────────────────┘
 │  ?kiosk=kitchen      ├───────────►│  rule engine, pollers          │  read-only  ┌────────────────┐
 └──────────────────────┘ (via       │  photo library + resizer       ├────────────►│ NAS photo dir  │
                          HAProxy)   │  world-state stream            │   mount     └────────────────┘
                                     └────────────────────────────────┘
```

**Division of responsibility:**

- **Backend = "what is true about the house."** It holds the single HA connection, evaluates alert rules once for all kiosks, polls weather forecasts and calendars, indexes and resizes photos, and streams a derived *world state* to each kiosk.
- **Browser = "what this screen shows next."** Each kiosk runs its own weighted rotation, timing, transitions, and rendering. A backend restart or brief outage does not interrupt the scene on screen.

## 3. Backend

Java 25, current Spring Boot 4.x, Spring MVC. Packages by responsibility:

### 3.1 `config`: configuration properties

All configuration is plain Spring Boot `@ConfigurationProperties` (records), `@Validated` with Jakarta Bean Validation plus custom cross-field validators. `spring-boot-configuration-processor` generates metadata for IDE completion. Any Boot property source works (YAML, `.properties`, env vars, Spring Cloud Config). Config is bound once at startup; an invalid config fails startup with a clear message.

Constraint: Boot's binder has no polymorphism, so every structure is flat and uniform. Rules are lists of one condition record type with optional fields; scenes are a map keyed by a fixed scene-type name.

See §6 for the full property reference.

### 3.2 `ha`: Home Assistant client

- One WebSocket connection to `/api/websocket`, authenticated with a long-lived access token (from an env var / Docker secret).
- On connect: `get_config` (for HA's time zone), then subscribe to state changes and load all current states into an in-memory **entity state cache** (entity id → state, attributes, `last_changed`).
- Supports service calls with responses (`return_response`) for `weather.get_forecasts` and `calendar.get_events`.
- Reconnects with exponential backoff (capped, e.g., 1 s → 60 s). While disconnected, the cache keeps last-known values and the world state is flagged `haConnected: false`.

### 3.3 `rules`: rule engine and built-in detectors

**Custom alerts** (`screensaver.alerts.<id>`): an alert is *active* when **all** its `when` conditions hold. Each condition is one record with optional fields:

| Field | Meaning |
|---|---|
| `entity` | Entity id the condition applies to (required for `state`/`above`/`below`). |
| `state` | Entity state equals this value. |
| `above` / `below` | Entity state, parsed as a number, is strictly above/below this value. |
| `after` / `before` | Local time-of-day window (HA's time zone). If `after` > `before`, the window crosses midnight. |
| `for` | The rest of this condition must have held continuously for this duration. |

Validation: each condition must specify at least one test; `state` cannot be combined with `above`/`below`; `for` requires an entity test.

- "Evening" is expressed with the `sun.sun` entity (`state: below_horizon`); no special sun condition is needed.
- Anything more complex is an HA template entity, referenced like any other entity.
- `for` tracking: the engine records when each condition last became true. For state-based conditions this is seeded from the entity's `last_changed` at startup; for numeric conditions it starts at first observation after startup.
- Rules are re-evaluated when any referenced entity changes and on a once-per-minute tick (for time windows and `for` expiry).

**Low-battery detector** (built in, no per-device config): every entity with `device_class: battery` whose numeric state is below `screensaver.batteries.threshold`, minus an exclude list. It produces a list of `{entityId, name, level}` sorted ascending by level. Unavailable or non-numeric batteries are ignored.

### 3.4 Pollers: weather and calendar

- **Weather:** current conditions come from the configured `weather.*` entity's state and attributes. The forecast comes from `weather.get_forecasts` (configured type `daily` or `hourly`), polled every 30 minutes and on HA reconnect.
- **Calendar:** for each configured calendar entity, `calendar.get_events` for today (local midnight to midnight), polled every 15 minutes, at local midnight, and on HA reconnect. Results are merged and sorted, and tagged with the calendar's label and color.

### 3.5 `photos`: photo library and resizer

- The NAS folder is mounted read-only into the container. The library recursively scans it at startup and every 5 minutes (file-watching on network shares is unreliable).
- Supported: JPEG, PNG, WebP. Other files are ignored; files that fail to decode are skipped and logged once.
- Each photo gets an id = hash of (relative path, size, mtime), so edited/replaced files get new ids. The index stores width/height (after EXIF orientation) and EXIF date taken when present.
- `photosVersion` = hash of the full id list; it changes whenever the library changes.
- Resizing: the browser requests a size. The server rounds it up to a bucket (e.g., multiples of 256 px) to bound cache variants, applies EXIF orientation, scales to fit, encodes JPEG, and stores the result in a disk cache (a container volume). Responses carry long-lived immutable cache headers, since ids change when content changes.

### 3.6 `state`: world state and stream

The **world state** is the derived data the scenes need, never a dump of all HA entities:

```json
{
  "haConnected": true,
  "generatedAt": "2026-10-08T17:05:00-06:00",
  "snakes": [ { "id": "internet-download", "value": 412.5, "available": true } ],
  "weather": { "condition": "partlycloudy", "temperature": 64, "unit": "°F", "forecast": [ ... ] },
  "calendar": { "date": "2026-10-08", "events": [ { "title": "...", "start": "...", "end": "...", "allDay": false, "calendar": "family" } ] },
  "alerts": [ { "id": "garage-open", "title": "...", "icon": "mdi:garage-open", "message": "..." } ],
  "lowBatteries": [ { "entityId": "sensor.back_door_lock_battery", "name": "Back door lock", "level": 12 } ],
  "photosVersion": "9f3e..."
}
```

- `snakes` contains values for every configured snake (pinned and guest pool); the browser chooses which guests to show.
- `alerts` is filtered per kiosk (alerts disabled for that kiosk are omitted).

**Stream:** `GET /api/stream?kiosk=<id>` (Server-Sent Events).

- On connect it sends a `config` event (this kiosk's merged config, see §6.3) followed by a `state` event.
- After that it sends a full `state` event whenever the world state changes, throttled to at most one per second per connection. The payload is small (a few KB), so full snapshots are simpler than patches.
- A backend restart (deploy or config change) drops the stream. When it reconnects, the browser reloads the whole app (§4.3), so new code, new or removed scenes, and new config all take effect together. The browser never applies a changed config in place.

### 3.7 HTTP API

| Endpoint | Purpose |
|---|---|
| `GET /` | Frontend (`index.html` and assets). |
| `GET /api/stream?kiosk=<id>` | SSE world-state stream (§3.6). Missing/unknown kiosk id → defaults. |
| `GET /api/photos` | Photo index: `[{id, width, height, taken}]`. |
| `GET /api/photos/{id}?w=&h=` | Resized JPEG (§3.5). 404 if the id is unknown. |
| `GET /actuator/health` | Docker health check. |

## 4. Frontend

TypeScript, built with Vite, **no UI framework**. Scenes are plain modules that render with the DOM, CSS, and canvas. Vite output is written into the jar's static resources by `frontend-maven-plugin` during the Maven build. Icons are the Material Design Icons webfont (`@mdi/font`), the same icon set HA uses, so `mdi:*` names work as-is.

### 4.1 Bootstrap

1. Read `kiosk` (default `default`) and dev flags (`scene`, `demo`) from the URL.
2. Open the SSE stream. Until the first `config` + `state` arrive the screen stays black. If nothing arrives within 5 s, show the clock scene (which needs no data) until the stream connects.
3. Start the scheduler.

### 4.2 Scene interface

```ts
interface Scene {
  readonly type: SceneType;
  isEligible(state: WorldState): boolean;
  mount(container: HTMLElement, ctx: SceneContext): void | Promise<void>;
  update?(state: WorldState): void;   // live data while on screen (e.g., snake values)
  unmount(): void;
}
```

`SceneContext` carries the kiosk config, the current world state, and the scene's own options. Adding a new scene type (e.g., "now playing") means adding one module and one config entry.

### 4.3 Scheduler

- **Candidates** = each enabled scene type that is eligible, except that the alert scene type expands into one candidate per active alert.
- Each candidate's weight and duration come from the kiosk's merged config. Individual alerts may override the default alert weight.
- **Alert cap:** if the combined weight of all alert and low-battery candidates would exceed `rotation.max-alert-share` (default 0.35) of the total, those weights are scaled down proportionally. Many simultaneous alerts therefore never take over the rotation.
- Next scene = weighted random choice, excluding the scene just shown (unless it is the only candidate).
- Transitions: ~1 s crossfade between scenes.
- **Fault isolation:** if a scene throws during mount/update/unmount, the scheduler logs to the console, skips to the next scene, and sidelines that scene type for 10 minutes.
- **Reload on reconnect:** when the stream reconnects after having been connected before (not the first connect), the page reloads itself at the next scene change instead of picking another scene. The reload happens only if the stream is still connected at that moment, so Fully never loads the page while the backend is down. Restarts are the only way code or config changes, so this one rule covers deploys and config edits alike. A tablet Wi-Fi blip also causes a reload, which is harmless.

### 4.4 Scenes

**Photos**
- Fetches `/api/photos` on start and whenever `photosVersion` changes.
- Shuffled deck, kept per browser session: every photo is shown once before any repeats, and new photos join on the next reshuffle.
- Each appearance shows `photos-per-appearance` photos for `seconds-per-photo` each, with crossfades. The scene's duration is derived from these values.
- Requests images sized to `screen × devicePixelRatio`.
- A photo whose orientation doesn't match the screen is shown whole (contain), over a blurred, enlarged copy of itself (CSS `filter: blur`), so there are no black bars and no cropping.
- Optional EXIF date in a corner (`show-date`).
- Eligible when at least one photo exists. When the backend is unreachable, uses already-loaded images.

**Snakes (NetWare tribute)**
- A text-mode character grid on canvas, sized to give roughly 80 columns, using a DOS/VGA-style font. Each snake's head is `█` and its body fades through `▓ ▒ ░` toward the tail.
- **Pinned** snakes always appear. `guest-count` additional snakes are drawn at random from the **guest** pool each appearance.
- For each snake, value → `t = clamp((value − min) / (max − min), 0, 1)`; length and speed are linear interpolations from configured min/max length and speed by `t`. Values update live while the scene is on screen.
- Movement: wanders the grid at its speed, never reversing, turning at walls, occasionally changing direction at random.
- **Palette:** the 16-color text-mode palette. Black (0) is the background, which leaves 15 snake colors. Configured colors are honored for pinned snakes. Guest snakes without a color, or whose color is already on screen, get an unused color, preferring the 13 brighter colors before dark blue and dark gray. No two snakes on screen share a color.
- **Legend:** a reserved bottom row listing each on-screen snake as its colored `█▓▒░` swatch followed by its label.
- **Unavailable entity:** the snake freezes in place, renders in dark gray, and its legend entry is marked unavailable.
- Eligible when at least one snake is configured.

**Clock**
- Each appearance picks digital or analog at random (styles are an extensible list), with a 12/24-hour option.
- Uses the tablet's local time and needs no backend data.
- To avoid OLED burn-in, the face drifts a few pixels every minute.
- Always eligible.

**Weather**
- Current condition icon (HA condition → `mdi:weather-*`), temperature, and today's high/low, plus a short forecast strip (next N days or hours).
- Eligible when weather data is present.

**Today's calendar**
- All-day events at the top, then timed events in order, each tagged with its calendar's label/color.
- Past events are dimmed; the current event is highlighted.
- Eligible when at least one event remains today.

**Low batteries**
- A large, slightly sad battery graphic, with the low devices listed below it sorted by level.
- Eligible when `lowBatteries` is non-empty.

**Alert**
- One generic layout: large MDI icon, title, short message.
- One candidate per active alert, so each alert appears on its own.
- Styled to be friendly, not alarming: no red flashing.

### 4.5 Status indicator

A small, dim dot in a corner appears only when the stream is disconnected or `haConnected` is false. The screens never show error messages.

## 5. Failure handling summary

| Failure | Behavior |
|---|---|
| HA disconnected | Backend reconnects with backoff; last-known data keeps flowing with `haConnected: false`; status dot shows; unavailable snakes go gray. |
| Backend down / restarting | Browser keeps rotating with its last state. Clock and weather keep working; photos use already-loaded images. When SSE reconnects, the page reloads at the next scene change. |
| Scene throws | Skipped and sidelined for 10 minutes; rotation continues. |
| Bad or corrupt photo | Skipped and logged once. |
| Invalid config | Startup fails with validation errors (visible in CI/container logs); kiosks ride it out on last state. |
| New deploy or config change | The restart drops every stream; on reconnect each kiosk reloads at its next scene change, picking up new code, scenes, and config. |

## 6. Configuration reference

### 6.1 Example

```yaml
screensaver:
  home-assistant:
    url: ws://homeassistant.lan:8123/api/websocket
    token: ${HA_TOKEN}

  photos:
    directory: /photos            # NAS share, mounted read-only
    cache-directory: /cache/photos
    photos-per-appearance: 3
    seconds-per-photo: 20s
    show-date: true

  clock:
    use-24-hour: false

  weather:
    entity: weather.home
    forecast-type: daily          # daily | hourly
    forecast-count: 5

  calendar:
    calendars:
      family:
        entity: calendar.family
        label: Family
        color: light-cyan
      school:
        entity: calendar.school
        label: School
        color: yellow

  snakes:
    guest-count: 2
    min-length: 4                 # segments at t = 0
    max-length: 30                # segments at t = 1
    min-speed: 2                  # cells/second at t = 0
    max-speed: 20                 # cells/second at t = 1
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

  batteries:
    threshold: 20
    exclude:
      - sensor.old_remote_battery

  alerts:
    garage-open:
      title: The garage door is open
      icon: mdi:garage-open-variant
      message: Just a heads up.
      when:
        - entity: cover.garage_door
          state: open
          for: 10m
        - entity: sun.sun
          state: below_horizon
        - entity: binary_sensor.garage_motion
          state: "off"
          for: 10m

  rotation:
    max-alert-share: 0.35
    scenes:
      photos:      { weight: 5 }           # duration derived from photo settings
      snakes:      { weight: 2, duration: 45s }
      clock:       { weight: 2, duration: 20s }
      weather:     { weight: 2, duration: 20s }
      calendar:    { weight: 2, duration: 20s }
      low-battery: { weight: 3, duration: 20s }
      alert:       { weight: 3, duration: 15s }

  kiosks:
    garage:
      disabled-alerts: [garage-open]
      scenes:
        photos: { weight: 1 }
    bedroom:
      scenes:
        photos: { weight: 10 }
        snakes: { enabled: false }
```

### 6.2 Notes and validation

- **Colors** are the 16 text-mode palette names: `black, blue, green, cyan, red, magenta, brown, light-gray, dark-gray, light-blue, light-green, light-cyan, light-red, light-magenta, yellow, white`. `black` is rejected for snakes.
- **Snakes:** each needs `min < max`; pinned snakes must have distinct colors; pinned count + `guest-count` ≤ 15; `guest-count` ≤ guest pool size.
- **Alerts:** each needs `title`, `icon`, and at least one condition. An alert may set its own `weight` to override `rotation.scenes.alert.weight`.
- **Kiosks:** the `kiosks.<id>` keys are the values used in `?kiosk=`. `disabled-alerts` entries must name existing alerts. Scene overrides may set `enabled`, `weight`, and `duration`.
- **Scene types** are a fixed enum; unknown keys under `rotation.scenes` or `kiosks.*.scenes` fail validation.
- **Photos duration** is derived (`photos-per-appearance × seconds-per-photo`); setting `duration` on the photos scene fails validation.

### 6.3 Merged kiosk config

Defaults (`rotation.scenes`) merged with `kiosks.<id>.scenes` overrides, plus the scene options the browser needs (photo timing, clock format, snake definitions and length/speed ranges, calendar labels/colors, per-alert weights). The backend sends this as the stream's `config` event. The HA token and server-side settings are never sent.

## 7. Testing

**Backend (JUnit)**
- Rule engine: pure unit tests with an injected `Clock`. Covers each condition field, the AND of conditions, time windows including midnight-crossing, `for` durations (seeded and observed), and the low-battery detector.
- Config: `ApplicationContextRunner` binding tests. Valid configs bind; invalid ones (missing entity, `min ≥ max`, duplicate pinned colors, unknown kiosk alert, unknown scene type, too many snakes) fail with clear messages.
- HA client: tests against an in-test fake HA WebSocket server scripting the auth handshake, state subscription, state-change events, and service-call responses (fixtures captured from the real HA), plus disconnect/reconnect.
- Photos: fixture images covering landscape, portrait, EXIF orientations, a corrupt file, and a file added between scans; resize bucketing and caching.
- Stream: `WebTestClient` tests for `config` + `state` on connect, updates after an entity change, throttling, and per-kiosk alert filtering.

**Frontend (Vitest)**
- Scheduler with a seeded random generator: weights respected, no immediate repeats, alert expansion, `max-alert-share` cap, fault sidelining, kiosk overrides.
- Pure functions: snake value → length/speed, palette color assignment, calendar grouping/dimming, photo deck shuffling.

**Visual development (manual)**
- `?scene=<type>` forces a single scene. `?demo` feeds canned world state and config, so scenes can be built in a desktop browser with no backend, HA, or NAS.
- The Vite dev server proxies `/api` to a locally running Boot app for live-data work.
- No automated browser end-to-end tests in v1.

## 8. Build and deployment

- Single Maven module. The frontend lives in `src/main/frontend`; `frontend-maven-plugin` installs Node, runs `npm ci` and the Vite build, and the output lands in the jar's static resources. The Vitest suite runs in Maven's `test` phase.
- The image is built with `spring-boot:build-image` or a Dockerfile, whichever the CI pipeline uses.
- Container volumes: NAS photo share (read-only), photo cache, config (if not using Spring Cloud Config).
- The HA token comes from an env var or Docker secret.
- Project version + git commit are exposed via Boot build info (`/actuator/info`) for troubleshooting.
- **Fully Kiosk:** set each tablet's screensaver URL to `https://<host>/?kiosk=<id>`; the timeout and schedule are set in Fully.
