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
