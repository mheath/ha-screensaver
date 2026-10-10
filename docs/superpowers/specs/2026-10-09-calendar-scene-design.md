# Calendar Scene — Design

**Date:** 2026-10-09
**Status:** Draft for review
**Extends:** [HA Screensaver — Design](2026-10-08-ha-screensaver-design.md). Where the two disagree about the calendar, this document wins. It replaces §3.4 (calendar half), the "Today's calendar" scene in §4.4, and the `calendar` block in §6.1. It also brings Home Assistant's `get_config` (time zone) forward from plan 3.

## 1. Purpose

A glanceable view of the family's day on the wall tablets: what's on today, and once today is done, what's on tomorrow. It must be impossible to mistake tomorrow's view for today's.

The user has about 16 Home Assistant calendars and plans to show 12, so the design supports up to 20.

### Out of scope

- Event details beyond title and time (location, description, attendees).
- Per-calendar icons or calendar labels on event blocks. Color identifies the calendar.
- Days beyond tomorrow.

## 2. Behavior

### 2.1 Which day is shown

Chosen once each time the scene appears, using the tablet's clock:

1. **Today**, if today has a timed event that hasn't ended yet, or today has an all-day event and it is before `all-day-until` (default 18:00).
2. Otherwise **tomorrow**, if tomorrow has any events.
3. Otherwise the scene is not eligible and is left out of the rotation.

