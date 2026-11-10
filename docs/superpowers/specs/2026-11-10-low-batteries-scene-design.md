# Low Batteries Scene — Design

**Date:** 2026-11-10
**Status:** Draft for review
**Extends:** [HA Screensaver — Design](2026-10-08-ha-screensaver-design.md). Where the two disagree about low batteries, this document wins. It replaces the low-battery detector in §3.3, the `lowBatteries` world-state field in §3.6, the "Low batteries" scene in §4.4, and the `batteries` block in §6.1.

## 1. Purpose

Nudge the household into replacing batteries before (or soon after) devices die. The scene lists every battery that is dead or below 10%, named by the device it powers, so someone glancing at a wall tablet knows exactly what to go fix.

It should feel friendly, not alarming. The user's own measure of success: with more than a handful of batteries listed, the screen should "guilt me into feeling lazy and actually go and replace the batteries."

Today the user's house has 4 battery entities `unavailable`, 2 at 0%, and 1 below 10%, so the scene will have real content from day one.

### Goals

- Show dead batteries and batteries below a threshold (default 10%).
- Name each one by its **device** (as Home Assistant's device registry names it), not by the battery entity.
- Order: dead first, then lowest level to highest.
- Let the user exclude specific battery entities (for example, a phone, whose battery the phone itself reports).

### Non-goals

- The alert scene and the alert cap (`rotation.max-alert-share`, main design §4.3). They stay in the later alerts work. With only this scene, the default weights already keep it to about a third of the rotation.
- Battery history, trends, or predicting when a battery will die.
- Battery sensors reported in volts or other non-percentage units.
- Telling the user which battery type a device takes.

### Anti-goals (would be a failure even if it technically works)

- **Crying wolf after a restart.** When Home Assistant restarts or a Zigbee/Z-Wave network hiccups, every battery briefly goes `unavailable`. The scene must not then list the whole house as dead.
- **Alarming visuals:** no red, no flashing.
- **Hue-only meaning.** The user is color blind (greens are weak). Dead versus low must differ in shape and wording, not only color.

## 2. Which batteries are listed

### 2.1 Battery entities

A battery entity is either of:

- a `sensor.*` entity with attribute `device_class: battery` and `unit_of_measurement: %`, or
- a `binary_sensor.*` entity with `device_class: battery` (on = low, off = normal).

Entities whose id is in `screensaver.batteries.exclude` are ignored completely, whether low or dead.

### 2.2 Status

Each non-excluded battery entity is in one of these states:

| Status | Rule |
|---|---|
| **Dead** | State is `unavailable` and its `last_changed` is at least `dead-after` ago (default 1 hour). |
| **Low** | A `sensor`: the state parses as a number strictly below `threshold` (default 10). A `binary_sensor`: the state is `on`. |
| *(not listed)* | Anything else: normal level, `unknown`, non-numeric, or `unavailable` for less than `dead-after`. |

`last_changed` comes from Home Assistant, so a restart of this app doesn't reset the dead-after clock. A restart of Home Assistant does: every entity's `last_changed` becomes the restart time, so truly dead batteries reappear `dead-after` after a Home Assistant restart. That is the intended trade-off.

A battery can become dead without any new state event (it simply stays `unavailable` long enough), so the list is re-evaluated on a once-a-minute tick as well as on every state change.

### 2.3 One entry per device

The listed name is the device's name: the device registry's `name_by_user` if the user renamed it, otherwise `name`. If a battery entity has no device, use its `friendly_name` attribute, falling back to the entity id.

*My call:* a device can have more than one battery entity (say a percentage sensor and a low binary sensor). The scene shows one entry per device: the worst status wins (dead, then the lower level). If a device is low and any of its *low* percentage sensors gives a number, the entry shows and sorts by the lowest such number, even when a binary sensor also says low; a number tells you more than "LOW". A percentage sensor that isn't low (say 50% while the binary sensor says low) doesn't supply the number, because "50%" on a "Running low" screen would contradict itself. Entities without a device are each their own entry.

### 2.4 Order

1. Dead, alphabetically by name.
2. Low binary sensors (no level known), alphabetically. *My call:* they go after dead and before numeric levels, because "low" with no number is closer to dead than a reading.
3. Low sensors by level ascending; ties alphabetically. 0% counts as low, not dead; it sorts first among the numbers.

## 3. Backend

### 3.1 Configuration

```yaml
screensaver:
  batteries:
    threshold: 10          # percent; strictly below this is low
    dead-after: 1h         # how long unavailable before a battery counts as dead
    exclude:               # battery entity ids never shown
      - sensor.phone_battery_level
```

Validation: `threshold` between 1 and 100; `dead-after` positive. A new scene type `LOW_BATTERIES` (JSON id `low-batteries`) joins `SceneType` with default weight 3 and duration 20 s, so it can be tuned or disabled per kiosk through the existing `rotation.scenes` and `kiosks.<id>.scenes` maps.

### 3.2 Device and entity registries

The app currently stores only `get_states` and `state_changed` data, which don't say which device an entity belongs to. After the existing `get_states` on each (re)connect, also fetch:

- the entity registry (`config/entity_registry/list` or `config/entity_registry/list_for_display`) for entity id → device id, and
- the device registry (`config/device_registry/list`) for device id → `name_by_user` / `name`.

Registries change rarely (adding or renaming a device). The builder chooses how to keep them current: re-fetch on Home Assistant's `entity_registry_updated` / `device_registry_updated` events, or on a slow poll. Either way a rename shows up without restarting this app. A registry fetch failure is logged and the scene falls back to friendly names; it must not drop the Home Assistant connection.

`HomeAssistantState` needs a way to enumerate entities (today it only supports lookup by id).

### 3.3 World state

A new field, sorted as in §2.4, uncapped (the browser decides how many to draw):

```json
"lowBatteries": [
  { "id": "device-or-entity-id", "name": "Back door lock", "status": "dead", "level": null },
  { "id": "…", "name": "Hall remote", "status": "low", "level": null },
  { "id": "…", "name": "Kitchen motion sensor", "status": "low", "level": 3 }
]
```

`level` is the integer percentage, rounded **down** (*my call*, so 9.6% shows as 9% and never as a "10%" that looks above the threshold), for low sensors and `null` otherwise. `id` is stable for the entry (device id, or entity id when there is no device). The list is empty when nothing needs attention.

## 4. Browser scene

### 4.1 Eligibility and updates

Eligible when `lowBatteries` is non-empty. While on screen, it redraws when the state changes. If the list empties while showing, it keeps showing what it last had until the rotation moves on (*my call*: a scene that blanks itself mid-display looks broken).

### 4.2 Look

Chosen by the user from mockups (option "B, a row of batteries"). Modern, clean style like the calendar, not the DOS look of snakes and the clock.

- **Background:** a dark teal-to-navy gradient (`#10302c` → `#1c2348`, 160°), distinct from the calendar's.
- **Heading:** "Running low", top left, large and bold.
- **Batteries:** each device is an upright battery drawing (outline with a terminal nub) with the device name centered beneath it.
  - **Dead:** dashed, dimmed gray outline, a small "z z" (asleep), and the word **DEAD** inside.
  - **Low sensor:** solid light outline, the percentage written large inside, and an orange (`#ffb547`) sliver of fill at the bottom sized to the level (always at least a visible sliver, so 0% still shows one).
  - **Low binary sensor:** solid light outline, a minimal sliver, and the word **LOW** inside.
- **Count and wrapping:** up to 5 batteries in a single row at full size. 6–10 wrap into two rows (up to 5 each) at a smaller size. At most 10 are drawn. If more need attention, a small "and N more" line appears at the bottom right.
- **Names:** wrap onto two lines; beyond that, cut off with "…" (*my call*).
- Sized in viewport units (like the other scenes) so it scales to the tablet.

The approved mockups are in `.superpowers/brainstorm/` (`battery-layout.html`, option B, and `battery-layout-v2.html`); they're a guide to proportions, not exact specs.

### 4.3 Demo

`?demo` data gains a few low batteries (at least one dead, one 0%, one low binary sensor) so the scene can be worked on without Home Assistant. `?scene=low-batteries` shows it alone.

## 5. Testing

- Status rules (§2.2), including the `dead-after` boundary and the once-a-minute re-evaluation, with a controllable clock.
- Device naming fallbacks and one-entry-per-device merging (§2.3).
- Ordering (§2.4).
- Exclude list.
- Registry fetch failure falls back to friendly names without disconnecting.
- Browser: eligibility, the 5 / 10 / "and N more" layout thresholds, and that dead and low are rendered with different text.
- Verify against the user's real Home Assistant, as the calendar work did: check that the 4 currently `unavailable` batteries, the two at 0%, and the one below 10% appear with sensible device names.
  - While doing so, check whether any of the `unavailable` ones are leftovers rather than dead batteries: entities with the attribute `restored: true` belong to a device or integration that Home Assistant no longer loads, and would show as dead forever. *My call:* don't add a rule up front; if leftovers turn up, the user either deletes them in Home Assistant or adds them to `exclude`, and a `restored: true` rule can be added then if it keeps happening.

## 6. Left to the builder

- Which entity-registry command to use, and how to keep the registries current (§3.2).
- Exact sizes, spacing, and SVG details within the approved look.
- Package layout and class names, following the existing code.
