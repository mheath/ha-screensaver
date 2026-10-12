# HA Screensaver

A screensaver for wall-mounted Fully Kiosk tablets: a Spring Boot app that connects to Home Assistant and rotates scenes in the browser. This version has the clock, the NetWare-style snakes, and today's (or tomorrow's) calendar. Design: `docs/superpowers/specs/2026-10-08-ha-screensaver-design.md` and `docs/superpowers/specs/2026-10-09-calendar-scene-design.md`.

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
- `?scene=snakes`, `?scene=clock`, or `?scene=calendar` — show only that scene.
- Without `?demo`, the dev server proxies `/api` to a Boot app on `localhost:8080`.

The small orange dot in the bottom-right corner means the kiosk has lost the stream or the backend has lost Home Assistant.