An event "touches" a day when any part of it falls within that local day. A timed event that crosses midnight appears on both days, clipped to each. A multi-day all-day event appears on every day it covers (HA's end date is exclusive).

### 2.2 Look

Modern and clean, not the DOS/CGA style of the snakes and clock scenes. The mockups chosen during brainstorming are the reference ("C · Bold" for the style, "1 · Different sky" for tomorrow).

- **Background:** today is a deep navy-to-purple gradient (`linear-gradient(160deg, #141a3a, #2a1645)`); tomorrow is a warm sunset gradient (`linear-gradient(160deg, #3a2410, #5a1f3a)`). The background difference is the main tomorrow cue.
- **Header:** a day tag, then the full date in heavy type, e.g. `[TODAY] Friday, October 9`. Today's tag is translucent white; tomorrow's is solid amber (`#ffb547`, dark text) reading `TOMORROW`.
- **All-day strip:** all-day events as pills in their calendar's color, between the header and the timeline.
- **Timeline:** hour labels down the left, light hour rules, and events as solid rounded blocks in their calendar's color with a soft shadow. Each block shows the title and, on a second line, the time range.
- **Font:** the tablet's system sans-serif. Nothing is downloaded.
- Times use 12- or 24-hour format per the existing `clock.use-24-hour` setting.

### 2.3 Timeline layout

- **Hour span:** from the earliest timed event's start rounded down to a whole hour, to the latest end rounded up to a whole hour, within the day (times already clipped to the day per §2.1). If that is shorter than 6 hours, extend the end later; if that reaches midnight, extend the start earlier.
- **Overlaps:** events that overlap (strictly: `a.start < b.end && b.start < a.end`; sharing an endpoint is not overlapping) form a group. Within a group each event takes the first free lane, and every event in the group is drawn at `1 / laneCount` of the width in its lane, side by side.
- **Short events** (under 40 minutes) put title and time on one line. Every block has a minimum height of one line of text.
- **Only all-day events:** no timeline. The all-day pills are shown larger in the space the timeline would use.

### 2.4 Today's live details

- Events that have ended are dimmed (about 40% opacity).
- A "now" line (white, with a dot at the left edge) marks the current time and moves every 30 seconds.
- Tomorrow has neither.
- If new event data arrives while the scene is on screen, it redraws with the new events for the same day.

## 3. Configuration

```yaml
screensaver:
  calendar:
    all-day-until: "18:00"
    calendars:
      family:
        entity: calendar.family
        label: Family
        color: blue
      school:
        entity: calendar.school
        label: School            # no color: assigned automatically

  rotation:
    scenes:
      calendar: { weight: 2, duration: 20s }
```

- `calendars.<id>`: `entity` (required), `label` (required), `color` (optional).
- `all-day-until`: a local time (default `18:00`).
- `label` is used in logs and is sent to the browser for future use; it is not displayed on the timeline.
- New scene type `calendar` with default weight 2 and duration 20 s.

### 3.1 Colors

A new `CalendarColor` enum, separate from the text-mode palette. Each color carries its block color and its text color. Order is the auto-assignment order: the most distinct colors come first.

| # | Name | Block | Text |
|---|---|---|---|
| 1 | `blue` | `#3b82f6` | `#ffffff` |
| 2 | `orange` | `#f97316` | `#ffffff` |
| 3 | `green` | `#22c55e` | `#ffffff` |
| 4 | `pink` | `#ec4899` | `#ffffff` |
| 5 | `violet` | `#8b5cf6` | `#ffffff` |
| 6 | `yellow` | `#facc15` | `#1f1a05` |
| 7 | `teal` | `#14b8a6` | `#ffffff` |
| 8 | `red` | `#ef4444` | `#ffffff` |
| 9 | `sky` | `#38bdf8` | `#06243a` |
| 10 | `lime` | `#a3e635` | `#1a2805` |
| 11 | `fuchsia` | `#d946ef` | `#ffffff` |
| 12 | `amber` | `#f59e0b` | `#2a1a02` |
| 13 | `lavender` | `#c4b5fd` | `#1e1438` |
| 14 | `emerald` | `#10b981` | `#ffffff` |
| 15 | `rose` | `#f43f5e` | `#ffffff` |
| 16 | `cyan` | `#22d3ee` | `#05282e` |
| 17 | `purple` | `#a855f7` | `#ffffff` |
| 18 | `brown` | `#a16207` | `#ffffff` |
| 19 | `slate` | `#64748b` | `#ffffff` |
| 20 | `stone` | `#a8a29e` | `#1c1917` |

`lavender` replaced `indigo`, which the user (color blind, weak greens) could not tell apart from `violet`.

### 3.2 Validation

- At most 20 calendars.
- Configured colors must be distinct.
- Calendars without a color are assigned, in configuration order, the first unused colors in the table's order. Assignment happens once at startup, so a calendar keeps its color until config changes.

## 4. Backend

### 4.1 Home Assistant client

- **Time zone:** after authenticating, send `get_config` and store `time_zone` as a `ZoneId` in `HomeAssistantState`. Until it is known, callers use the JVM's default zone.
- **Service calls:** `HomeAssistantConnector.callService(domain, service, target, data)` sends `call_service` with `return_response: true` and returns a `CompletableFuture<JsonNode>` holding the reply's `response` object. It completes exceptionally with `HaRequestException` when HA returns an error or when not connected.
- **Connected event:** publish `HomeAssistantConnected` after the initial states load, so pollers can fetch right after (re)connecting.

### 4.2 `calendar` package

`CalendarPoller` does nothing when no calendars are configured. Otherwise it fetches:

- on `HomeAssistantConnected`,
- every 15 minutes while connected,
- and just after local midnight in HA's time zone.

Each run calls `calendar.get_events` once per calendar with `start_date_time` = today 00:00 and `end_date_time` = the day after tomorrow 00:00 (HA's time zone). HA's response is keyed by entity id: `{"calendar.family": {"events": [{"start", "end", "summary", ...}]}}`.

- **Parsing:** a `start`/`end` holding a date (`2026-10-09`) is an all-day event; a date-time with offset is a timed event. A missing or blank `summary` becomes `(No title)`. An event whose dates can't be parsed is skipped and logged.
- **Failure isolation:** if one calendar's call fails, it keeps that calendar's last-known events and logs a warning; the other calendars update normally.
- **Result:** events from all calendars merged, sorted by start then title, tagged with the calendar id. When the merged list changes, the poller publishes an update so `WorldStateService` refreshes.

### 4.3 World state and kiosk config

World state gains:

```json
"calendar": {
  "events": [
    { "title": "Soccer practice", "start": "2026-10-09T15:00:00-06:00", "end": "2026-10-09T17:00:00-06:00", "allDay": false, "calendar": "school" },
    { "title": "Grandma's birthday", "start": "2026-10-09", "end": "2026-10-10", "allDay": true, "calendar": "family" }
  ]
}
```

There is no date field: times are absolute and the browser works out today and tomorrow from its own clock.

Kiosk config gains:

```json
"calendar": {
  "allDayUntil": "18:00",
  "calendars": [ { "id": "family", "label": "Family", "color": "#3b82f6", "textColor": "#ffffff" } ]
}
```

Colors are sent resolved, so the browser needs no copy of the palette.

## 5. Frontend

New module `scenes/calendar/`, following the snakes scene's split between pure logic and rendering.

**`model.ts`** (pure, unit-tested):

- `eventsOn(events, date)`: events touching a local date, timed events clipped to it (§2.1).
- `chooseDay(events, now, allDayUntil)`: `'today' | 'tomorrow' | null` per §2.1. The scene module's `isEligible` uses it with the current time.
- `hourSpan(timedEvents)`: `{ startHour, endHour }` per §2.3.
- `packLanes(timedEvents)`: each event with its `lane` and its group's `laneCount` per §2.3.

**`calendar.ts` and `calendar.css`:** render the chosen day with absolutely positioned, percentage-based blocks inside the hour grid (§2.2–2.4).

**Demo:** `?demo` adds a few calendars and events placed relative to the current time (some past, an overlap, an all-day event, and events tomorrow), so `?demo&scene=calendar` works without a backend.

## 6. Failure handling

| Situation | Behavior |
|---|---|
| HA disconnected | Poller skips its runs; last-known events stay in the world state; fetches immediately on reconnect. |
| One calendar's call fails, or its entity doesn't exist | That calendar keeps its last-known events; a warning is logged on each failed poll. |
| HA time zone not yet known | JVM default zone. |
| Unparseable event | Skipped and logged. Missing title shows `(No title)`. |
| Backend unreachable | The browser keeps its last events; the day choice keeps working from the tablet's clock. |

## 7. Testing

**Backend (JUnit)**

- Config binding: valid calendars, duplicate colors, more than 20 calendars, auto-assigned colors in order, `all-day-until` default.
- `HaSession` / connector against `FakeHomeAssistant`: `get_config` stores the zone; a service call with `return_response`; an error reply; a call while disconnected.
- `CalendarPoller` with an injected `Clock`: the two-day window in HA's zone; merge and sort; a failed calendar keeps its events; the midnight run; the fetch on connect; all-day vs timed parsing; `(No title)`.
- World state and kiosk config serialization of the new fields.

**Frontend (Vitest)**

- `chooseDay`: a timed event ending exactly now; all-day events before and after `allDayUntil`; nothing left today and an empty tomorrow.
- `eventsOn`: multi-day all-day events, a timed event crossing midnight.
- `hourSpan`: the 6-hour minimum, extending earlier when the end hits midnight.
- `packLanes`: a chain of overlaps, three at once, events sharing only an endpoint.

**Manual:** `?demo&scene=calendar` in a desktop browser, then against the real HA calendars.
