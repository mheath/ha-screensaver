package net.toadhead.ha.screensaver.batteries;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A device whose battery is dead or running low.
 *
 * @param id the device id, or the entity id for an entity without a device
 * @param name what to call the device
 * @param status whether the battery is dead or low
 * @param level the charge in percent, or {@code null} if dead or not known
 */
public record LowBattery(String id, String name, Status status, Integer level) {

	public enum Status {

		DEAD, LOW;

		/**
		 * The lowercase name sent to the browser.
		 * @return the id, for example {@code dead}
		 */
		@JsonValue
		public String id() {
			return name().toLowerCase(Locale.ROOT);
		}

	}

}
