package net.toadhead.ha.screensaver.batteries;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.toadhead.ha.screensaver.batteries.LowBattery.Status;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.Batteries;
import net.toadhead.ha.screensaver.ha.DeviceRegistry;
import net.toadhead.ha.screensaver.ha.EntityState;

/**
 * Finds the dead and low batteries among Home Assistant's entity states.
 */
public final class LowBatteryDetector {

	private static final Comparator<LowBattery> ORDER = Comparator
		.comparing((LowBattery battery) -> battery.status() == Status.DEAD ? 0 : 1)
		.thenComparing(LowBattery::level, Comparator.nullsFirst(Comparator.naturalOrder()))
		.thenComparing(LowBattery::name, String.CASE_INSENSITIVE_ORDER);

	private LowBatteryDetector() {
	}

	/**
	 * Lists the devices whose batteries are dead or low, one entry per device.
	 * @param states every entity state
	 * @param registry which device each entity belongs to
	 * @param settings the threshold, dead-after duration and exclusions
	 * @param now the current time
	 * @return the batteries, dead first, then low ones without a level, then low ones
	 * from the emptiest up
	 */
	public static List<LowBattery> detect(Collection<EntityState> states, DeviceRegistry registry, Batteries settings,
			Instant now) {
		Map<String, List<Reading>> byDevice = new LinkedHashMap<>();
		for (EntityState state : states) {
			if (isBattery(state) && !settings.exclude().contains(state.entityId())) {
				Reading reading = read(state, settings, now);
				if (reading != null) {
					String key = registry.deviceId(state.entityId()).orElse(state.entityId());
					byDevice.computeIfAbsent(key, (k) -> new ArrayList<>()).add(reading);
				}
			}
		}
		List<LowBattery> batteries = new ArrayList<>();
		byDevice.forEach((deviceId, readings) -> batteries.add(combine(deviceId, readings, registry)));
		batteries.sort(ORDER);
		return batteries;
	}

	private static boolean isBattery(EntityState state) {
		if (!state.attribute("device_class").filter("battery"::equals).isPresent()) {
			return false;
		}
		if (state.entityId().startsWith("binary_sensor.")) {
			return true;
		}
		return state.entityId().startsWith("sensor.")
				&& state.attribute("unit_of_measurement").filter("%"::equals).isPresent();
	}

	/** Returns {@code null} if the battery is neither dead nor low. */
	private static Reading read(EntityState state, Batteries settings, Instant now) {
		boolean binary = state.entityId().startsWith("binary_sensor.");
		if ("unavailable".equals(state.state())) {
			Instant changed = state.lastChanged();
			boolean dead = changed != null && !now.isBefore(changed.plus(settings.deadAfter()));
			return dead ? new Reading(state, true, null) : null;
		}
		if (binary) {
			return "on".equals(state.state()) ? new Reading(state, false, null) : null;
		}
		double value;
		try {
			value = Double.parseDouble(state.state());
		}
		catch (NumberFormatException ex) {
			return null;
		}
		if (!Double.isFinite(value) || value >= settings.threshold()) {
			return null;
		}
		return new Reading(state, false, (int) Math.floor(value));
	}

	private static LowBattery combine(String deviceId, List<Reading> readings, DeviceRegistry registry) {
		EntityState first = readings.get(0).state();
		String name = registry.deviceName(deviceId)
			.orElseGet(() -> first.attribute("friendly_name").orElse(first.entityId()));
		if (readings.stream().anyMatch(Reading::dead)) {
			return new LowBattery(deviceId, name, Status.DEAD, null);
		}
		Integer level = readings.stream()
			.map(Reading::level)
			.filter((l) -> l != null)
			.min(Comparator.naturalOrder())
			.orElse(null);
		return new LowBattery(deviceId, name, Status.LOW, level);
	}

	private record Reading(EntityState state, boolean dead, Integer level) {
	}

}
